package com.agentdeck.app.domain.settings

import com.agentdeck.app.domain.launch.CliAdapterRegistry
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationModePolicyTest {
    private fun card(recipeId: String) = AgentCard(
        id = "card_test1",
        name = "t",
        icon = "x",
        recipeId = recipeId,
        templateId = "tpl",
        profileId = null,
        termuxSessionName = "s",
        workspaceNamespace = PathNamespace.UBUNTU,
        workspacePath = "/root/projects/default",
    )

    @Test
    fun `light adapters only recipe_light`() {
        val all = CliAdapterRegistry.SESSION_RECIPE_ORDER.mapNotNull {
            CliAdapterRegistry.default.forRecipe(it)?.descriptor
        }
        val light = ConversationModePolicy.adaptersForMode(ConversationMode.LIGHT, all)
        assertEquals(listOf("recipe_light"), light.map { it.recipeId })
        assertFalse(ConversationModePolicy.requiresEmbeddedRuntime(ConversationMode.LIGHT))
        assertFalse(ConversationModePolicy.showSettingsRuntimes(ConversationMode.LIGHT))
    }

    @Test
    fun `dev adapters exclude light`() {
        val all = CliAdapterRegistry.SESSION_RECIPE_ORDER.mapNotNull {
            CliAdapterRegistry.default.forRecipe(it)?.descriptor
        }
        val dev = ConversationModePolicy.adaptersForMode(ConversationMode.DEV, all)
        assertTrue(dev.none { it.recipeId == "recipe_light" })
        assertTrue(dev.any { it.recipeId == "recipe_codex" })
        assertTrue(ConversationModePolicy.requiresEmbeddedRuntime(ConversationMode.DEV))
    }

    @Test
    fun `card mode matching is exclusive`() {
        val light = card("recipe_light")
        val codex = card("recipe_codex")
        assertTrue(ConversationModePolicy.cardMatchesMode(light, ConversationMode.LIGHT))
        assertFalse(ConversationModePolicy.cardMatchesMode(codex, ConversationMode.LIGHT))
        assertTrue(ConversationModePolicy.cardMatchesMode(codex, ConversationMode.DEV))
        assertFalse(ConversationModePolicy.cardMatchesMode(light, ConversationMode.DEV))
    }
}
