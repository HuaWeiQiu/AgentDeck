package com.agentdeck.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.data.chat.CodexAccount
import com.agentdeck.app.data.chat.CodexAccountManager
import com.agentdeck.app.data.chat.CodexAccountProtocol
import com.agentdeck.app.data.chat.CodexDeviceLogin
import com.agentdeck.app.data.chat.CodexInbound
import com.agentdeck.app.data.provider.ProviderDiscoveryException
import com.agentdeck.app.data.secure.CredentialInvalidatedException
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.profiles.ProfileInputValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ProviderEditorDraft(
    val id: String?,
    val name: String,
    val adapterId: ProviderAdapterId,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val models: List<ProviderModel>,
    val hasStoredCredential: Boolean,
    val validated: Boolean,
    val status: ProviderConnectionStatus,
    val error: String? = null,
)

data class CodexAccountUiState(
    val account: CodexAccount? = null,
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val deviceLogin: CodexDeviceLogin? = null,
    val error: String? = null,
)

internal fun ProviderEditorDraft.selectAdapter(adapterId: ProviderAdapterId): ProviderEditorDraft {
    val defaultNames = setOf("Sub2API", "Responses 服务", "Chat Completions", "小红书 dots")
    val nextName = if (id == null && (name.isBlank() || name in defaultNames)) {
        when (adapterId) {
            ProviderAdapterId.SUB2API -> "Sub2API"
            ProviderAdapterId.OPENAI_CHAT_COMPLETIONS -> "Chat Completions"
            else -> "Responses 服务"
        }
    } else {
        name
    }
    val nextBase = if (id == null && adapterId == ProviderAdapterId.OPENAI_CHAT_COMPLETIONS &&
        (baseUrl.isBlank() || baseUrl.contains("sub2api", ignoreCase = true))
    ) {
        "https://note3-prev-api.askdiandian.com/v1"
    } else {
        baseUrl
    }
    val nextModel = if (id == null && adapterId == ProviderAdapterId.OPENAI_CHAT_COMPLETIONS &&
        model.isBlank()
    ) {
        "dots3-note-prev"
    } else {
        model
    }
    return copy(
        name = nextName,
        adapterId = adapterId,
        baseUrl = nextBase,
        model = nextModel,
        validated = false,
        models = emptyList(),
        error = null,
    )
}

class ModelsViewModel : ViewModel() {
    private val repo = ServiceLocator.profiles
    private val credentials = ServiceLocator.credentials
    private val discovery = ServiceLocator.modelDiscovery
    private val accountManager = CodexAccountManager(ServiceLocator.codexBridge)
    private val mutableAccountState = MutableStateFlow(CodexAccountUiState())
    private var accountJob: Job? = null
    @Volatile private var deviceSession: CodexAccountManager.DeviceLoginSession? = null

    val accountState: StateFlow<CodexAccountUiState> = mutableAccountState.asStateFlow()

