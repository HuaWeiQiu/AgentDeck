package com.agentdeck.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.profiles.ProfileInputValidator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ModelsViewModel : ViewModel() {
    private val repo = ServiceLocator.profiles

    val profiles: StateFlow<List<ProviderProfile>> = repo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun save(state: EditorState): Result<ProviderProfile> = runCatching {
        ProfileInputValidator.validate(
            name = state.name,
            baseUrl = state.baseUrl,
            defaultModel = state.model,
        ).getOrThrow()
        repo.saveProfile(
            existingId = state.id,
            name = state.name,
            type = state.type,
            baseUrl = state.baseUrl,
            defaultModel = state.model,
        )
    }

    suspend fun delete(id: String): Result<Int> = runCatching {
        requireNotNull(repo.getProfile(id)) { "CLI 配置不存在" }
        repo.deleteProfile(id)
    }

}
