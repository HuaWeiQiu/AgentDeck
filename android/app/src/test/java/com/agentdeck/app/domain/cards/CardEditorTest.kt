package com.agentdeck.app.domain.cards

import com.agentdeck.app.domain.launch.CodexUbuntuAdapter
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardEditorTest {
    @Test
    fun `new codex card uses adapter owned launch fields`() {
        val profile = profile(ProviderType.OPENAI_COMPATIBLE)
        val draft = CardDraft(
            id = null,
            name = "  Project Codex  ",
            recipeId = "recipe_codex",
            profileId = profile.id,
            workspacePath = "  /root/projects/demo  ",
            enabled = true,
        )

        val card = CardEditor.build(
            draft = draft,
            existing = null,
            newId = "card_1234abcd",
            adapter = CodexUbuntuAdapter,
            profile = profile,
        ).getOrThrow()

        assertEquals("Project Codex", card.name)
        assertEquals("tpl_codex_ubuntu", card.templateId)
        assertEquals(PathNamespace.UBUNTU, card.workspaceNamespace)
        assertEquals("/root/projects/demo", card.workspacePath)
        assertEquals("agentdeck-codex-1234abcd", card.termuxSessionName)
        assertEquals("codex", card.innerBin)
    }

    @Test
    fun `editing preserves session and argv while allowing disabled state`() {
        val existing = card(innerArgs = listOf("resume", "thread-1"))
        val draft = CardDraft(
            id = existing.id,
            name = "Renamed",
            recipeId = existing.recipeId,
            profileId = null,
            workspacePath = "/root/next",
            enabled = false,
        )

        val updated = CardEditor.build(
            draft,
            existing,
            "card_unused00",
            CodexUbuntuAdapter,
            null,
        ).getOrThrow()

        assertEquals(existing.termuxSessionName, updated.termuxSessionName)
        assertEquals(existing.innerArgs, updated.innerArgs)
        assertFalse(updated.enabled)
    }

    @Test
    fun `profile type and missing profile fail closed`() {
        val incompatible = profile(ProviderType.ANTHROPIC)
        val draft = CardDraft(
            id = null,
            name = "Codex",
            recipeId = "recipe_codex",
            profileId = incompatible.id,
            workspacePath = "/root/projects/default",
            enabled = true,
        )

        val wrongType = CardEditor.build(
            draft,
            null,
            "card_1234abcd",
            CodexUbuntuAdapter,
            incompatible,
        )
        val missing = CardEditor.build(
            draft,
            null,
            "card_1234abcd",
            CodexUbuntuAdapter,
            null,
        )

        assertTrue(wrongType.isFailure)
        assertTrue(missing.isFailure)
    }

    private fun card(innerArgs: List<String> = emptyList()) = AgentCard(
        id = "card_codex_default",
        name = "Codex",
        icon = "codex",
        recipeId = "recipe_codex",
        templateId = "tpl_codex_ubuntu",
        profileId = null,
        termuxSessionName = "agentdeck-codex-default",
        workspaceNamespace = PathNamespace.UBUNTU,
        workspacePath = "/root/projects/default",
        distro = "ubuntu",
        innerBin = "codex",
        innerArgs = innerArgs,
    )

    private fun profile(type: ProviderType) = ProviderProfile(
        id = "profile_test",
        name = "Test",
        type = type,
        baseUrl = "https://api.example.com/v1",
        defaultModel = "model",
    )
}
