package com.agentdeck.app.ui.sessions

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.data.chat.ActiveCodexConnections
import com.agentdeck.app.data.chat.ChatSessionRegistry
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.cards.CardDraft
import com.agentdeck.app.domain.cards.CardEditor
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.launch.CliAdapterRegistry
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.RecipeSummary
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.settings.ExperienceLevel
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 会话列表 item 的纯数据 UI model，
 * 在 ViewModel 层完成 recipe/profile/model 反查，item composition 不再访问 ViewModel。
 */
data class SessionCardUi(
    val card: AgentCard,
    val recipeAvailable: Boolean,
    val cliDisplayName: String,
    val profile: ProviderProfile?,
    val modelDisplayName: String?,
    /** 用户自定义标题优先，null 时回退到卡片名。 */
    val displayTitle: String,
    val summary: String,
    val lastActiveLabel: String,
    val selectedExtensionIds: Set<String> = emptySet(),
)

/**
 * 搜索过滤（标题/副标题/模型名）+ 排序（置顶优先，其余按显示标题）。
 * 纯函数，便于 JVM 单测。
 */
internal fun filterAndSortSessions(
    items: List<SessionCardUi>,
    query: String,
): List<SessionCardUi> {
    val trimmed = query.trim()
    val filtered = if (trimmed.isEmpty()) {
        items
    } else {
        items.filter { item ->
            item.displayTitle.contains(trimmed, ignoreCase = true) ||
                item.card.name.contains(trimmed, ignoreCase = true) ||
                item.summary.contains(trimmed, ignoreCase = true) ||
                item.card.identity?.roleName?.contains(trimmed, ignoreCase = true) == true ||
                item.modelDisplayName?.contains(trimmed, ignoreCase = true) == true
        }
    }
    return filtered.sortedWith(
        compareByDescending<SessionCardUi> { it.card.pinned }
            .thenByDescending { it.card.lastActiveAtEpochMs }
            .thenBy { it.displayTitle.lowercase() },
    )
}

class SessionsViewModel : ViewModel() {
    private val cardsRepo = ServiceLocator.cards
    private val recipesRepo = ServiceLocator.recipes
    private val adapters = CliAdapterRegistry.default
    private val searchQueryInput = MutableStateFlow("")

    val recipes: List<RecipeSummary> = recipesRepo.loadRecipes().map { it.summary }
    val availableAdapters: List<CliAdapterDescriptor> = recipes
        .filter { it.available }
        .mapNotNull { adapters.forRecipe(it.id)?.descriptor }

    private val mutableCardsHydrated = MutableStateFlow(false)

    /**
     * False until Room emits the first cards snapshot. Prevents the empty-state
     * onboarding checklist from flashing on cold start when sessions already exist.
     */
    val cardsHydrated: StateFlow<Boolean> = mutableCardsHydrated.asStateFlow()

