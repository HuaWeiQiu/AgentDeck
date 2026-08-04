package com.agentdeck.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.ProviderProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SessionsViewModel : ViewModel() {
    private val cardsRepo = ServiceLocator.cards
    private val profilesRepo = ServiceLocator.profiles
    private val launcher = ServiceLocator.launcher

    val cards: StateFlow<List<AgentCard>> = cardsRepo.observeCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<List<ProviderProfile>> = profilesRepo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun launch(cardId: String): LaunchResult = launcher.launch(cardId)

    suspend fun updateWorkspace(cardId: String, path: String) {
        val card = cardsRepo.getCard(cardId) ?: return
        cardsRepo.saveCard(card.copy(workspacePath = path))
    }
}
