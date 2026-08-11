package com.agentdeck.app.ui.extensions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.extensions.ExtensionAuthType
import com.agentdeck.app.domain.extensions.ExtensionPolicy
import com.agentdeck.app.domain.extensions.ExtensionTool
import com.agentdeck.app.domain.extensions.ManagedExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsViewModel : ViewModel() {
    private val repository = ServiceLocator.extensions
    private val extensionPolicy = ExtensionPolicy(BuildConfig.EXTENSION_MAX_LEVEL)
    private val mutableLoaded = MutableStateFlow(false)
    private val mutableLoadError = MutableStateFlow<String?>(null)
    private val mutableBusy = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)
    private val reloadRequests = MutableStateFlow(0)

    val extensions: StateFlow<List<ManagedExtension>> = reloadRequests
        .flatMapLatest {
            mutableLoaded.value = false
            repository.observeExtensions()
                .onEach {
                    mutableLoaded.value = true
                    mutableLoadError.value = null
                }
                .catch { error ->
                    mutableLoaded.value = true
                    mutableLoadError.value = error.message ?: "扩展数据加载失败"
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()
    val loadError: StateFlow<String?> = mutableLoadError.asStateFlow()
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val supportsLocalMcp: Boolean = BuildConfig.EXTENSION_MAX_LEVEL >= 3

    fun clearError() {
        mutableError.value = null
    }

    fun retryLoad() {
        reloadRequests.value += 1
    }

    fun importSkill(uri: Uri, onResult: (Result<ManagedExtension>) -> Unit = {}) {
        runOperation(onResult) {
            val resolver = ServiceLocator.appContext.contentResolver
            resolver.openInputStream(uri)?.use { repository.importSkill(it) }
                ?: error("无法读取所选 SKILL.md")
        }
    }

    fun discoverRemote(
        existingId: String?,
        url: String,
        authType: ExtensionAuthType,
        bearerToken: String,
        onResult: (Result<List<ExtensionTool>>) -> Unit,
    ) {
        runOperation(onResult) {
            val bearer = bearerForDiscovery(existingId, url, authType, bearerToken)
            try {
                repository.discoverRemote(url, bearer)
            } finally {
                bearer?.fill(0)
            }
        }
    }

    fun saveRemote(
        existingId: String?,
        name: String,
        description: String,
        url: String,
        authType: ExtensionAuthType,
        bearerToken: String,
        discoveredTools: List<ExtensionTool>,
        onResult: (Result<ManagedExtension>) -> Unit = {},
    ) {
        runOperation(onResult) {
            val secret = bearerToken.trim().takeIf(String::isNotEmpty)
                ?.toByteArray(StandardCharsets.UTF_8)
            try {
                repository.saveRemoteMcp(
                    existingId = existingId,
                    name = name,
                    description = description,
                    url = url,
                    authType = authType,
                    bearerToken = secret,
                    discoveredTools = discoveredTools,
                )
            } finally {
                secret?.fill(0)
            }
        }
    }

    fun saveLocal(
        existingId: String? = null,
        name: String,
        description: String,
        command: String,
        args: List<String>,
        onResult: (Result<ManagedExtension>) -> Unit = {},
    ) {
        runOperation(onResult) {
            repository.saveLocalMcp(
                existingId = existingId,
                name = name,
                description = description,
                command = command,
                args = args,
            )
        }
    }

    fun setEnabled(id: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit = {}) {
        runOperation(onResult) { repository.setEnabled(id, enabled) }
    }

    fun setToolEnabled(
        extensionId: String,
        toolName: String,
        enabled: Boolean,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        runOperation(onResult) { repository.setToolEnabled(extensionId, toolName, enabled) }
    }

    fun delete(id: String, onResult: (Result<Unit>) -> Unit = {}) {
        runOperation(onResult) { repository.delete(id) }
    }

    private suspend fun bearerForDiscovery(
        existingId: String?,
        url: String,
        authType: ExtensionAuthType,
        bearerToken: String,
    ): ByteArray? {
        if (authType == ExtensionAuthType.NONE) return null
        bearerToken.trim().takeIf(String::isNotEmpty)?.let {
            return it.toByteArray(StandardCharsets.UTF_8)
        }
        val existing = if (existingId == null) null else repository.getById(existingId)
        val normalizedUrl = extensionPolicy.validateRemoteUrl(url).toString()
        require(
            existing?.mcp?.authType == ExtensionAuthType.BEARER &&
                existing.mcp.url == normalizedUrl,
        ) { "地址或鉴权方式已变化，请重新输入 Bearer Token" }
        val credentialRef = existing?.mcp?.credentialRef
            ?: error("请输入 Bearer Token")
        return ServiceLocator.extensionCredentials.load(credentialRef)
            ?: error("已保存的 Bearer Token 不存在")
    }

    private fun <T> runOperation(
        onResult: (Result<T>) -> Unit,
        block: suspend () -> T,
    ) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            val result = try {
                runCatching { withContext(Dispatchers.IO) { block() } }
            } finally {
                mutableBusy.value = false
            }
            result.exceptionOrNull()?.let { error ->
                mutableError.value = error.message ?: "扩展操作失败"
            }
            onResult(result)
        }
    }
}
