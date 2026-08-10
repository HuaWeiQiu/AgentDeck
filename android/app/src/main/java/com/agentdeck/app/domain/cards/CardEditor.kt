package com.agentdeck.app.domain.cards

import com.agentdeck.app.domain.launch.CliAdapter
import com.agentdeck.app.domain.chat.ConversationIdentityPolicy
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ConversationIdentity
import com.agentdeck.app.domain.model.ProviderProfile

data class CardDraft(
    val id: String?,
    val name: String,
    val recipeId: String,
    val profileId: String?,
    val modelId: String?,
    val permissionLevel: CodexPermissionLevel? = null,
    val identity: ConversationIdentity? = null,
    val workspacePath: String,
    val enabled: Boolean,
)

object CardEditor {
    fun build(
        draft: CardDraft,
        existing: AgentCard?,
        newId: String,
        adapter: CliAdapter,
        profile: ProviderProfile?,
    ): Result<AgentCard> = runCatching {
        require(
            (existing == null && draft.id == null) ||
                (existing != null && draft.id == existing.id),
        ) {
            "卡片 ID 不匹配"
        }
        require(draft.recipeId == adapter.descriptor.recipeId) { "CLI 配方不可用" }
        require(draft.profileId == profile?.id) { "CLI 配置不存在" }
        adapter.validateProfile(profile).getOrThrow()
        if (profile == null) {
            require(draft.modelId == null) { "当前 Codex 配置不能单独指定模型" }
        } else {
            require(!draft.modelId.isNullOrBlank() && draft.modelId.length <= 160) {
                "请选择有效模型"
            }
            require(draft.modelId.none { it.isISOControl() }) { "模型 ID 包含非法字符" }
        }

        val identity = ConversationIdentityPolicy.normalize(draft.identity)
        val id = existing?.id ?: newId
        require(id.matches(CARD_ID_PATTERN)) { "卡片 ID 无效" }
        val descriptor = adapter.descriptor
        val card = AgentCard(
            id = id,
            name = draft.name.trim(),
            icon = descriptor.defaultIcon,
            recipeId = descriptor.recipeId,
            templateId = descriptor.templateId,
            profileId = profile?.id,
            modelId = draft.modelId,
            permissionLevel = draft.permissionLevel,
            termuxSessionName = existing?.termuxSessionName ?: "agentdeck-${descriptor.defaultInnerBin}-${id.removePrefix("card_")}",
            workspaceNamespace = descriptor.defaultWorkspaceNamespace,
            workspacePath = draft.workspacePath.trim(),
            distro = "ubuntu",
            innerBin = descriptor.defaultInnerBin,
            innerArgs = existing?.innerArgs ?: emptyList(),
            enabled = draft.enabled,
            identity = identity,
        )
        adapter.validateCard(card).getOrThrow()
        card
    }

    private val CARD_ID_PATTERN = Regex("card_[A-Za-z0-9._-]{1,58}")
}
