package com.agentdeck.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.ProviderProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ModelsViewModel : ViewModel() {
    private val repo = ServiceLocator.profiles

    val profiles: StateFlow<List<ProviderProfile>> = repo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun save(state: EditorState) {
        repo.saveProfile(
            existingId = state.id,
            name = state.name,
            type = state.type,
            baseUrl = state.baseUrl,
            defaultModel = state.model,
            apiKey = state.apiKey.ifBlank { null },
        )
    }

    suspend fun delete(id: String) {
        repo.deleteProfile(id)
    }
}