    val profiles: StateFlow<List<ProviderProfile>> = repo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allModels: StateFlow<List<ProviderModel>> = repo.observeAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshAccount()
    }

    fun newDraft() = ProviderEditorDraft(
        id = null,
        name = "Sub2API",
        adapterId = ProviderAdapterId.SUB2API,
        baseUrl = "",
        apiKey = "",
        model = "",
        models = emptyList(),
        hasStoredCredential = false,
        validated = false,
        status = ProviderConnectionStatus.UNVERIFIED,
    )

    fun editDraft(profile: ProviderProfile): ProviderEditorDraft {
        val models = allModels.value.filter { it.providerId == profile.id }
        return ProviderEditorDraft(
            id = profile.id,
            name = profile.name,
            adapterId = profile.adapterId,
            baseUrl = profile.baseUrl,
            apiKey = "",
            model = profile.defaultModel,
            models = models,
            hasStoredCredential = profile.credentialRef?.let(credentials::contains) == true,
            validated = profile.connectionStatus == ProviderConnectionStatus.READY ||
                profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
            status = profile.connectionStatus,
        )
    }

    fun refreshAccount() {
        if (deviceSession != null) return
        runAccountOperation(loading = true) { accountManager.readAccount() }
    }

    fun loginWithApiKey(apiKey: String) {
        if (apiKey.isBlank() || deviceSession != null) return
        runAccountOperation { accountManager.loginWithApiKey(apiKey) }
    }

    fun logout() {
        if (deviceSession != null) return
        runAccountOperation { accountManager.logout() }
    }

    fun startChatGptLogin() {
        if (mutableAccountState.value.isWorking || deviceSession != null) return
        accountJob?.cancel()
        accountJob = viewModelScope.launch(Dispatchers.IO) {
            mutableAccountState.update {
                it.copy(isLoading = false, isWorking = true, error = null)
            }
            val session = accountManager.startDeviceLogin().getOrElse { error ->
                mutableAccountState.update {
                    it.copy(
                        isWorking = false,
                        deviceLogin = null,
                        error = error.message ?: "无法启动 ChatGPT 登录",
                    )
                }
                return@launch
            }
            deviceSession = session
            mutableAccountState.update {
                it.copy(isWorking = false, deviceLogin = session.login, error = null)
            }
            try {
                val terminalEvent = session.events.first { event ->
                    when (event) {
                        is CodexInbound.Disconnected -> true
                        is CodexInbound.Notification ->
                            event.method == "account/login/completed" &&
                                CodexAccountProtocol.parseLoginCompletion(
                                    event.params,
                                    session.login.loginId,
                                ) != null
                        is CodexInbound.ServerRequest,
                        is CodexInbound.Handoff,
                        -> false
                    }
                }
                when (terminalEvent) {
                    is CodexInbound.Disconnected -> throw IllegalStateException(terminalEvent.message)
                    is CodexInbound.Notification -> {
                        val completion = requireNotNull(
                            CodexAccountProtocol.parseLoginCompletion(
                                terminalEvent.params,
                                session.login.loginId,
                            ),
                        )
                        check(completion.success) { completion.error ?: "ChatGPT 登录未完成" }
                        val account = session.readAccount().account
                        mutableAccountState.value = CodexAccountUiState(
                            account = account,
                            isLoading = false,
                        )
                        ServiceLocator.setup.scan(force = true)
                    }
                    is CodexInbound.ServerRequest,
                    is CodexInbound.Handoff,
                    -> Unit
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableAccountState.update {
                    it.copy(
                        isWorking = false,
                        deviceLogin = null,
                        error = error.message ?: "ChatGPT 登录失败",
                    )
                }
            } finally {
                if (deviceSession === session) deviceSession = null
                session.close()
            }
        }
    }

    fun cancelChatGptLogin() {
        val session = deviceSession ?: return
        val waitingJob = accountJob
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { session.cancel() }
            waitingJob?.cancelAndJoin()
            if (deviceSession === session) deviceSession = null
            mutableAccountState.update {
                it.copy(isWorking = false, deviceLogin = null, error = null)
            }
        }
    }

    private fun runAccountOperation(
        loading: Boolean = false,
        operation: suspend () -> Result<com.agentdeck.app.data.chat.CodexAccountSnapshot>,
    ) {
        if (mutableAccountState.value.isWorking || deviceSession != null) return
        accountJob?.cancel()
        accountJob = viewModelScope.launch(Dispatchers.IO) {
            mutableAccountState.update {
                it.copy(
                    isLoading = loading,
                    isWorking = !loading,
                    deviceLogin = null,
                    error = null,
                )
            }
            operation().fold(
                onSuccess = { snapshot ->
                    mutableAccountState.value = CodexAccountUiState(
                        account = snapshot.account,
                        isLoading = false,
                    )
                    ServiceLocator.setup.scan(force = true)
                },
                onFailure = { error ->
                    mutableAccountState.update {
                        it.copy(
                            isLoading = false,
                            isWorking = false,
                            error = error.message ?: "无法读取 Codex 账号状态",
                        )
                    }
                },
            )
        }
    }

    suspend fun discover(draft: ProviderEditorDraft): ProviderEditorDraft {
        val endpoint = ProfileInputValidator.validateManaged(
            name = draft.name,
            baseUrl = draft.baseUrl,
            defaultModel = draft.model.ifBlank { "pending-model" },
        ).getOrElse { error ->
            return draft.copy(
                validated = false,
                status = ProviderConnectionStatus.INVALID_RESPONSE,
                error = error.message ?: "模型服务配置无效",
            )
        }
        val existing = draft.id?.let { id -> repo.getProfile(id) }
        val credentialRef = existing?.credentialRef
        val secret = when {
            draft.apiKey.isNotBlank() -> draft.apiKey.toByteArray(StandardCharsets.UTF_8)
            credentialRef != null -> try {
                credentials.load(credentialRef)
            } catch (error: CredentialInvalidatedException) {
                // Keystore 密钥失效：密文已自动清理，引导用户重新验证/导入而不是报裸错误
                return draft.copy(
                    hasStoredCredential = false,
                    validated = false,
                    status = ProviderConnectionStatus.CREDENTIAL_REJECTED,
                    error = "模型连接已失效，请重新验证或重新导入 API Key",
                )
            }
            else -> null
        } ?: return draft.copy(
            validated = false,
            status = ProviderConnectionStatus.CREDENTIAL_REJECTED,
            error = "请输入 API Key",
        )
        return try {
            val previewId = existing?.id ?: "preview_provider"
            val profile = ProviderProfile(
                id = previewId,
                name = draft.name.trim(),
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = endpoint.apiBaseUrl,
                defaultModel = draft.model.ifBlank { "pending-model" },
                adapterId = draft.adapterId,
                credentialRef = credentialRef,
            )
            val models = discovery.discover(profile, secret)
            val selected = draft.model.takeIf { model -> models.any { it.id == model } }
                ?: models.first().id
            draft.copy(
                baseUrl = endpoint.apiBaseUrl,
                model = selected,
                models = models,
                validated = true,
                status = ProviderConnectionStatus.READY,
                error = null,
            )
        } catch (error: ProviderDiscoveryException) {
            draft.copy(
                baseUrl = endpoint.apiBaseUrl,
                models = emptyList(),
                validated = error.status == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
                status = error.status,
                error = error.message,
            )
        } catch (error: Exception) {
            draft.copy(
                baseUrl = endpoint.apiBaseUrl,
                models = emptyList(),
                validated = false,
                status = ProviderConnectionStatus.NETWORK_ERROR,
                error = error.message ?: "无法获取模型列表",
            )
        } finally {
            secret.fill(0)
        }
    }

    suspend fun save(draft: ProviderEditorDraft): Result<ProviderProfile> = runCatching {
        require(draft.validated) { "请先验证服务并获取模型" }
        val endpoint = ProfileInputValidator.validateManaged(
            draft.name,
            draft.baseUrl,
            draft.model,
        ).getOrThrow()
        val existing = draft.id?.let { repo.getProfile(it) }
        require(draft.id == null || existing != null) { "要编辑的模型服务不存在" }
        val credentialRef = existing?.credentialRef
            ?: "cred_${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val replacement = draft.apiKey.takeIf(String::isNotBlank)
            ?.toByteArray(StandardCharsets.UTF_8)
        var previous: ByteArray? = null
        var replaced = false
        try {
            if (replacement != null) {
                previous = existing?.credentialRef?.let(credentials::load)
            }
            if (replacement == null) {
                require(credentials.contains(credentialRef)) {
                    "已保存的 API Key 不存在，请重新输入"
                }
            } else {
                credentials.save(credentialRef, replacement)
                replaced = true
            }
            val id = existing?.id
                ?: "prof_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val checkedAt = System.currentTimeMillis()
            val models = draft.models.map { model ->
                model.copy(providerId = id, discoveredAtEpochMs = checkedAt)
            }
            repo.saveProfileAndModels(
                existingId = existing?.id,
                name = draft.name,
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = endpoint.apiBaseUrl,
                defaultModel = draft.model,
                adapterId = draft.adapterId,
                credentialRef = credentialRef,
                connectionStatus = draft.status,
                lastCheckedAtEpochMs = checkedAt,
                models = models,
            )
        } catch (error: Exception) {
            if (replaced) {
                val rollback = previous
                runCatching {
                    if (rollback != null) {
                        credentials.save(credentialRef, rollback)
                    } else {
                        credentials.delete(credentialRef)
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        } finally {
            previous?.fill(0)
            replacement?.fill(0)
        }
    }.also { result ->
        if (result.isSuccess) ServiceLocator.setup.scan(force = true)
    }

    suspend fun delete(profile: ProviderProfile): Result<Unit> = runCatching {
        repo.deleteProfile(profile.id)
        profile.credentialRef?.let(credentials::delete)
        Unit
    }.also { result ->
        if (result.isSuccess) ServiceLocator.setup.scan(force = true)
    }
}
