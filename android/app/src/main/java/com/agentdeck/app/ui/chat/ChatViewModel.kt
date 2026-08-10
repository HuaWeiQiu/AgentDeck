package com.agentdeck.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.data.chat.ActiveCodexConnections
import com.agentdeck.app.data.chat.ChatSessionRegistry
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
import com.agentdeck.app.domain.chat.ChatError
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.CodexModelOption
import com.agentdeck.app.domain.chat.ChatUiState
import com.agentdeck.app.domain.chat.ChatUserInputRequest
import com.agentdeck.app.domain.chat.CodexProtocol
import com.agentdeck.app.domain.chat.ConversationIdentityPolicy
import com.agentdeck.app.domain.chat.QueuedChatMessage
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class ChatViewModel(
    private val cardId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    // Streamed assistant text lives outside ChatUiState so per-token updates only
    // recompose the message that is actually streaming.
    private val mutableStreamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = mutableStreamingText.asStateFlow()
    private val streamingCoalescer = StreamingDeltaCoalescer(STREAM_FLUSH_INTERVAL_MS)
    private var streamingFlushJob: Job? = null

    private var client: CodexRpcClient? = null
    private var endpoint: CodexBridgeEndpoint? = null
    private var connectJob: Job? = null
    private var eventJob: Job? = null
    private var turnStartJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var autoReconnecting = false
    private var threadId: String? = null
    private var activeTurnId: String? = null
    private var credentialBroker: ProviderCredentialBroker? = null
    private var permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT
    private var reasoningEffort: String? = null
    private var developerInstructions: String? = null
    private var steerFailedForTurn: String? = null

    init {
        connect()
    }

    fun connect() {
        // Manual retry interrupts any scheduled auto-reconnect.
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        autoReconnecting = false
        startConnect()
    }

    private fun startConnect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            // A session held by the registry survives leaving the chat screen;
            // reattach to it instead of launching a fresh bridge.
            val held = ChatSessionRegistry.take(cardId)
            if (held != null) {
                reattach(held)
                return@launch
            }
            disconnectServer()
            eventJob?.cancel()
            mutableState.update {
                it.copy(
                    isConnecting = true,
                    isConnected = false,
                    isStreaming = false,
                    approval = null,
                    userInputRequest = null,
                    error = null,
                )
            }
            try {
                // Card/profile validation and bridge launch do file and process work;
                // keep them off the main thread.
                val (card, managedRuntime, endpoint, modelOptions, providerLabel) = withContext(Dispatchers.IO) {
                    val card = requireNotNull(ServiceLocator.cards.getCard(cardId)) { "对话不存在" }
                    permissionLevel = CodexPermissionLevel.effective(
                        card.permissionLevel,
                        ServiceLocator.experienceSettings.codexPermissionLevel.value,
                    )
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
                    val endpoint = ServiceLocator.codexBridge.launch(card, managedRuntime).getOrThrow()
                    // Take ownership immediately: authorization/model lookup can still suspend or
                    // fail, and cancellation must be able to stop the process we just launched.
                    this@ChatViewModel.endpoint = endpoint
                    managedRuntime?.let {
                        credentialBroker?.authorize(requireNotNull(endpoint.credentialToken))
                    }
                    val modelOptions = profile?.let { managedProfile ->
                        ServiceLocator.profiles.getModels(managedProfile.id).map { model ->
                            CodexModelOption(model.id, model.displayName)
                        }
                    }.orEmpty()
                    ConnectPrep(card, managedRuntime, endpoint, modelOptions, profile?.name)
                }
                mutableState.update { it.copy(card = card) }
                val runtimeKey = managedRuntime?.conversationKey
                    ?: ConversationLinkRepository.CURRENT_RUNTIME_KEY
                val rpc = CodexRpcClient.connect(endpoint)
                client = rpc
                ActiveCodexConnections.register(cardId, rpc)
                eventJob = viewModelScope.launch { rpc.events.collect(::handleInbound) }
                rpc.initialize(BuildConfig.VERSION_NAME)
                val shouldDiscoverModels = managedRuntime == null &&
                    !endpoint.profileConfig.usesCustomProvider
                val appServerModels = if (shouldDiscoverModels) {
                    discoverModels(rpc)
                } else {
                    emptyList()
                }
                val profileConfig = ConversationIdentityPolicy.mergeIntoConfig(
                    endpoint.profileConfig.sessionConfig(managedRuntime != null),
                    card.identity,
                )
                reasoningEffort = profileConfig.optString("model_reasoning_effort")
                    .trim()
                    .takeIf(String::isNotBlank)
                developerInstructions = profileConfig.opt("developer_instructions")
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.let { value ->
                        require(value is String) { "developer_instructions 必须是字符串" }
                        value
                    }

                val linkedThread = ServiceLocator.conversationLinks.threadId(cardId, runtimeKey)
                val response = if (linkedThread == null) {
                    startThread(rpc, card.workspacePath, profileConfig, managedRuntime)
                } else {
                    try {
                        rpc.request(
                            "thread/resume",
                            CodexProtocol.threadResumeParams(
                                linkedThread,
                                card.workspacePath,
                                permissionLevel,
                                profileConfig,
                                managedRuntime?.modelId,
                                managedRuntime?.providerId,
                            ),
                        )
                    } catch (error: CodexRpcException) {
                        if (!error.isMissingThread() && !error.hasActiveWriter()) throw error
                        ServiceLocator.conversationLinks.clearThreadId(cardId, runtimeKey)
                        startThread(rpc, card.workspacePath, profileConfig, managedRuntime)
                    }
                }
                CodexProtocol.threadUpdatedAtEpochMs(response)?.let { timestamp ->
                    ServiceLocator.cards.touchActivity(cardId, timestamp)
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
                resetStreaming()
                val availableModels = availableModels(
                    managed = managedRuntime != null,
                    configured = modelOptions,
                    discovered = appServerModels,
                    runtimeModel = runtime.model,
                )
                autoReconnecting = false
                reconnectAttempts = 0
                mutableState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        isStreaming = false,
                        isReconnecting = false,
                        streamingItemId = null,
                        runtimeModel = runtime.model,
                        runtimeProvider = providerLabel ?: runtime.provider,
                        items = CodexProtocol.historyItems(response),
                        availableModels = availableModels,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                disconnectServer()
                throw error
            } catch (error: Exception) {
                disconnectServer()
                if (autoReconnecting) {
                    scheduleReconnect(error.message ?: "无法连接 Codex")
                } else {
                    mutableState.update {
                        it.copy(
                            isConnecting = false,
                            isConnected = false,
                            isStreaming = false,
                            isReconnecting = false,
                            error = ChatError.from(error.message ?: "无法连接 Codex"),
                        )
                    }
                }
            }
        }
    }

    fun updateComposer(value: String) {
        if (value.length <= MAX_COMPOSER_LENGTH) {
            mutableState.update { it.copy(composer = value) }
        }
    }

    /** In-chat model override for subsequent turns; null restores the card model. */
    fun setModelOverride(modelId: String?) {
        if (modelId != null && state.value.availableModels.none { it.id == modelId }) return
        mutableState.update { it.copy(selectedModel = modelId) }
    }

    /** In-chat permission override for subsequent turns; null restores the default. */
    fun setPermissionOverride(level: CodexPermissionLevel?) {
        mutableState.update { it.copy(selectedPermission = level) }
        permissionLevel = CodexPermissionLevel.effective(
            level ?: mutableState.value.card?.permissionLevel,
            ServiceLocator.experienceSettings.codexPermissionLevel.value,
        )
    }

    fun send() {
        // Sending during auto-reconnect interrupts the backoff and retries now.
        if (state.value.isReconnecting) {
            interruptReconnect()
            startConnect()
            return
        }
        val text = state.value.composer.trim()
        if (text.isBlank()) return
        val rpc = client ?: return
        val currentThread = threadId ?: return
        // While a turn is running, steer it with the new instruction. If steering is
        // not possible (no active turn id yet, or it already failed once), queue the
        // message and send it as a new turn when the current one completes.
        val turn = activeTurnId
        if (state.value.isStreaming && turn != null && turnStartJob?.isActive != true) {
            if (steerFailedForTurn == turn) {
                enqueue(text)
                return
            }
            mutableState.update { it.copy(composer = "", error = null) }
            viewModelScope.launch {
                try {
                    rpc.request(
                        "turn/steer",
                        CodexProtocol.turnSteerParams(currentThread, turn, text),
                    )
                    ServiceLocator.cards.touchActivity(cardId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    steerFailedForTurn = turn
                    enqueue(text)
                }
            }
            return
        }
        if (!state.value.canSend) return
        startTurn(text, rpc, currentThread)
    }

    /** Remove a queued message before it is auto-sent. */
    fun cancelQueued() {
        mutableState.update { it.copy(queued = null) }
    }

    /** Answer a pending `item/tool/requestUserInput` request. */
    fun respondUserInput(answers: Map<String, List<String>>) {
        val rpc = client ?: return
        val request = state.value.userInputRequest ?: return
        mutableState.update { it.copy(userInputRequest = null) }
        viewModelScope.launch {
            runCatching {
                rpc.respond(request.requestId, CodexProtocol.userInputResponse(request, answers))
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        error = ChatError.from(error.message ?: "无法提交回答"),
                        userInputRequest = request,
                    )
                }
            }
        }
    }

    private fun enqueue(text: String) {
        mutableState.update {
            it.copy(
                composer = "",
                queued = QueuedChatMessage(id = UUID.randomUUID().toString(), text = text),
            )
        }
    }

    private fun startTurn(text: String, rpc: CodexRpcClient, currentThread: String) {
        val localItem = ChatItem(
            id = "local-user-${UUID.randomUUID()}",
            kind = ChatItemKind.USER,
            text = text,
        )
        mutableState.update {
            it.copy(
                composer = "",
                queued = null,
                isStreaming = true,
                error = null,
                items = it.items + localItem,
            )
        }
        turnStartJob = viewModelScope.launch {
            try {
                val response = rpc.request(
                    "turn/start",
                    CodexProtocol.turnStartParams(
                        currentThread,
                        text,
                        permissionLevel,
                        modelOverride = mutableState.value.selectedModel,
                        collaborationModel = mutableState.value.selectedModel
                            ?: mutableState.value.runtimeModel,
                        reasoningEffort = reasoningEffort,
                        developerInstructions = developerInstructions,
                    ),
                )
                activeTurnId = CodexProtocol.turnId(response)
                steerFailedForTurn = null
                ServiceLocator.cards.touchActivity(cardId)
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
                        error = ChatError.from(error.message ?: "消息发送失败"),
                    )
                }
            } finally {
                turnStartJob = null
            }
        }
    }

    fun stop() {
        // Stopping during auto-reconnect cancels it and stays disconnected.
        if (state.value.isReconnecting) {
            interruptReconnect()
            disconnectServer()
            mutableState.update {
                it.copy(isConnected = false, isStreaming = false)
            }
            return
        }
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
                    error = ChatError.from("已停止等待 Codex 响应，请重试连接"),
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
                    mutableState.update {
                        it.copy(error = ChatError.from(error.message ?: "无法停止当前回复"))
                    }
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
                    it.copy(
                        error = ChatError.from(error.message ?: "无法提交审批结果"),
                        approval = approval,
                    )
                }
            }
        }
    }

    /**
     * Resume a session handed over by [ChatSessionRegistry]: replay items completed
     * while detached, redeliver pending approvals/questions, and keep consuming the
     * same live event stream. No new bridge process is launched.
     */
    private suspend fun reattach(held: ChatSessionRegistry.HeldSession) {
        disconnectServer()
        mutableState.update {
            it.copy(
                isConnecting = true,
                isConnected = false,
                isStreaming = false,
                approval = null,
                userInputRequest = null,
                error = null,
            )
        }
        try {
            val card = withContext(Dispatchers.IO) {
                requireNotNull(ServiceLocator.cards.getCard(cardId)) { "对话不存在" }
            }
            permissionLevel = held.permissionLevel
            client = held.client
            endpoint = held.endpoint
            credentialBroker = held.broker
            reasoningEffort = held.reasoningEffort
            developerInstructions = held.developerInstructions
            threadId = held.threadId
            activeTurnId = held.activeTurnId
            ActiveCodexConnections.register(cardId, held.client)
            eventJob?.cancel()
            eventJob = viewModelScope.launch { held.client.events.collect(::handleInbound) }

            val replayed: List<ChatItem> = buildList {
                held.bufferedItems.toList().forEach { json -> CodexProtocol.item(json)?.let(::add) }
                held.bufferedTurns.toList().forEach { json -> addAll(CodexProtocol.turnItems(json)) }
            }
            resetStreaming()
            autoReconnecting = false
            reconnectAttempts = 0
            mutableState.update { current ->
                val merged = replayed.fold(current.items, CodexProtocol::upsert)
                current.copy(
                    card = card,
                    isConnecting = false,
                    isConnected = true,
                    isStreaming = held.isBusy,
                    isReconnecting = false,
                    streamingItemId = null,
                    runtimeModel = held.runtimeModel,
                    runtimeProvider = held.runtimeProvider,
                    availableModels = held.availableModels,
                    selectedModel = held.selectedModel,
                    selectedPermission = held.selectedPermission,
                    approval = held.pendingApproval,
                    userInputRequest = held.pendingUserInput,
                    queued = held.queued,
                    items = merged,
                    error = null,
                )
            }
            held.pendingRequests.toList().forEach { request -> handleServerRequest(request) }
            if (!held.isBusy && held.queued != null) {
                startTurn(held.queued.text, held.client, held.threadId)
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
                    isReconnecting = false,
                    error = ChatError.from(error.message ?: "无法恢复 Codex 会话"),
                )
            }
        }
    }

    private suspend fun startThread(
        rpc: CodexRpcClient,
        cwd: String,
        profileConfig: JSONObject,
        managedRuntime: ManagedProviderRuntime?,
    ): JSONObject = rpc.request(
        "thread/start",
        CodexProtocol.threadStartParams(
            cwd = cwd,
            permissionLevel = permissionLevel,
            profileConfig = profileConfig,
            modelOverride = managedRuntime?.modelId,
            modelProviderOverride = managedRuntime?.providerId,
        ),
    )

    private suspend fun handleInbound(inbound: CodexInbound) {
        when (inbound) {
            is CodexInbound.Notification -> handleNotification(inbound.method, inbound.params)
            is CodexInbound.ServerRequest -> handleServerRequest(inbound)
            is CodexInbound.Disconnected -> {
                activeTurnId = null
                commitStreaming()
                disconnectServer()
                beginReconnect("Codex 连接已断开：${inbound.message}")
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
                if (method == "item/completed") discardStreaming(item.id)
                mutableState.update { it.copy(items = CodexProtocol.upsert(it.items, item)) }
            }

            "item/fileChange/patchUpdated" -> {
                val itemId = params.optString("itemId")
                val patches = CodexProtocol.patchUpdatedPatches(params)
                if (itemId.isBlank() || patches.isEmpty()) return
                mutableState.update { current ->
                    val index = current.items.indexOfFirst { it.id == itemId }
                    if (index < 0) return@update current
                    val item = current.items[index]
                    val existingPaths = item.patches.mapTo(mutableSetOf()) { it.path }
                    val merged = item.patches + patches.filterNot { it.path in existingPaths }
                    current.copy(
                        items = current.items.toMutableList().apply {
                            set(index, item.copy(patches = merged))
                        },
                    )
                }
            }

            "thread/tokenUsage/updated" -> {
                val total = params.optJSONObject("tokenUsage")
                    ?.optJSONObject("last")
                    ?.optLong("totalTokens", 0L)
                    ?: return
                if (total > 0) {
                    mutableState.update { it.copy(lastTurnTokens = formatTokenCount(total)) }
                }
            }

            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                val delta = params.optString("delta")
                if (itemId.isBlank() || delta.isEmpty()) return
                if (state.value.streamingItemId != itemId) {
                    // A different message started streaming: commit what we have so far.
                    commitStreaming()
                    mutableState.update {
                        it.copy(
                            streamingItemId = itemId,
                            items = it.items.ensureAssistantItem(itemId),
                        )
                    }
                }
                streamingCoalescer.append(delta)
                scheduleStreamingFlush()
            }

            "turn/completed", "turn/failed", "turn/cancelled" -> {
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { ServiceLocator.cards.touchActivity(cardId) }
                }
                activeTurnId = null
                steerFailedForTurn = null
                // Flush partial streamed text first so cancelled/failed turns keep it;
                // authoritative items below replace it when the server sent full text.
                commitStreaming()
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
                        error = turnError?.let(ChatError::from) ?: current.error,
                    )
                }
                // A message queued while steering failed goes out as a fresh turn once
                // the previous turn completed successfully.
                val queued = state.value.queued
                if (method == "turn/completed" && queued != null &&
                    client != null && threadId != null
                ) {
                    startTurn(queued.text, requireNotNull(client), requireNotNull(threadId))
                }
            }

            "error" -> {
                val willRetry = params.optBoolean("willRetry", false)
                if (!willRetry) activeTurnId = null
                mutableState.update {
                    it.copy(
                        isStreaming = if (willRetry) it.isStreaming else false,
                        error = ChatError.from(CodexProtocol.errorMessage(params)),
                    )
                }
            }
        }
    }

    private fun scheduleStreamingFlush() {
        if (streamingFlushJob?.isActive == true) return
        streamingFlushJob = viewModelScope.launch {
            delay(STREAM_FLUSH_INTERVAL_MS)
            flushStreaming()
        }
    }

    private fun flushStreaming() {
        val drained = streamingCoalescer.drain() ?: return
        mutableStreamingText.value = (mutableStreamingText.value ?: "") + drained
    }

    /**
     * Flush pending deltas and fold the accumulated streaming text into the timeline
     * item, then leave streaming mode. No-op when nothing is streaming.
     */
    private fun commitStreaming() {
        val itemId = state.value.streamingItemId ?: return
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        flushStreaming()
        val text = mutableStreamingText.value
        mutableStreamingText.value = null
        mutableState.update {
            it.copy(
                streamingItemId = null,
                items = if (text.isNullOrEmpty()) {
                    it.items
                } else {
                    CodexProtocol.appendAgentDelta(it.items, itemId, text)
                },
            )
        }
    }

    /** Drop streaming state without committing; the server sent authoritative text. */
    private fun discardStreaming(itemId: String) {
        if (state.value.streamingItemId != itemId) return
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        streamingCoalescer.drain()
        mutableStreamingText.value = null
        mutableState.update { it.copy(streamingItemId = null) }
    }

    private fun resetStreaming() {
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        streamingCoalescer.drain()
        mutableStreamingText.value = null
    }

    private fun beginReconnect(reason: String) {
        reconnectJob?.cancel()
        autoReconnecting = true
        reconnectAttempts = 0
        scheduleReconnect(reason)
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            autoReconnecting = false
            reconnectAttempts = 0
            mutableState.update {
                it.copy(
                    isConnecting = false,
                    isConnected = false,
                    isStreaming = false,
                    approval = null,
                    isReconnecting = false,
                    error = ChatError.from(reason),
                )
            }
            return
        }
        reconnectAttempts += 1
        val delayMs = reconnectDelayMs(reconnectAttempts)
        reconnectJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isConnecting = false,
                    isConnected = false,
                    isStreaming = false,
                    approval = null,
                    isReconnecting = true,
                    error = null,
                )
            }
            delay(delayMs)
            startConnect()
        }
    }

    private fun interruptReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnecting = false
        reconnectAttempts = 0
        mutableState.update { it.copy(isReconnecting = false) }
    }

    private suspend fun handleServerRequest(request: CodexInbound.ServerRequest) {
        val params = request.params
        if (request.method == "item/tool/requestUserInput") {
            val inputRequest = CodexProtocol.parseUserInputRequest(request.id, params)
            if (inputRequest == null) {
                client?.respondUnsupported(request.id, request.method)
            } else {
                mutableState.update { it.copy(userInputRequest = inputRequest) }
            }
            return
        }
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
                        error = ChatError.from("只读权限无法安全回绝 Codex 操作，请重新连接"),
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
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnecting = false
        resetStreaming()
        holdOrDisconnect()
        super.onCleared()
    }

    /**
     * Leaving the screen hands a healthy session to [ChatSessionRegistry] so the turn
     * keeps running in the background; anything unhealthy is torn down instead.
     */
    private fun holdOrDisconnect() {
        val rpc = client
        val currentThread = threadId
        val currentEndpoint = endpoint
        // The registry collector takes over event consumption; stop ours first so the
        // underlying channel is not drained by two collectors at once.
        eventJob?.cancel()
        eventJob = null
        if (shouldKeepSessionInBackground(state.value) &&
            rpc != null && currentThread != null && currentEndpoint != null &&
            state.value.isConnected &&
            ChatSessionRegistry.hold(
                cardId = cardId,
                cardName = state.value.card?.name ?: "Codex",
                client = rpc,
                endpoint = currentEndpoint,
                threadId = currentThread,
                permissionLevel = permissionLevel,
                runtimeModel = state.value.runtimeModel,
                runtimeProvider = state.value.runtimeProvider,
                availableModels = state.value.availableModels,
                selectedModel = state.value.selectedModel,
                selectedPermission = state.value.selectedPermission,
                reasoningEffort = reasoningEffort,
                developerInstructions = developerInstructions,
                activeTurnId = activeTurnId,
                pendingApproval = state.value.approval,
                pendingUserInput = state.value.userInputRequest,
                queued = state.value.queued,
                broker = credentialBroker,
            )
        ) {
            client = null
            endpoint = null
            credentialBroker = null
            ActiveCodexConnections.unregister(cardId, rpc)
            return
        }
        disconnectServer()
    }

    /**
     * Idempotent, never blocks the caller. Bridge teardown (Thread.sleep/waitFor in the
     * runtime) runs on a short-lived IO scope that cancels itself once finished, so
     * onCleared() stays off the main thread without leaking coroutines.
     */
    private fun disconnectServer() {
        ChatSessionRegistry.stopAndRemove(cardId)
        val rpc = client
        client = null
        val broker = credentialBroker
        credentialBroker = null
        val endpoint = endpoint
        this.endpoint = null
        if (rpc != null) {
            ActiveCodexConnections.unregister(cardId, rpc)
        }
        rpc?.close()
        broker?.close()
        if (endpoint != null) {
            val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            teardownScope.launch {
                try {
                    ServiceLocator.codexBridge.stop(endpoint)
                } finally {
                    teardownScope.cancel()
                }
            }
        }
    }

    companion object {
        private const val MAX_COMPOSER_LENGTH = 32_000
        private const val STREAM_FLUSH_INTERVAL_MS = 64L
        private const val MAX_RECONNECT_ATTEMPTS = 8
        private val APPROVAL_DECISIONS = setOf("accept", "acceptForSession", "decline", "cancel")

        fun factory(cardId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cardId) as T
            }
    }
}

