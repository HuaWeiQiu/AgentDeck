package com.agentdeck.app.domain.settings

import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.launch.CliAdapterRegistry
import com.agentdeck.app.domain.model.AgentCard

/**
 * Single place for Secure 轻聊 / 开发 surface rules.
 * UI should call this instead of scattering recipe ifs.
 *
 * See docs/plans/conversation-modes.md.
 */
object ConversationModePolicy {

    fun sessionRecipes(mode: ConversationMode): List<String> = when (mode) {
        ConversationMode.LIGHT -> listOf("recipe_light")
        ConversationMode.DEV -> listOf(
            "recipe_codex",
            "recipe_pi",
            "recipe_deepseek_harness",
        )
    }

    fun adaptersForMode(
        mode: ConversationMode,
        all: List<CliAdapterDescriptor>,
    ): List<CliAdapterDescriptor> {
        val allowed = sessionRecipes(mode).toSet()
        return all.filter { it.recipeId in allowed }
            .sortedBy { sessionRecipes(mode).indexOf(it.recipeId) }
    }

    fun cardMatchesMode(card: AgentCard, mode: ConversationMode): Boolean =
        when (mode) {
            ConversationMode.LIGHT -> CliAdapterRegistry.usesLightChat(card.recipeId)
            ConversationMode.DEV -> CliAdapterRegistry.isDevMode(card.recipeId)
        }

    /** Empty-state / create: light only needs a Chat Completions style gateway, not Codex runtime. */
    fun requiresEmbeddedRuntime(mode: ConversationMode): Boolean =
        mode == ConversationMode.DEV

    fun showSettingsRuntimes(mode: ConversationMode): Boolean =
        mode == ConversationMode.DEV

    fun showSettingsExtensions(mode: ConversationMode): Boolean =
        mode == ConversationMode.DEV

    fun showSettingsConversationAdvanced(mode: ConversationMode): Boolean =
        mode == ConversationMode.DEV

    fun showSettingsCodexConfig(mode: ConversationMode): Boolean =
        mode == ConversationMode.DEV

    fun listSectionTitle(mode: ConversationMode): String = when (mode) {
        ConversationMode.LIGHT -> "轻聊会话"
        ConversationMode.DEV -> "开发会话"
    }

    fun emptyChecklistPrimaryHint(mode: ConversationMode): String = when (mode) {
        ConversationMode.LIGHT -> "连上 Chat Completions 模型服务后即可轻聊，可写角色"
        ConversationMode.DEV -> "准备好运行环境并连上模型后，就可以用 Agent 干活"
    }
}
