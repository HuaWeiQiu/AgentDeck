package com.agentdeck.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.data.chat.CodexBridgeEndpoint
import com.agentdeck.app.data.chat.CodexInbound
import com.agentdeck.app.data.chat.CodexRpcClient
import com.agentdeck.app.data.chat.CodexRpcException
import com.agentdeck.app.data.chat.CodexRpcTimeoutException
import com.agentdeck.app.data.chat.ConversationLinkRepository
import com.agentdeck.app.data.chat.ManagedProviderRuntime
import com.agentdeck.app.data.secure.ProviderCredentialBroker
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.chat.ApprovalKind
import com.agentdeck.app.domain.chat.ChatApproval
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatUiState
import com.agentdeck.app.domain.chat.CodexProtocol
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class ChatViewModel(
    private val cardId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var client: CodexRpcClient? = null
    private var endpoint: CodexBridgeEndpoint? = null
    private var connectJob: Job? = null
    private var eventJob: Job? = null
    private var turnStartJob: Job? = null
    private var threadId: String? = null
    private var activeTurnId: String? = null
    private var credentialBroker: ProviderCredentialBroker? = null
    private var permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT

    init {
        connect()
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            disconnectServer()
            eventJob?.cancel()
            mutableState.update {
                it.copy(
                    isConnecting = true,
                    isConnected = false,
                    isStreaming = false,
                    approval = null,
                    error = null,
                )
            }
            try {
                val card = requireNotNull(ServiceLocator.cards.getCard(cardId)) { "对话不存在" }
                permissionLevel = CodexPermissionLevel.effective(
                    card.permissionLevel,
                    ServiceLocator.experienceSettings.codexPermissionLevel.value,
                )
                mutableState.update { it.copy(card = card) }
                val profile = card.profileId?.let { profileId ->
                    requireNotNull(ServiceLocator.profiles.getProfile(profileId)) { "对话绑定的模型服务不存在" }
                }
                val managedRuntime = profile?.let { managedProfile ->
                    require(
                        managedProfile.connectionStatus == ProviderConnectionStatus.READY ||
                            managedProfile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
                    ) { "模型服务尚未验证，请先在设置中重新验证" }
                    val credentialRef = requireNotNull(managedProfile.credentialRef) {
                        "模型服务缺少 API Key，请重新配置"
                    }
                    require(ServiceLocator.credentials.contains(credentialRef)) {
                        "模型服务的 API Key 不存在，请重新配置"
                    }
                    val selectedModel = requireNotNull(card.modelId?.takeIf(String::isNotBlank)) {
                        "对话没有绑定模型，请编辑对话后重试"
                    }
                    ProviderCredentialBroker(ServiceLocator.credentials, credentialRef).also { broker ->
                        credentialBroker = broker
                    }.let { broker ->
                        ManagedProviderRuntime.from(managedProfile, selectedModel, broker.port)
                    }
                }
                val runtimeKey = managedRuntime?.conversationKey
                    ?: ConversationLinkRepository.CURRENT_RUNTIME_KEY
                val endpoint = ServiceLocator.codexBridge.launch(card, managedRuntime).getOrThrow()
                this@ChatViewModel.endpoint = endpoint
                managedRuntime?.let {
                    credentialBroker?.authorize(requireNotNull(endpoint.credentialToken))
                }
                val rpc = CodexRpcClient.connect(endpoint)
                client = rpc
                eventJob = viewModelScope.launch { rpc.events.collect(::handleInbound) }
                rpc.initialize(BuildConfig.VERSION_NAME)

                val linkedThread = ServiceLocator.conversationLinks.threadId(cardId, runtimeKey)
                val response = if (linkedThread == null) {
                    startThread(rpc, card.workspacePath)
                } else {
                    try {
                        rpc.request(
                            "thread/resume",
                            CodexProtocol.threadResumeParams(
                                linkedThread,
                                card.workspacePath,
                                permissionLevel,
                            ),
                        )
                    } catch (error: CodexRpcException) {
                        if (!error.isMissingThread() && !error.hasActiveWriter()) throw error
                        ServiceLocator.conversationLinks.clearThreadId(cardId, runtimeKey)
                        startThread(rpc, card.workspacePath)
                    }
                }
                threadId = CodexProtocol.threadId(response)
                // Each bridge owns a fresh app-server process. A persisted inProgress turn
                // belongs to the disconnected process and must be cleared server-side before
                // a new turn can run.
                CodexProtocol.inProgressTurnId(response)?.let { staleTurnId ->
                    try {
                        rpc.request(
                            "turn/interrupt",
                            JSONObject()
                                .put("threadId", requireNotNull(threadId))
                                .put("turnId", staleTurnId),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw IllegalStateException(
                            "上次未完成的 Codex 任务无法清理，请重试连接：${error.message ?: "未知错误"}",
                            error,
                        )
                    }
                }
                activeTurnId = null
                val runtime = CodexProtocol.runtime(response)
                managedRuntime?.let { expected ->
                    require(runtime.model == expected.modelId && runtime.provider == expected.providerId) {
                        "Codex 实际运行配置与对话绑定不一致，请检查模型服务配置"
                    }
                }
                ServiceLocator.conversationLinks.saveThreadId(
                    cardId,
                    requireNotNull(threadId),
                    runtimeKey,
                )
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        isStreaming = false,
                        runtimeModel = runtime.model,
                        runtimeProvider = runtime.provider,
                        items = CodexProtocol.historyItems(response),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                disconnectServer()
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isStreaming = false,
                        error = error.message ?: "无法连接 Codex",
                    )
                }
            }
        }
    }

    fun updateComposer(value: String) {
        if (value.length <= MAX_COMPOSER_LENGTH) {
            mutableState.update { it.copy(composer = value) }
        }
    }

    fun send() {
        val text = state.value.composer.trim()
        val rpc = client ?: return
        val currentThread = threadId ?: return
        if (!state.value.canSend || text.isBlank()) return
        val localItem = ChatItem(
            id = "local-user-${UUID.randomUUID()}",
            kind = ChatItemKind.USER,
            text = text,
        )
        mutableState.update {
            it.copy(
                composer = "",
                isStreaming = true,
                error = null,
                items = it.items + localItem,
            )
        }
        turnStartJob = viewModelScope.launch {
            try {
                val response = rpc.request(
                    "turn/start",
                    CodexProtocol.turnStartParams(currentThread, text, permissionLevel),
                )
                activeTurnId = CodexProtocol.turnId(response)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is CodexRpcTimeoutException) {
                    disconnectServer()
                }
                mutableState.update {
                    it.copy(
                        composer = it.composer.ifBlank { text },
                        isConnected = error !is CodexRpcTimeoutException,
                        isStreaming = false,
                        items = it.items.filterNot { item -> item.id == localItem.id },
                        error = error.message ?: "消息发送失败",
                    )
                }
            } finally {
                turnStartJob = null
            }
        }
    }

    fun stop() {
        val rpc = client ?: return
        val currentThread = threadId ?: return
        val currentTurn = activeTurnId
        if (currentTurn == null) {
            if (turnStartJob?.isActive != true) return
            turnStartJob?.cancel()
            turnStartJob = null
            disconnectServer()
            mutableState.update {
                it.copy(
                    isConnected = false,
                    isStreaming = false,
                    error = "已停止等待 Codex 响应，请重试连接",
                )
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                rpc.request(
                    "turn/interrupt",
                    JSONObject().put("threadId", currentThread).put("turnId", currentTurn),
                )
            }.fold(
                onSuccess = {
                    activeTurnId = null
                    mutableState.update { it.copy(isStreaming = false) }
                },
                onFailure = { error ->
                    mutableState.update { it.copy(error = error.message ?: "无法停止当前回复") }
                },
            )
        }
    }

    fun decideApproval(decision: String) {
        require(decision in APPROVAL_DECISIONS)
        val rpc = client ?: return
        val approval = state.value.approval ?: return
        mutableState.update { it.copy(approval = null) }
        viewModelScope.launch {
            runCatching {
                rpc.respond(approval.requestId, CodexProtocol.approvalResponse(approval, decision))
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "无法提交审批结果", approval = approval)
                }
            }
        }
    }

    suspend fun openTerminal(): LaunchResult =
        ServiceLocator.launcher.launch(cardId, permissionLevel)

    private suspend fun startThread(rpc: CodexRpcClient, cwd: String): JSONObject =
        rpc.request("thread/start", CodexProtocol.threadStartParams(cwd, permissionLevel))

    private suspend fun handleInbound(inbound: CodexInbound) {
        when (inbound) {
            is CodexInbound.Notification -> handleNotification(inbound.method, inbound.params)
            is CodexInbound.ServerRequest -> handleServerRequest(inbound)
            is CodexInbound.Disconnected -> {
                activeTurnId = null
                disconnectServer()
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isStreaming = false,
                        approval = null,
                        error = it.error ?: "Codex 连接已断开：${inbound.message}",
                    )
                }
            }
        }
    }

    private fun handleNotification(method: String, params: JSONObject) {
        when (method) {
            "turn/started" -> {
                activeTurnId = params.optJSONObject("turn")?.optString("id")
                    ?.takeIf(String::isNotBlank)
                mutableState.update { it.copy(isStreaming = true) }
            }

            "item/started", "item/completed" -> {
                val item = params.optJSONObject("item")?.let(CodexProtocol::item) ?: return
                mutableState.update { it.copy(items = CodexProtocol.upsert(it.items, item)) }
            }

            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                val delta = params.optString("delta")
                if (itemId.isNotBlank() && delta.isNotEmpty()) {
                    mutableState.update {
                        it.copy(items = CodexProtocol.appendAgentDelta(it.items, itemId, delta))
                    }
                }
            }

            "turn/completed", "turn/failed", "turn/cancelled" -> {
                activeTurnId = null
                val completed = params.optJSONObject("turn")
                val authoritative = completed?.let(CodexProtocol::turnItems).orEmpty()
                mutableState.update { current ->
                    val merged = authoritative.fold(current.items, CodexProtocol::upsert)
                    val turnError = completed?.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf(String::isNotBlank)
                    current.copy(
                        isStreaming = false,
                        items = merged,
                        error = turnError ?: current.error,
                    )
                }
            }

            "error" -> {
                val willRetry = params.optBoolean("willRetry", false)
                if (!willRetry) activeTurnId = null
                mutableState.update {
                    it.copy(
                        isStreaming = if (willRetry) it.isStreaming else false,
                        error = CodexProtocol.errorMessage(params),
                    )
                }
            }
        }
    }

    private suspend fun handleServerRequest(request: CodexInbound.ServerRequest) {
        val params = request.params
        val approval = when (request.method) {
            "item/commandExecution/requestApproval" -> ChatApproval(
                requestId = request.id,
                kind = ApprovalKind.COMMAND,
                title = "允许运行命令？",
                detail = params.optString("command")
                    .takeIf(String::isNotBlank)
                    ?: params.optString("reason").takeIf(String::isNotBlank)
                    ?: "Codex 请求运行一条命令",
            )

            "item/fileChange/requestApproval" -> ChatApproval(
                requestId = request.id,
                kind = ApprovalKind.FILE_CHANGE,
                title = "允许修改文件？",
                detail = params.optString("reason").takeIf(String::isNotBlank)
                    ?: "Codex 请求写入当前工作区",
            )

            "item/permissions/requestApproval" -> {
                val permissions = params.optJSONObject("permissions") ?: JSONObject()
                val reason = params.optString("reason").takeIf(String::isNotBlank)
                ChatApproval(
                    requestId = request.id,
                    kind = ApprovalKind.PERMISSIONS,
                    title = "允许额外权限？",
                    detail = listOfNotNull(reason, permissions.toString(2))
                        .joinToString("\n")
                        .ifBlank { "Codex 请求本轮额外的文件或网络权限" },
                    requestedPermissions = permissions.toString(),
                )
            }

            else -> null
        }
        if (approval == null) {
            client?.respondUnsupported(request.id, request.method)
        } else if (CodexProtocol.shouldAutoDecline(permissionLevel)) {
            try {
                client?.respond(request.id, CodexProtocol.approvalResponse(approval, "decline"))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                disconnectServer()
                mutableState.update {
                    it.copy(
                        isConnected = false,
                        isStreaming = false,
                        error = "只读权限无法安全回绝 Codex 操作，请重新连接",
                    )
                }
                return
            }
            mutableState.update { current ->
                current.copy(
                    items = current.items + ChatItem(
                        id = "permission-blocked-${request.id}",
                        kind = ChatItemKind.TOOL,
                        text = "只读权限已阻止 ${approval.blockedActionLabel()}",
                        status = "blocked",
                    ),
                )
            }
        } else {
            mutableState.update { it.copy(approval = approval) }
        }
    }

    override fun onCleared() {
        disconnectServer()
        super.onCleared()
    }

    private fun disconnectServer() {
        client?.close()
        client = null
        credentialBroker?.close()
        credentialBroker = null
        endpoint?.let { ServiceLocator.codexBridge.stop(it) }
        endpoint = null
    }

    companion object {
        private const val MAX_COMPOSER_LENGTH = 32_000
        private val APPROVAL_DECISIONS = setOf("accept", "acceptForSession", "decline", "cancel")

        fun factory(cardId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cardId) as T
            }
    }
}

private fun ChatApproval.blockedActionLabel(): String = when (kind) {
    ApprovalKind.COMMAND -> "命令执行"
    ApprovalKind.FILE_CHANGE -> "文件修改"
    ApprovalKind.PERMISSIONS -> "额外权限请求"
}

private fun CodexRpcException.isMissingThread(): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("not found") || normalized.contains("no rollout found")
}

internal fun CodexRpcException.hasActiveWriter(): Boolean =
    message.lowercase().contains("active writer")
