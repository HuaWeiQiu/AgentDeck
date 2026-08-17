package com.agentdeck.app.ui.chat

import android.net.Uri
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
import com.agentdeck.app.data.chat.RpcRequestId
import com.agentdeck.app.data.secure.ProviderCredentialBroker
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.chat.ApprovalKind
import com.agentdeck.app.domain.chat.ChatApproval
import com.agentdeck.app.domain.chat.ChatAttachment
import com.agentdeck.app.domain.chat.ChatError
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.CodexModelOption
import com.agentdeck.app.domain.chat.ChatUiState
import com.agentdeck.app.domain.chat.ChatUserInputRequest
import com.agentdeck.app.domain.chat.CodexProtocol
import com.agentdeck.app.domain.chat.ConversationIdentityPolicy
import com.agentdeck.app.domain.chat.HostWriteApproval
import com.agentdeck.app.domain.chat.QueuedChatMessage
import com.agentdeck.app.domain.chat.PendingApprovalQueue
import com.agentdeck.app.domain.chat.PendingUserInputQueue
import com.agentdeck.app.domain.extensions.ExtensionSessionHandle
import com.agentdeck.app.domain.extensions.ExtensionSessionPlan
import com.agentdeck.app.domain.host.HostApprovalGateway
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val cardId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private val transcriptRepository = ChatTranscriptRepository()
    internal val transcriptState: StateFlow<ChatTranscriptUiState> = combine(
        state,
        transcriptRepository.state,
    ) { current, transcript ->
        current.toTranscriptState(transcript)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChatUiState().toTranscriptState(ChatTranscriptStoreState()),
    )

    // Streamed assistant text lives outside ChatUiState so per-token updates only
    // recompose the message that is actually streaming.
    private val mutableStreamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = mutableStreamingText.asStateFlow()
    private val streamingCoalescer = StreamingDeltaCoalescer(STREAM_FLUSH_INTERVAL_MS)
    private var streamingFlushJob: Job? = null

    private val markdownCache get() = SharedChatMarkdown.cache()
    private val markdownParseDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val markdownParseJobs = mutableMapOf<String, Job>()
    private val markdownParseContents = mutableMapOf<String, String>()
    private val mutableMarkdownDocuments = MutableStateFlow<Map<String, ChatMarkdownDocument>>(emptyMap())
    internal val markdownDocuments: StateFlow<Map<String, ChatMarkdownDocument>> =
        mutableMarkdownDocuments.asStateFlow()
    private val visibleMarkdownIds = MutableStateFlow<Set<String>>(emptySet())

    private var client: CodexRpcClient? = null
    private var endpoint: CodexBridgeEndpoint? = null
    private var connectJob: Job? = null
    private var eventJob: Job? = null
    private var eventCollectorScope: CoroutineScope? = null
    private var turnStartJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var autoReconnecting = false
    private var threadId: String? = null
    private var activeTurnId: String? = null
    private var credentialBroker: ProviderCredentialBroker? = null
    private var extensionSession: ExtensionSessionHandle? = null
    private var permissionLevel: CodexPermissionLevel = CodexPermissionLevel.DEFAULT
    private var reasoningEffort: String? = null
    private var developerInstructions: String? = null
    private var steerFailedForTurn: String? = null
    private val pendingApprovalQueue = PendingApprovalQueue()
    private var respondingApproval: ChatApproval? = null
    private val pendingUserInputQueue = PendingUserInputQueue()
    private var respondingUserInput: ChatUserInputRequest? = null
    private val serverResponsesInFlight = mutableSetOf<RpcRequestId>()
    private var handoffInProgress = false
    private val hostWriteWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    /** 仅当前对话有效的「本会话允许写真实目录」。 */
    @Volatile
    private var hostWriteAllowedForSession = false
    private val hostApprovalGateway = HostApprovalGateway { _, _, summary ->
        awaitHostWriteApproval(summary)
    }

    private val trimListener: (Int) -> Unit = ::onTrimMemory

    init {
        ChatMemoryTrim.register(trimListener)
        observeMarkdownItems()
        connect()
    }

    /** System memory pressure: drop off-screen ASTs, keep visible docs intact. */
    private fun onTrimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val keep = LinkedHashSet(visibleMarkdownIds.value)
        transcriptRepository.state.value.streamingItemId?.let(keep::add)
        markdownCache.retain(keep)
        mutableMarkdownDocuments.value = markdownCache.snapshot()
    }

    internal fun requestMarkdown(messageId: String, content: String) {
        if (content.isEmpty()) return
        markdownCache.get(messageId, content)?.let { cached ->
            if (mutableMarkdownDocuments.value[messageId] !== cached) {
                mutableMarkdownDocuments.value = markdownCache.snapshot()
            }
            return
        }
        if (markdownParseContents[messageId] == content && markdownParseJobs[messageId]?.isActive == true) {
            return
        }
        markdownParseJobs.remove(messageId)?.cancel()
        markdownParseContents[messageId] = content
        markdownParseJobs[messageId] = viewModelScope.launch {
            try {
                val document = withContext(markdownParseDispatcher) {
                    SharedChatMarkdown.parse(messageId, content)
                }
                if (markdownParseContents[messageId] == content) {
                    mutableMarkdownDocuments.value = markdownCache.put(document)
                }
            } finally {
                if (markdownParseContents[messageId] == content) {
                    markdownParseJobs.remove(messageId)
                    markdownParseContents.remove(messageId)
                }
            }
        }
    }

    internal fun touchMarkdown(messageId: String) {
        // LRU touch only. visibleMarkdownIds has exactly one writer — the
        // viewport snapshotFlow — so window membership stays race-free.
        markdownCache.touch(messageId)
    }

    internal fun reportVisibleItems(ids: Set<String>) {
        transcriptRepository.reportVisibleItems(ids)
        val assistantIds = if (ids.isEmpty()) {
            ids
        } else {
            val itemsById = transcriptRepository.state.value.items.associateBy { it.id }
            ids.filterTo(LinkedHashSet()) { itemsById[it]?.kind == ChatItemKind.ASSISTANT }
        }
        if (visibleMarkdownIds.value != assistantIds) {
            visibleMarkdownIds.value = assistantIds
        }
        assistantIds.forEach(markdownCache::touch)
    }

    /** Re-fetch an evicted history page by its original cursor (P2 bounded window). */
    internal fun refetchPage(pageKey: String) {
        val request = transcriptRepository.beginRefetchPage(pageKey) ?: return
        val rpc = client
        val currentThread = threadId
        if (rpc == null || currentThread == null) {
            transcriptRepository.failRefetchPage(request)
            return
        }
        viewModelScope.launch {
            try {
                val response = rpc.request(
                    "thread/turns/list",
                    CodexProtocol.threadTurnsListParams(currentThread, request.cursor),
                )
                val page = withContext(Dispatchers.Default) {
                    CodexProtocol.historyPage(response)
                }
                transcriptRepository.finishRefetchPage(request, page)
            } catch (error: CancellationException) {
                transcriptRepository.failRefetchPage(request)
                throw error
            } catch (error: Exception) {
                transcriptRepository.failRefetchPage(request)
            }
        }
    }

    internal fun loadOlderHistory() {
        val request = transcriptRepository.beginLoadOlder() ?: return
        val rpc = client
        val currentThread = threadId
        if (rpc == null || currentThread == null) {
            transcriptRepository.failLoadOlder(request)
            return
        }
        viewModelScope.launch {
            try {
                val response = rpc.request(
                    "thread/turns/list",
                    CodexProtocol.threadTurnsListParams(currentThread, request.cursor),
                )
                val page = withContext(Dispatchers.Default) {
                    CodexProtocol.historyPage(response)
                }
                preheatMarkdown(page.items)
                transcriptRepository.finishLoadOlder(request, page)
            } catch (error: CancellationException) {
                transcriptRepository.failLoadOlder(request)
                throw error
            } catch (error: Exception) {
                transcriptRepository.failLoadOlder(request)
                mutableState.update {
                    it.copy(error = ChatError.from(error.message ?: "无法加载更早的对话"))
                }
            }
        }
    }

    internal fun approvalPatches(itemId: String): List<com.agentdeck.app.domain.chat.FilePatch> =
        transcriptRepository.patchesFor(itemId)

    private suspend fun preheatMarkdown(items: List<ChatItem>) {
        val candidates = items.filter {
            it.kind == ChatItemKind.ASSISTANT && it.text.isNotEmpty()
        }.takeLast(INITIAL_MARKDOWN_PREPARSE_COUNT)
        if (candidates.isEmpty()) return
        val documents = withContext(markdownParseDispatcher) {
            candidates.map { item -> SharedChatMarkdown.parse(item.id, item.text) }
        }
        documents.forEach(markdownCache::put)
        mutableMarkdownDocuments.value = markdownCache.snapshot()
    }

    private fun observeMarkdownItems() {
        viewModelScope.launch {
            combine(transcriptState, visibleMarkdownIds) { current, visibleIds ->
                MarkdownWindow(current.items, visibleIds, current.streamingItemId)
            }.distinctUntilChanged().collect { window ->
                val scheduled = visibleMarkdownWindow(
                    items = window.items,
                    visibleIds = window.visibleIds,
                    streamingItemId = window.streamingItemId,
                )
                val scheduledIds = scheduled.mapTo(mutableSetOf()) { it.id }
                // Do not cancel out-of-window parse jobs here: a visible message
                // whose job was launched by ChatMessage before the viewport report
                // lands would be cancelled with nothing left to relaunch it
                // (stuck "正在排版回复"). Jobs are single-flight per id and the
                // cache below stays LRU-bounded, so a short waiting queue is cheap.
                val retained = markdownCache.retain(scheduledIds)
                if (retained != mutableMarkdownDocuments.value) {
                    mutableMarkdownDocuments.value = retained
                }
                scheduled.forEach { item -> requestMarkdown(item.id, item.text) }
            }
        }
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
            // Warm keep-alive: track foreground; do not kill pi/dsh.
            com.agentdeck.app.data.runtime.NativeRuntimeBudget.onCodexForeground()
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
                // The title is useful immediately; bridge startup and history restore continue below.
                mutableState.update {
                    it.copy(card = card, runtimeModel = card.modelId ?: it.runtimeModel)
                }
                val memPreview = ChatTranscriptPreviewCache.get(cardId, card.profileId, card.modelId)
                val preview = memPreview.ifEmpty {
                    withContext(Dispatchers.IO) {
                        ServiceLocator.diskTranscriptPreview.get(cardId, card.profileId, card.modelId)
                    }
                }
                transcriptRepository.showPreview(preview)
                // Card/profile validation and bridge launch do file and process work;
                // keep them off the main thread.
                val (managedRuntime, endpoint, modelOptions, providerLabel, extensionPlan) = withContext(Dispatchers.IO) {
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
                    val session = ServiceLocator.extensions.openSession(card.id).also {
                        extensionSession = it
                    }
                    val endpoint = ServiceLocator.codexBridge.launch(
                        card,
                        managedRuntime,
                        session.plan,
                    ).getOrThrow()
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
                    ConnectPrep(managedRuntime, endpoint, modelOptions, profile?.name, session.plan)
                }
                val runtimeKey = managedRuntime?.conversationKey
                    ?: ConversationLinkRepository.CURRENT_RUNTIME_KEY
                val rpc = CodexRpcClient.connect(endpoint)
                client = rpc
                ActiveCodexConnections.register(cardId, rpc)
                startEventCollection(rpc)
                rpc.initialize(BuildConfig.VERSION_NAME)
                val shouldDiscoverModels = managedRuntime == null &&
                    !endpoint.profileConfig.usesCustomProvider
                val (appServerModels, inheritedMcpServers) = coroutineScope {
                    val models = async {
                        if (shouldDiscoverModels) discoverModels(rpc) else emptyList()
                    }
                    val mcpServers = async { readEffectiveMcpServers(rpc, card.workspacePath) }
                    models.await() to mcpServers.await()
                }
                val baseProfileConfig = ConversationIdentityPolicy.mergeIntoConfig(
                    endpoint.profileConfig.sessionConfig(managedRuntime != null),
                    card.identity,
                )
                val profileConfig = ServiceLocator.extensions.mergeSessionConfig(
                    baseProfileConfig,
                    extensionPlan,
                    inheritedMcpServers,
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
                // L1 host workspace: bind IPC session + inject CLI instructions before thread start
                bindHostWorkspaceSession(endpoint.instanceKey)

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
                val historyPage = withContext(Dispatchers.Default) {
                    CodexProtocol.initialHistoryPage(response)
                }
                preheatMarkdown(historyPage.items)
                ServiceLocator.conversationLinks.saveThreadId(
                    cardId,
                    requireNotNull(threadId),
                    runtimeKey,
                )
                resetStreaming()
                transcriptRepository.reset(historyPage)
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
                        runtimeModel = runtime.model,
                        runtimeProvider = providerLabel ?: runtime.provider,
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

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || state.value.isImportingAttachment) return
        val available = com.agentdeck.app.data.chat.ChatAttachmentStore.MAX_ATTACHMENTS -
            state.value.attachments.size
        if (available <= 0) {
            mutableState.update { it.copy(error = ChatError.from("单次最多添加 4 个附件")) }
            return
        }
        mutableState.update { it.copy(isImportingAttachment = true, error = null) }
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            try {
                uris.take(available).forEach { uri ->
                    try {
                        val attachment = ServiceLocator.chatAttachments.import(cardId, uri)
                        if (attachment.kind == com.agentdeck.app.domain.chat.ChatAttachmentKind.IMAGE &&
                            !supportsImageInput(
                                state.value.availableModels,
                                state.value.selectedModel ?: state.value.runtimeModel,
                            )
                        ) {
                            ServiceLocator.chatAttachments.remove(attachment)
                            error("当前模型明确不支持图片输入")
                        }
                        mutableState.update { current ->
                            current.copy(attachments = current.attachments + attachment)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        failures += attachmentFailureMessage(error.message)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                mutableState.update {
                    it.copy(
                        isImportingAttachment = false,
                        error = failures.takeIf { it.isNotEmpty() }
                            ?.let(::attachmentFailureSummary)
                            ?.let { ChatError.Attachment(it) },
                    )
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        val attachment = state.value.attachments.firstOrNull { it.id == id } ?: return
        mutableState.update { current ->
            current.copy(attachments = current.attachments.filterNot { it.id == id })
        }
        viewModelScope.launch { ServiceLocator.chatAttachments.remove(attachment) }
    }

    /** In-chat model override for subsequent turns; null restores the card model. */
    fun setModelOverride(modelId: String?) {
        if (modelId != null && state.value.availableModels.none { it.id == modelId }) return
        if (state.value.attachments.any {
                it.kind == com.agentdeck.app.domain.chat.ChatAttachmentKind.IMAGE
            } && !supportsImageInput(state.value.availableModels, modelId ?: state.value.runtimeModel)
        ) {
            mutableState.update { it.copy(error = ChatError.from("该模型不支持已添加的图片")) }
            return
        }
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
        val attachments = state.value.attachments
        if (text.isBlank() && attachments.isEmpty()) return
        val rpc = client ?: return
        val currentThread = threadId ?: return
        // While a turn is running, steer it with the new instruction. If steering is
        // not possible (no active turn id yet, or it already failed once), queue the
        // message and send it as a new turn when the current one completes.
        val turn = activeTurnId
        if (state.value.isStreaming && turn != null && turnStartJob?.isActive != true) {
            if (steerFailedForTurn == turn) {
                enqueue(text, attachments)
                return
            }
            mutableState.update { it.copy(composer = "", attachments = emptyList(), error = null) }
            viewModelScope.launch {
                try {
                    rpc.request(
                        "turn/steer",
                        CodexProtocol.turnSteerParams(currentThread, turn, text, attachments),
                    )
                    ServiceLocator.cards.touchActivity(cardId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    steerFailedForTurn = turn
                    enqueue(text, attachments)
                }
            }
            return
        }
        if (!state.value.canSend) return
        startTurn(text, attachments, rpc, currentThread)
    }

    /** Remove a queued message before it is auto-sent. */
    fun cancelQueued() {
        mutableState.update { current ->
            val queued = current.queued ?: return@update current
            current.copy(
                composer = current.composer.ifBlank { queued.text },
                attachments = if (current.attachments.isEmpty()) queued.attachments else current.attachments,
                queued = null,
            )
        }
    }

    /** Answer a pending `item/tool/requestUserInput` request. */
    fun respondUserInput(answers: Map<String, List<String>>) {
        val rpc = client ?: return
        val request = state.value.userInputRequest ?: return
        if (respondingUserInput != null) return
        if (!serverResponsesInFlight.add(request.requestId)) return
        respondingUserInput = request
        mutableState.update { it.copy(userInputRequest = null) }
        viewModelScope.launch {
            runCatching {
                rpc.respond(request.requestId, CodexProtocol.userInputResponse(request, answers))
            }.fold(
                onSuccess = {
                    serverResponsesInFlight.remove(request.requestId)
                    if (respondingUserInput?.requestId == request.requestId) {
                        respondingUserInput = null
                        promoteNextUserInput()
                    }
                },
                onFailure = { error ->
                    serverResponsesInFlight.remove(request.requestId)
                    if (respondingUserInput?.requestId == request.requestId) {
                        respondingUserInput = null
                        mutableState.update {
                            it.copy(
                                error = ChatError.from(error.message ?: "无法提交回答"),
                                userInputRequest = request,
                            )
                        }
                    }
                }
            )
        }
    }

    private fun enqueue(text: String, attachments: List<ChatAttachment>) {
        mutableState.update {
            it.copy(
                composer = "",
                attachments = emptyList(),
                queued = QueuedChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = text,
                    attachments = attachments,
                ),
            )
        }
    }

    private fun startTurn(
        text: String,
        attachments: List<ChatAttachment>,
        rpc: CodexRpcClient,
        currentThread: String,
    ) {
        val protocolText = CodexProtocol.userMessageText(text, attachments)
        val messageText = CodexProtocol.displayUserMessageText(
            protocolText,
            attachments.count { it.kind == com.agentdeck.app.domain.chat.ChatAttachmentKind.IMAGE },
        )
        val localItem = ChatItem(
            id = "local-user-${UUID.randomUUID()}",
            kind = ChatItemKind.USER,
            text = messageText,
        )
        mutableState.update {
            it.copy(
                composer = "",
                attachments = emptyList(),
                queued = null,
                isStreaming = true,
                error = null,
            )
        }
        transcriptRepository.append(localItem)
        turnStartJob = viewModelScope.launch {
            try {
                val response = rpc.request(
                    "turn/start",
                    CodexProtocol.turnStartParams(
                        currentThread,
                        text,
                        permissionLevel,
                        attachments = attachments,
                        modelOverride = mutableState.value.selectedModel,
                        collaborationModel = mutableState.value.selectedModel
                            ?: mutableState.value.runtimeModel,
                        reasoningEffort = reasoningEffort,
                        developerInstructions = developerInstructions,
                    ),
                )
                val startedTurnId = CodexProtocol.turnId(response)
                activeTurnId = startedTurnId
                transcriptRepository.updateItem(localItem.id) { item ->
                    item.copy(turnId = startedTurnId)
                }
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
                        attachments = if (it.attachments.isEmpty()) attachments else it.attachments,
                        isConnected = error !is CodexRpcTimeoutException,
                        isStreaming = false,
                        error = ChatError.from(error.message ?: "消息发送失败"),
                    )
                }
                transcriptRepository.removeItem(localItem.id)
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
        if (respondingApproval != null) return
        respondingApproval = approval
        serverResponsesInFlight.add(approval.requestId)
        mutableState.update { it.copy(approval = null) }
        viewModelScope.launch {
            runCatching {
                rpc.respond(approval.requestId, CodexProtocol.approvalResponse(approval, decision))
            }.fold(
                onSuccess = {
                    serverResponsesInFlight.remove(approval.requestId)
                    if (respondingApproval?.requestId == approval.requestId) {
                        respondingApproval = null
                        promoteNextApproval()
                    }
                },
                onFailure = { error ->
                    serverResponsesInFlight.remove(approval.requestId)
                    if (respondingApproval?.requestId == approval.requestId) {
                        respondingApproval = null
                        mutableState.update {
                            it.copy(
                                error = ChatError.from(error.message ?: "无法提交审批结果"),
                                approval = approval,
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * 宿主工作区写操作审批结果（ADR-0011）。
     * @param allow 是否允许
     * @param forSession 为 true 时仅本对话后续写操作免询问（不写入高级设置）
     */
    fun decideHostWrite(allow: Boolean, forSession: Boolean = false) {
        val pending = state.value.hostWriteApproval ?: return
        if (allow && forSession) {
            hostWriteAllowedForSession = true
        }
        mutableState.update { it.copy(hostWriteApproval = null) }
        hostWriteWaiters.remove(pending.id)?.complete(allow)
    }

    private suspend fun awaitHostWriteApproval(summary: String): Boolean {
        // 持久偏好：不再询问
        val mode = ServiceLocator.experienceSettings.hostWriteApprovalMode.value
        if (mode == com.agentdeck.app.domain.host.HostWriteApprovalMode.NEVER_ASK) {
            return true
        }
        // 本会话临时授权
        if (hostWriteAllowedForSession) {
            return true
        }
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        hostWriteWaiters[id] = deferred
        mutableState.update {
            it.copy(hostWriteApproval = HostWriteApproval(id = id, summary = summary))
        }
        return try {
            deferred.await()
        } finally {
            hostWriteWaiters.remove(id)
            mutableState.update { state ->
                if (state.hostWriteApproval?.id == id) state.copy(hostWriteApproval = null) else state
            }
        }
    }

    private fun bindHostWorkspaceSession(instanceKey: String) {
        val experience = ServiceLocator.experienceSettings
        val advanced = experience.level.value.advancedEnabled
        val workspaceOn = advanced &&
            experience.hostWorkspaceEnabled.value &&
            ServiceLocator.workspaceGrants.primaryGrant() != null
        val labOn = com.agentdeck.app.BuildConfig.HOST_LAB &&
            experience.level.value == com.agentdeck.app.domain.settings.ExperienceLevel.DEVELOPER &&
            experience.labRiskAccepted.value &&
            (experience.labIntentEnabled.value ||
                experience.labUiEnabled.value ||
                experience.labPrivEnabled.value)
        if (!workspaceOn && !labOn) {
            ServiceLocator.hostToolRelay.unbind()
            ServiceLocator.hostApprovalGateway.delegate =
                com.agentdeck.app.domain.host.DenyAllHostApprovalGateway
            mutableState.update { it.copy(hostWorkspaceBanner = null) }
            return
        }
        ServiceLocator.hostApprovalGateway.delegate = hostApprovalGateway
        ServiceLocator.hostToolRelay.bind(
            conversationId = cardId,
            instanceId = instanceKey,
        )
        if (workspaceOn) {
            developerInstructions = mergeHostInstructions(
                developerInstructions,
                HOST_WORKSPACE_INSTRUCTIONS,
                marker = "agentdeck-host workspace.",
            )
        }
        if (labOn) {
            developerInstructions = mergeHostInstructions(
                developerInstructions,
                buildLabHostInstructions(experience),
                marker = "AgentDeck Lab Host",
            )
        }
        val bannerParts = buildList {
            if (workspaceOn) {
                val grantName =
                    ServiceLocator.workspaceGrants.primaryGrant()?.displayName ?: "已授权文件夹"
                add("本机文件夹 · $grantName")
            }
            if (labOn) {
                val labs = buildList {
                    if (experience.labIntentEnabled.value) add("Intent")
                    if (experience.labUiEnabled.value) add("屏幕")
                    if (experience.labPrivEnabled.value) add("特权壳")
                }
                add("Lab · ${labs.joinToString("/")}")
            }
        }
        mutableState.update {
            it.copy(hostWorkspaceBanner = bannerParts.joinToString("  |  ").ifBlank { null })
        }
    }

    private fun buildLabHostInstructions(
        experience: com.agentdeck.app.data.repo.ExperienceSettingsRepository,
    ): String = buildString {
        appendLine("AgentDeck Lab Host tools are available (experimental, user-gated).")
        appendLine("Use only via agentdeck-host; never invent raw Android paths.")
        if (experience.labIntentEnabled.value) {
            appendLine("L2 Intent:")
            appendLine("  agentdeck-host intent.open_url --url https://example.com")
            appendLine("  agentdeck-host intent.share_text --text HELLO")
        }
        if (experience.labUiEnabled.value) {
            appendLine("L3 UI (requires system Accessibility for AgentDeck Lab):")
            appendLine("  agentdeck-host ui.snapshot")
            appendLine("  agentdeck-host ui.click_text --text LABEL")
        }
        if (experience.labPrivEnabled.value) {
            appendLine("L4 whitelist shell only (id / uname -a / getprop ... / pm list packages -3):")
            appendLine("  agentdeck-host priv.shell --command 'id'")
        }
        append("Click/shell may require user approval.")
    }

    private fun mergeHostInstructions(base: String?, block: String, marker: String): String {
        if (base.isNullOrBlank()) return block
        if (base.contains(marker)) return base
        return base.trimEnd() + "\n\n" + block
    }

    private fun unbindHostWorkspaceSession() {
        hostWriteWaiters.values.forEach { it.complete(false) }
        hostWriteWaiters.clear()
        hostWriteAllowedForSession = false
        ServiceLocator.hostApprovalGateway.delegate =
            com.agentdeck.app.domain.host.DenyAllHostApprovalGateway
        ServiceLocator.hostToolRelay.unbind()
        mutableState.update { it.copy(hostWriteApproval = null, hostWorkspaceBanner = null) }
    }

    /**
     * Resume a session handed over by [ChatSessionRegistry]: replay items completed
     * while detached, redeliver pending approvals/questions, and keep consuming the
     * same live event stream. No new bridge process is launched.
     */
    private suspend fun reattach(held: ChatSessionRegistry.HeldSession) {
        disconnectServer()
        // ChatSessionRegistry.take() removes the only background owner. Adopt every
        // resource before the first suspend/failure point so disconnectServer() can
        // always release the bridge, brokers, proxy, and Skill snapshot together.
        permissionLevel = held.permissionLevel
        client = held.client
        endpoint = held.endpoint
        credentialBroker = held.broker
        extensionSession = held.extensionSession
        reasoningEffort = held.reasoningEffort
        developerInstructions = held.developerInstructions
        threadId = held.threadId
        activeTurnId = held.activeTurnId
        ActiveCodexConnections.register(cardId, held.client)
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
            mutableState.update {
                it.copy(card = card, runtimeModel = card.modelId ?: it.runtimeModel)
            }
            val memPreview = ChatTranscriptPreviewCache.get(cardId, card.profileId, card.modelId)
            val preview = memPreview.ifEmpty {
                withContext(Dispatchers.IO) {
                    ServiceLocator.diskTranscriptPreview.get(cardId, card.profileId, card.modelId)
                }
            }
            transcriptRepository.showPreview(preview)
            bindHostWorkspaceSession(held.endpoint.instanceKey)

            // The registry intentionally does not retain a second copy of transcript
            // history. Rebuild the recent page from the still-live app-server before
            // replaying authoritative events completed while the screen was detached.
            val historyResponse = held.client.request(
                "thread/turns/list",
                CodexProtocol.threadTurnsListParams(
                    threadId = held.threadId,
                    limit = INITIAL_HISTORY_TURNS,
                ),
            )
            val historyPage = withContext(Dispatchers.Default) {
                CodexProtocol.historyPage(historyResponse)
            }
            preheatMarkdown(historyPage.items)
            transcriptRepository.reset(historyPage)

            val replayed: List<ChatItem> = buildList {
                held.bufferedItems.toList().forEach { json -> CodexProtocol.item(json)?.let(::add) }
                held.bufferedTurns.toList().forEach { json -> addAll(CodexProtocol.turnItems(json)) }
            }
            transcriptRepository.upsertAll(replayed)
            stopEventCollection()
            resetStreaming()
            autoReconnecting = false
            reconnectAttempts = 0
            respondingApproval = null
            pendingApprovalQueue.restore(held.pendingApprovals)
            respondingUserInput = null
            pendingUserInputQueue.restore(held.pendingUserInputs)
            mutableState.update { current ->
                current.copy(
                    card = card,
                    isConnecting = false,
                    isConnected = true,
                    isStreaming = held.isBusy,
                    isReconnecting = false,
                    runtimeModel = held.runtimeModel,
                    runtimeProvider = held.runtimeProvider,
                    availableModels = held.availableModels,
                    selectedModel = held.selectedModel,
                    selectedPermission = held.selectedPermission,
                    approval = held.pendingApproval,
                    userInputRequest = held.pendingUserInput,
                    queued = held.queued,
                    error = null,
                )
            }
            held.pendingRequests.toList().forEach { request -> handleServerRequest(request) }
            promoteNextApproval()
            promoteNextUserInput()
            // The handoff fence keeps post-marker events in the client channel. Restore
            // the authoritative held snapshot before consuming them so a resolved event
            // cannot race with and then be overwritten by stale pending UI state.
            startEventCollection(held.client)
            val queued = held.queued
            if (!held.isBusy && queued != null) {
                startTurn(queued.text, queued.attachments, held.client, held.threadId)
            }
        } catch (error: CancellationException) {
            disconnectServer()
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

    private suspend fun readEffectiveMcpServers(rpc: CodexRpcClient, cwd: String): Set<String> {
        val configRead = runCatching {
            rpc.request(
                "config/read",
                JSONObject().put("cwd", cwd).put("includeLayers", false),
            )
        }.getOrElse { error ->
            if (ServiceLocator.extensions.requiresManagedMcp) {
                throw IllegalStateException(
                    "无法验证 Codex 的 MCP 配置，安全版已停止连接：${error.message ?: "未知错误"}",
                    error,
                )
            }
            JSONObject()
        }
        val effectiveConfig = configRead.optJSONObject("config") ?: JSONObject()
        return (effectiveConfig.optJSONObject("mcp_servers")
            ?: effectiveConfig.optJSONObject("mcpServers"))
            ?.keys()
            ?.asSequence()
            ?.toSet()
            .orEmpty()
    }

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
            is CodexInbound.Handoff -> Unit
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
                val notificationTurnId = params.optString("turnId")
                    .takeIf(String::isNotBlank)
                    ?: activeTurnId
                val item = params.optJSONObject("item")?.let(CodexProtocol::item)
                    ?.copy(turnId = notificationTurnId)
                    ?: return
                if (method == "item/completed") discardStreaming(item.id)
                transcriptRepository.upsert(item)
            }

            "item/fileChange/patchUpdated" -> {
                val itemId = params.optString("itemId")
                val patches = CodexProtocol.patchUpdatedPatches(params)
                if (itemId.isBlank() || patches.isEmpty()) return
                transcriptRepository.mergePatches(itemId, patches)
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

            "serverRequest/resolved" -> {
                val requestId = CodexProtocol.resolvedRequestId(params) ?: return
                pendingApprovalQueue.remove(requestId)
                pendingUserInputQueue.remove(requestId)
                serverResponsesInFlight.remove(requestId)
                if (respondingApproval?.requestId == requestId) respondingApproval = null
                if (respondingUserInput?.requestId == requestId) respondingUserInput = null
                mutableState.update { current ->
                    current.copy(
                        approval = current.approval?.takeUnless { it.requestId == requestId },
                        userInputRequest = current.userInputRequest
                            ?.takeUnless { it.requestId == requestId },
                    )
                }
                promoteNextApproval()
                promoteNextUserInput()
            }

            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                val delta = params.optString("delta")
                val notificationTurnId = params.optString("turnId")
                    .takeIf(String::isNotBlank)
                    ?: activeTurnId
                if (itemId.isBlank() || delta.isEmpty()) return
                if (transcriptRepository.state.value.streamingItemId != itemId) {
                    // A different message started streaming: commit what we have so far.
                    commitStreaming()
                    transcriptRepository.ensureAssistantItem(itemId, notificationTurnId)
                    transcriptRepository.setStreamingItemId(itemId)
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
                respondingApproval = null
                pendingApprovalQueue.clear()
                respondingUserInput = null
                pendingUserInputQueue.clear()
                // Flush partial streamed text first so cancelled/failed turns keep it;
                // authoritative items below replace it when the server sent full text.
                commitStreaming()
                val completed = params.optJSONObject("turn")
                val authoritative = completed?.let(CodexProtocol::turnItems).orEmpty()
                transcriptRepository.upsertAll(authoritative)
                transcriptRepository.foldTailIntoLatestPage()
                mutableState.update { current ->
                    val turnError = completed?.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf(String::isNotBlank)
                    current.copy(
                        isStreaming = false,
                        approval = null,
                        userInputRequest = null,
                        error = turnError?.let(ChatError::from) ?: current.error,
                    )
                }
                // A message queued while steering failed goes out as a fresh turn once
                // the previous turn completed successfully.
                val queued = state.value.queued
                if (!handoffInProgress && method == "turn/completed" && queued != null &&
                    client != null && threadId != null
                ) {
                    startTurn(
                        queued.text,
                        queued.attachments,
                        requireNotNull(client),
                        requireNotNull(threadId),
                    )
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
        val itemId = transcriptRepository.state.value.streamingItemId ?: return
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        flushStreaming()
        val text = mutableStreamingText.value
        mutableStreamingText.value = null
        if (!text.isNullOrEmpty()) {
            transcriptRepository.appendAgentDelta(itemId, text)
        }
        transcriptRepository.setStreamingItemId(null)
    }

    /** Drop streaming state without committing; the server sent authoritative text. */
    private fun discardStreaming(itemId: String) {
        if (transcriptRepository.state.value.streamingItemId != itemId) return
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        streamingCoalescer.drain()
        mutableStreamingText.value = null
        transcriptRepository.setStreamingItemId(null)
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
                respondUnsupportedToServer(request.id, request.method)
            } else if (state.value.userInputRequest != null || respondingUserInput != null) {
                if (!pendingUserInputQueue.offer(inputRequest)) {
                    respondUnsupportedToServer(request.id, "too many pending user input requests")
                }
            } else {
                mutableState.update { it.copy(userInputRequest = inputRequest) }
            }
            return
        }
        val mcpApproval = if (request.method == "mcpServer/elicitation/request") {
            CodexProtocol.parseMcpToolApproval(
                request.id,
                params,
                extensionSession?.plan?.mcpApprovalIdentities.orEmpty(),
                requireManagedIdentity = ServiceLocator.extensions.requiresManagedMcp,
            )
        } else {
            null
        }
        if (request.method == "mcpServer/elicitation/request" && mcpApproval == null) {
            respondToServer(request.id, CodexProtocol.cancelMcpElicitationResponse())
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

            "mcpServer/elicitation/request" -> mcpApproval

            else -> null
        }
        if (approval == null) {
            respondUnsupportedToServer(request.id, request.method)
        } else if (CodexProtocol.shouldAutoDecline(permissionLevel, approval.kind)) {
            try {
                respondToServer(
                    request.id,
                    CodexProtocol.approvalResponse(approval, "decline"),
                )
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
            transcriptRepository.append(
                ChatItem(
                    id = "permission-blocked-${request.id}",
                    kind = ChatItemKind.TOOL,
                    text = "只读权限已阻止 ${approval.blockedActionLabel()}",
                    status = "blocked",
                ),
            )
        } else if (state.value.approval != null || respondingApproval != null) {
            if (!pendingApprovalQueue.offer(approval)) {
                respondToServer(
                    request.id,
                    CodexProtocol.approvalResponse(approval, "cancel"),
                )
            }
        } else {
            mutableState.update { it.copy(approval = approval) }
        }
    }

    private fun promoteNextApproval() {
        if (respondingApproval != null || state.value.approval != null) return
        pendingApprovalQueue.poll()?.let { next ->
            mutableState.update { it.copy(approval = next) }
        }
    }

    private fun promoteNextUserInput() {
        if (respondingUserInput != null || state.value.userInputRequest != null) return
        pendingUserInputQueue.poll()?.let { next ->
            mutableState.update { it.copy(userInputRequest = next) }
        }
    }

    private suspend fun respondToServer(requestId: RpcRequestId, response: JSONObject) {
        val rpc = client ?: return
        serverResponsesInFlight.add(requestId)
        try {
            rpc.respond(requestId, response)
        } finally {
            serverResponsesInFlight.remove(requestId)
        }
    }

    private suspend fun respondUnsupportedToServer(requestId: RpcRequestId, method: String) {
        val rpc = client ?: return
        serverResponsesInFlight.add(requestId)
        try {
            rpc.respondUnsupported(requestId, method)
        } finally {
            serverResponsesInFlight.remove(requestId)
        }
    }

    override fun onCleared() {
        ChatMemoryTrim.unregister(trimListener)
        reconnectJob?.cancel()
        reconnectJob = null
        autoReconnecting = false
        resetStreaming()
        markdownParseJobs.values.forEach(Job::cancel)
        markdownParseJobs.clear()
        markdownParseContents.clear()
        state.value.card?.let { card ->
            val snap = transcriptRepository.state.value
            ChatTranscriptPreviewCache.put(
                cardId,
                card.profileId,
                card.modelId,
                snap,
            )
            // Disk copy for force-stop / process death (non-authoritative).
            val profileId = card.profileId
            val modelId = card.modelId
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    ServiceLocator.diskTranscriptPreview.put(cardId, profileId, modelId, snap)
                } finally {
                    cancel()
                }
            }
        }
        deletePendingAttachments(state.value.attachments)
        holdOrDisconnect()
        super.onCleared()
    }

    private fun deletePendingAttachments(attachments: List<ChatAttachment>) {
        if (attachments.isEmpty()) return
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            try {
                attachments.forEach { ServiceLocator.chatAttachments.remove(it) }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    /**
     * Leaving the screen hands a healthy session to [ChatSessionRegistry] so re-open
     * is warm (and in-flight turns keep running). Unhealthy sessions tear down.
     */
    private fun holdOrDisconnect() {
        val rpc = client
        val currentThread = threadId
        val currentEndpoint = endpoint
        handoffInProgress = true
        if (shouldKeepSessionInBackground(
                state = state.value,
                hasServerResponseInFlight = serverResponsesInFlight.isNotEmpty(),
            ) &&
            rpc != null && currentThread != null && currentEndpoint != null &&
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
                pendingApprovals = pendingApprovalQueue.snapshot(),
                pendingUserInput = state.value.userInputRequest,
                pendingUserInputs = pendingUserInputQueue.snapshot(),
                queued = state.value.queued,
                broker = credentialBroker,
                extensionSession = extensionSession,
                onDrained = { held ->
                    val current = state.value
                    held.activeTurnId = activeTurnId
                    held.isBusy = hasActiveSessionWork(current)
                    held.pendingApproval = current.approval
                    held.pendingApprovals.clear()
                    held.pendingApprovals.addAll(pendingApprovalQueue.snapshot())
                    held.pendingUserInput = current.userInputRequest
                    held.pendingUserInputs.clear()
                    held.pendingUserInputs.addAll(pendingUserInputQueue.snapshot())
                    held.queued = current.queued
                    client = null
                    handoffInProgress = false
                },
            )
        ) {
            // The collector drains to the handoff marker and exits naturally. Direct
            // cancellation could lose a Channel element already dequeued for delivery.
            eventJob = null
            eventCollectorScope = null
            endpoint = null
            credentialBroker = null
            extensionSession = null
            ActiveCodexConnections.unregister(cardId, rpc)
            return
        }
        handoffInProgress = false
        disconnectServer()
    }

    /**
     * Idempotent, never blocks the caller. Bridge teardown (Thread.sleep/waitFor in the
     * runtime) runs on a short-lived IO scope that cancels itself once finished, so
     * onCleared() stays off the main thread without leaking coroutines.
     */
    private fun disconnectServer() {
        handoffInProgress = false
        respondingApproval = null
        respondingUserInput = null
        serverResponsesInFlight.clear()
        pendingApprovalQueue.clear()
        pendingUserInputQueue.clear()
        stopEventCollection()
        unbindHostWorkspaceSession()
        ChatSessionRegistry.stopAndRemove(cardId)
        val rpc = client
        client = null
        val broker = credentialBroker
        credentialBroker = null
        val extensions = extensionSession
        extensionSession = null
        val endpoint = endpoint
        this.endpoint = null
        if (rpc != null) {
            ActiveCodexConnections.unregister(cardId, rpc)
        }
        rpc?.close()
        broker?.close()
        extensions?.close()
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

    private fun startEventCollection(rpc: CodexRpcClient) {
        stopEventCollection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        eventCollectorScope = scope
        eventJob = scope.launch {
            try {
                rpc.eventsUntilHandoff().collect(::handleInbound)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (client === rpc) {
                    commitStreaming()
                    disconnectServer()
                    beginReconnect("Codex 事件处理失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun stopEventCollection() {
        eventJob?.cancel()
        eventJob = null
        eventCollectorScope?.cancel()
        eventCollectorScope = null
    }

    companion object {
        private const val MAX_COMPOSER_LENGTH = 32_000
        private const val STREAM_FLUSH_INTERVAL_MS = 64L
        private const val INITIAL_MARKDOWN_PREPARSE_COUNT = 6
        private const val INITIAL_HISTORY_TURNS = 50
        private const val MAX_RECONNECT_ATTEMPTS = 8
        private val HOST_WORKSPACE_INSTRUCTIONS = """
            AgentDeck Host Workspace (L1) is available for this conversation.

            IMPORTANT: The user's real Android folder is NOT mounted into this Linux cwd.
            `/root/projects/...` is a private Runtime tree. The real folder is reached only via agentdeck-host.

            List/read/write the REAL folder:
              agentdeck-host workspace.list --path .
              agentdeck-host workspace.read --path RELATIVE
              agentdeck-host workspace.write --path RELATIVE --content TEXT
              agentdeck-host workspace.mkdir --path RELATIVE
              agentdeck-host workspace.remove --path RELATIVE
              agentdeck-host workspace.stat --path RELATIVE

            To edit with normal shell tools inside Runtime, pull then push:
              agentdeck-host workspace.pull --path RELATIVE
              # file appears at /root/projects/host-mirror/RELATIVE
              # edit it, then:
              agentdeck-host workspace.push --path RELATIVE

            Writes/deletes/push require user approval. Never invent /sdcard paths.
        """.trimIndent()
        private val APPROVAL_DECISIONS = setOf("accept", "acceptForSession", "decline", "cancel")

        fun factory(cardId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cardId) as T
            }
    }
}

internal fun attachmentFailureMessage(message: String?): String {
    val detail = message.orEmpty()
    return when {
        "附件不能超过" in detail -> "附件不能超过 20 MiB"
        "不支持此文件类型" in detail ->
            "不支持此文件类型；可添加 PNG、JPEG、文本/代码、PDF、DOCX 或 XLSX"
        "当前模型明确不支持图片" in detail -> "当前模型不支持图片输入"
        else -> "文件无法解析；请确认文件未损坏、未加密且包含可读取内容"
    }
}

internal fun attachmentFailureSummary(failures: List<String>): String {
    val distinct = failures.distinct()
    val visible = distinct.take(2).joinToString("；")
    return if (failures.size == 1) visible else "${failures.size} 个文件未添加：$visible"
}

private fun ChatUiState.toTranscriptState(
    transcript: ChatTranscriptStoreState,
): ChatTranscriptUiState {
    return ChatTranscriptUiState(
        items = transcript.items,
        pages = transcript.pages,
        tailIds = transcript.tailIds,
        streamingItemId = transcript.streamingItemId,
        isConnecting = isConnecting,
        isReconnecting = isReconnecting,
        isStreaming = isStreaming,
        error = error,
        hasOlderHistory = transcript.hasOlderHistory,
        isLoadingOlder = transcript.isLoadingOlder,
        refetchingPageKeys = transcript.refetchingPageKeys,
    )
}

private data class MarkdownWindow(
    val items: List<ChatItem>,
    val visibleIds: Set<String>,
    val streamingItemId: String?,
)

/**
 * Warm keep-alive: any healthy connected Codex session stays held while the App
 * process lives, so re-entering chat is fast. Only in-flight host-write approvals
 * or unresolved server responses force a teardown (unsafe to background).
 * System memory pressure still calls [ChatSessionRegistry.releaseAllIdleSessions].
 */
internal fun shouldKeepSessionInBackground(
    state: ChatUiState,
    hasServerResponseInFlight: Boolean = false,
): Boolean = state.isConnected &&
    !hasServerResponseInFlight &&
    state.hostWriteApproval == null

internal fun hasActiveSessionWork(state: ChatUiState): Boolean =
    state.isStreaming ||
        state.approval != null ||
        state.userInputRequest != null

internal fun reconnectDelayMs(attempt: Int): Long {
    val schedule = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    return schedule[(attempt - 1).coerceIn(0, schedule.lastIndex)]
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM tokens".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk tokens".format(tokens / 1_000.0)
    else -> "$tokens tokens"
}

private fun ChatApproval.blockedActionLabel(): String = when (kind) {
    ApprovalKind.COMMAND -> "命令执行"
    ApprovalKind.FILE_CHANGE -> "文件修改"
    ApprovalKind.PERMISSIONS -> "额外权限请求"
    ApprovalKind.MCP_TOOL -> "MCP 工具调用"
}

private fun CodexRpcException.isMissingThread(): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("not found") || normalized.contains("no rollout found")
}

internal fun CodexRpcException.hasActiveWriter(): Boolean =
    message.lowercase().contains("active writer")

private data class ConnectPrep(
    val runtime: ManagedProviderRuntime?,
    val endpoint: CodexBridgeEndpoint,
    val models: List<CodexModelOption>,
    val providerLabel: String?,
    val extensionPlan: ExtensionSessionPlan,
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

internal fun supportsImageInput(
    models: List<CodexModelOption>,
    currentModel: String?,
): Boolean = models.firstOrNull { it.id == currentModel }
    ?.inputModalities
    ?.contains("image")
    ?: true

private const val MAX_MODEL_LIST_PAGES = 5
private const val MODEL_LIST_TIMEOUT_MILLIS = 5_000L
