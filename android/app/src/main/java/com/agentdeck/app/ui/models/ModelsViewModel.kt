package com.agentdeck.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.data.provider.ProviderDiscoveryException
import com.agentdeck.app.data.provider.ImportedCodexProvider
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.profiles.ProfileInputValidator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

class ModelsViewModel : ViewModel() {
    private val repo = ServiceLocator.profiles
    private val credentials = ServiceLocator.credentials
    private val discovery = ServiceLocator.modelDiscovery

    val profiles: StateFlow<List<ProviderProfile>> = repo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allModels: StateFlow<List<ProviderModel>> = repo.observeAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    suspend fun importCurrentCodexProvider(): Result<ImportedCodexProvider> =
        ServiceLocator.existingCodexProviderImporter.importCurrent()

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
            credentialRef != null -> credentials.load(credentialRef)
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
    }

    suspend fun delete(profile: ProviderProfile): Result<Unit> = runCatching {
        repo.deleteProfile(profile.id)
        profile.credentialRef?.let(credentials::delete)
    }
}