    val cards: StateFlow<List<AgentCard>> = cardsRepo.observeCards()
        .onEach { mutableCardsHydrated.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val setupState: StateFlow<SetupState> = ServiceLocator.setup.state
    val experienceLevel: StateFlow<ExperienceLevel> = ServiceLocator.experienceSettings.level
    val defaultPermissionLevel: StateFlow<CodexPermissionLevel> =
        ServiceLocator.experienceSettings.codexPermissionLevel

    val profiles: StateFlow<List<ProviderProfile>> = ServiceLocator.profiles.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val models: StateFlow<List<ProviderModel>> = ServiceLocator.profiles.observeAllModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val extensions: StateFlow<List<ManagedExtension>> = ServiceLocator.extensions.observeExtensions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val cardExtensionSelections: StateFlow<Map<String, Set<String>>> =
        ServiceLocator.extensions.observeCardSelections()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val selectableExtensions: StateFlow<List<ManagedExtension>> = extensions
        .map { values -> selectableExtensions(values, BuildConfig.EXTENSION_MAX_LEVEL) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cardItems: StateFlow<List<SessionCardUi>> =
        combine(cards, profiles, models, cardExtensionSelections) {
                cardList, profileList, modelList, extensionSelections ->
            cardList.map { card ->
                val profile = card.profileId?.let { profileId ->
                    profileList.firstOrNull { it.id == profileId }
                }
                val model = card.profileId?.let { profileId ->
                    card.modelId?.let { modelId ->
                        modelList.firstOrNull {
                            it.providerId == profileId && it.id == modelId
                        }
                    }
                }
                val displayTitle = card.customTitle ?: card.name
                val modelDisplayName = model?.displayName ?: card.modelId
                val runtimeName = card.profileId?.let {
                    listOfNotNull(profile?.name, modelDisplayName)
                        .joinToString(" · ")
                        .ifBlank { "模型服务不可用" }
                } ?: "当前 Codex 配置"
                SessionCardUi(
                    card = card,
                    recipeAvailable = isRecipeAvailable(card.recipeId),
                    cliDisplayName = adapterDisplayName(card.recipeId),
                    profile = profile,
                    modelDisplayName = modelDisplayName,
                    displayTitle = displayTitle,
                    summary = conversationSummary(displayTitle, adapterDisplayName(card.recipeId), runtimeName),
                    lastActiveLabel = formatLastActivity(card.lastActiveAtEpochMs),
                    selectedExtensionIds = extensionSelections[card.id].orEmpty(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val searchQuery: StateFlow<String> = searchQueryInput

    fun setSearchQuery(query: String) {
        searchQueryInput.value = query
    }

    /** 搜索过滤 + 排序（置顶优先）后的完整列表；界面自行按 archived 分组。 */
    val visibleItems: StateFlow<List<SessionCardUi>> =
        combine(cardItems, searchQueryInput) { items, query ->
            filterAndSortSessions(items, query)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun newDraft(): CardDraft? = availableAdapters.firstOrNull()?.let { descriptor ->
        CardDraft(
            id = null,
            name = descriptor.displayName,
            recipeId = descriptor.recipeId,
            profileId = null,
            modelId = null,
            permissionLevel = null,
            identity = null,
            workspacePath = descriptor.defaultWorkspacePath,
            enabled = true,
            selectedExtensionIds = emptySet(),
        )
    }

    fun editDraft(item: SessionCardUi): CardDraft = CardDraft(
        id = item.card.id,
        name = item.card.name,
        recipeId = item.card.recipeId,
        profileId = item.card.profileId,
        modelId = item.card.modelId,
        permissionLevel = item.card.permissionLevel,
        identity = item.card.identity,
        workspacePath = item.card.workspacePath,
        enabled = item.card.enabled,
        selectedExtensionIds = item.selectedExtensionIds,
    )

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
        if (existing != null) {
            require(ChatSessionRegistry.releaseIfIdle(existing.id)) {
                "该对话仍在后台运行，请等待任务结束后再编辑"
            }
        }
        val profile = draft.profileId?.let { profileId ->
            requireNotNull(ServiceLocator.profiles.getProfile(profileId)) { "模型服务不存在" }
        }
        if (profile != null) {
            if (adapter.descriptor.recipeId == "recipe_codex") {
                require(
                    profile.adapterId == ProviderAdapterId.SUB2API ||
                        profile.adapterId == ProviderAdapterId.OPENAI_RESPONSES,
                ) { "该模型服务不支持 Codex Responses" }
            }
            require(
                profile.connectionStatus == ProviderConnectionStatus.READY ||
                    profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
            ) { "模型服务尚未验证" }
            val credentialRef = requireNotNull(profile.credentialRef) { "模型服务缺少 API Key" }
            require(ServiceLocator.credentials.contains(credentialRef)) { "模型服务的 API Key 不存在" }
            val modelId = requireNotNull(draft.modelId?.takeIf(String::isNotBlank)) { "请选择模型" }
            val knownModels = ServiceLocator.profiles.getModels(profile.id)
            require(
                knownModels.any { it.id == modelId } ||
                    profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED &&
                    modelId == profile.defaultModel,
            ) { "所选模型不属于该模型服务，请重新获取模型列表" }
        }
        val id = "card_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val card = CardEditor.build(draft, existing, id, adapter, profile).getOrThrow()
        // CardEditor 从草稿重建卡片，自定义标题/置顶/归档等列表管理字段从旧卡片继承
        val persisted = card.copy(
            customTitle = existing?.customTitle,
            pinned = existing?.pinned ?: false,
            archived = existing?.archived ?: false,
            lastActiveAtEpochMs = existing?.lastActiveAtEpochMs ?: 0L,
        )
        ServiceLocator.extensions.saveCardWithSelections(
            persisted,
            draft.selectedExtensionIds,
        )
        persisted
    }

    suspend fun delete(cardId: String): Result<Unit> = runCatching {
        requireNotNull(cardsRepo.getCard(cardId)) { "卡片不存在" }
        require(ChatSessionRegistry.releaseIfIdle(cardId)) {
            "该对话仍在后台运行，请等待任务结束后再删除"
        }
        // threadId 关联清理由 CardRepository.deleteCard 级联处理
        cardsRepo.deleteCard(cardId)
    }

    suspend fun rename(cardId: String, title: String): Result<Unit> = runCatching {
        val trimmed = title.trim()
        cardsRepo.renameCard(cardId, trimmed)
        syncThreadName(cardId, trimmed)
    }

    suspend fun setPinned(cardId: String, pinned: Boolean): Result<Unit> = runCatching {
        cardsRepo.setPinned(cardId, pinned)
    }

    suspend fun setArchived(cardId: String, archived: Boolean): Result<Unit> = runCatching {
        cardsRepo.setArchived(cardId, archived)
        syncThreadArchived(cardId, archived)
    }

    /**
     * 尽力而为的协议同步：仅当该卡片当前有活跃的 Codex 连接（聊天界面已注册）
     * 且已关联 threadId 时才调用；失败只记录日志，不影响已完成的本地操作。
     * 没有活跃连接时直接跳过——绝不为同步拉起 runtime 或 app-server。
     */
    private fun syncThreadArchived(cardId: String, archived: Boolean) {
        syncRequest(cardId, if (archived) "thread/archive" else "thread/unarchive") { threadId ->
            JSONObject().put("threadId", threadId)
        }
    }

    private fun syncThreadName(cardId: String, name: String) {
        syncRequest(cardId, "thread/name/set") { threadId ->
            JSONObject().put("threadId", threadId).put("name", name)
        }
    }

    private fun syncRequest(
        cardId: String,
        method: String,
        params: (threadId: String) -> JSONObject,
    ) {
        val rpc = ActiveCodexConnections.clientFor(cardId) ?: return
        val threadId = ServiceLocator.conversationLinks.threadId(cardId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                rpc.request(method, params(threadId), timeoutMillis = SYNC_TIMEOUT_MILLIS)
            }.onFailure { error ->
                Log.w(TAG, "Codex 协议同步 $method 失败（本地状态已更新）", error)
            }
        }
    }

    companion object {
        private const val TAG = "SessionsViewModel"
        private const val SYNC_TIMEOUT_MILLIS = 10_000L
    }
}

internal fun selectableExtensions(
    extensions: List<ManagedExtension>,
    maxLevel: Int,
): List<ManagedExtension> = extensions.filter {
    it.enabled && it.status == ExtensionStatus.READY && it.requiredLevel.value <= maxLevel
}.sortedWith(compareBy<ManagedExtension>({ it.kind.ordinal }, { it.name.lowercase() }))

internal fun formatLastActivity(
    timestamp: Long,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    if (timestamp <= 0L) return "尚未开始"
    return SimpleDateFormat("yyyy/MM/dd HH:mm", locale).apply {
        this.timeZone = timeZone
    }.format(Date(timestamp))
}