internal fun shouldKeepSessionInBackground(state: ChatUiState): Boolean =
    state.isStreaming || state.approval != null || state.userInputRequest != null || state.queued != null

internal fun reconnectDelayMs(attempt: Int): Long {
    val schedule = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    return schedule[(attempt - 1).coerceIn(0, schedule.lastIndex)]
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM tokens".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk tokens".format(tokens / 1_000.0)
    else -> "$tokens tokens"
}

private fun List<ChatItem>.ensureAssistantItem(itemId: String): List<ChatItem> =
    if (any { it.id == itemId }) {
        this
    } else {
        this + ChatItem(itemId, ChatItemKind.ASSISTANT, "")
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

private data class ConnectPrep(
    val card: com.agentdeck.app.domain.model.AgentCard,
    val runtime: ManagedProviderRuntime?,
    val endpoint: CodexBridgeEndpoint,
    val models: List<CodexModelOption>,
    val providerLabel: String?,
)

private suspend fun discoverModels(rpc: CodexRpcClient): List<CodexModelOption> {
    val models = LinkedHashMap<String, CodexModelOption>()
    var cursor: String? = null
    repeat(MAX_MODEL_LIST_PAGES) {
        val response = try {
            rpc.request(
                "model/list",
                CodexProtocol.modelListParams(cursor),
                timeoutMillis = MODEL_LIST_TIMEOUT_MILLIS,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return models.values.toList()
        }
        val page = CodexProtocol.modelPage(response)
        page.models.forEach { model -> models.putIfAbsent(model.id, model) }
        cursor = page.nextCursor
        if (cursor == null) return models.values.toList()
    }
    return models.values.toList()
}

internal fun availableModels(
    managed: Boolean,
    configured: List<CodexModelOption>,
    discovered: List<CodexModelOption>,
    runtimeModel: String,
): List<CodexModelOption> {
    val candidates = if (managed) configured else discovered
    val merged = LinkedHashMap<String, CodexModelOption>()
    candidates.forEach { model -> merged.putIfAbsent(model.id, model) }
    merged.putIfAbsent(runtimeModel, CodexModelOption(runtimeModel, runtimeModel, isDefault = true))
    return merged.values.sortedWith(
        compareBy<CodexModelOption> {
            when {
                it.id == runtimeModel -> 0
                it.isDefault -> 1
                else -> 2
            }
        }.thenBy { it.displayName.lowercase() },
    )
}

private const val MAX_MODEL_LIST_PAGES = 5
private const val MODEL_LIST_TIMEOUT_MILLIS = 5_000L
