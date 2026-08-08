package com.agentdeck.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.cards.CardDraft
import com.agentdeck.app.domain.cards.CardEditor
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.launch.CliAdapterRegistry
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.RecipeSummary
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

class SessionsViewModel : ViewModel() {
    private val cardsRepo = ServiceLocator.cards
    private val profilesRepo = ServiceLocator.profiles
    private val recipesRepo = ServiceLocator.recipes
    private val adapters = CliAdapterRegistry.default

    val recipes: List<RecipeSummary> = recipesRepo.loadRecipes().map { it.summary }
    val availableAdapters: List<CliAdapterDescriptor> = recipes
        .filter { it.available }
        .mapNotNull { adapters.forRecipe(it.id)?.descriptor }

    val cards: StateFlow<List<AgentCard>> = cardsRepo.observeCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<List<ProviderProfile>> = profilesRepo.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val setupState: StateFlow<SetupState> = ServiceLocator.setup.state

    fun newDraft(): CardDraft? = availableAdapters.firstOrNull()?.let { descriptor ->
        CardDraft(
            id = null,
            name = descriptor.displayName,
            recipeId = descriptor.recipeId,
            profileId = null,
            workspacePath = descriptor.defaultWorkspacePath,
            enabled = true,
        )
    }

    fun editDraft(card: AgentCard): CardDraft = CardDraft(
        id = card.id,
        name = card.name,
        recipeId = card.recipeId,
        profileId = card.profileId,
        workspacePath = card.workspacePath,
        enabled = card.enabled,
    )

    fun compatibleProfiles(recipeId: String, candidates: List<ProviderProfile>): List<ProviderProfile> {
        val providerType = adapters.forRecipe(recipeId)?.descriptor?.providerType ?: return emptyList()
        return candidates.filter { it.type == providerType }
    }

    fun isRecipeAvailable(recipeId: String): Boolean =
        recipes.firstOrNull { it.id == recipeId }?.available == true

    fun adapterDisplayName(recipeId: String): String =
        adapters.forRecipe(recipeId)?.descriptor?.displayName
            ?: recipes.firstOrNull { it.id == recipeId }?.name
            ?: "CLI"

    suspend fun save(draft: CardDraft): Result<AgentCard> = runCatching {
        require(isRecipeAvailable(draft.recipeId)) { "该 CLI 配方尚未开放" }
        val adapter = requireNotNull(adapters.forRecipe(draft.recipeId)) { "缺少 CLI adapter" }
        val existing = draft.id?.let { cardsRepo.getCard(it) }
        require(draft.id == null || existing != null) { "要编辑的卡片不存在" }
        val profile = draft.profileId?.let { profileId ->
            profilesRepo.getProfile(profileId) ?: error("选择的 CLI 配置已不存在")
        }
        val id = "card_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val card = CardEditor.build(draft, existing, id, adapter, profile).getOrThrow()
        cardsRepo.saveCard(card)
        card
    }

    suspend fun delete(cardId: String): Result<Unit> = runCatching {
        requireNotNull(cardsRepo.getCard(cardId)) { "卡片不存在" }
        cardsRepo.deleteCard(cardId)
        ServiceLocator.conversationLinks.clearThreadId(cardId)
    }
}
