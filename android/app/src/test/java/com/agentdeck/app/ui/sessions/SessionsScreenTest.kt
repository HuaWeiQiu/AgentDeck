package com.agentdeck.app.ui.sessions

import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.domain.setup.readyReport
import com.agentdeck.app.ui.permissions.permissionSelectionLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsScreenTest {
    @Test
    fun `existing Codex authentication opens conversation`() {
        assertTrue(canStartConversation(SetupState(readyReport()), null))
    }

    @Test
    fun `missing authentication returns to setup without managed provider`() {
        assertFalse(canStartConversation(SetupState(reportWithoutAuthentication()), null))
    }

    @Test
    fun `verified managed provider can open without global authentication`() {
        val profile = readyProfile()

        assertTrue(
            canStartConversation(
                SetupState(reportWithoutAuthentication()),
                profile,
            ),
        )
        assertFalse(
            canStartConversation(
                SetupState(reportWithoutAuthentication()),
                profile.copy(credentialRef = null),
            ),
        )
    }

    @Test
    fun `setup banner hides when a managed conversation is ready`() {
        val setup = SetupState(reportWithoutAuthentication())
        val card = AgentCard(
            id = "card",
            name = "Codex",
            icon = "codex",
            recipeId = "recipe_codex",
            templateId = "template",
            profileId = "provider",
            modelId = "model",
            termuxSessionName = "session",
            workspaceNamespace = PathNamespace.UBUNTU,
            workspacePath = "/root/project",
            distro = "ubuntu",
            innerBin = "codex",
        )

        assertFalse(shouldShowSetupBanner(setup, listOf(card), listOf(readyProfile())))
        assertTrue(shouldShowSetupBanner(setup, listOf(card.copy(profileId = null)), listOf(readyProfile())))
    }

    @Test
    fun `conversation summary avoids repeating the card title`() {
        assertEquals(
            "当前 Codex 配置",
            conversationSummary("Codex", "Codex", "当前 Codex 配置"),
        )
        assertEquals(
            "Codex · DeepSeek · model",
            conversationSummary("客户项目", "Codex", "DeepSeek · model"),
        )
    }

    @Test
    fun `permission selection distinguishes inherited and overridden values`() {
        assertEquals(
            "使用默认 · 推荐",
            permissionSelectionLabel(null, CodexPermissionLevel.ASK_FIRST),
        )
        assertEquals(
            "只读",
            permissionSelectionLabel(
                CodexPermissionLevel.READ_ONLY,
                CodexPermissionLevel.FULL_ACCESS,
            ),
        )
    }

    private fun reportWithoutAuthentication(): EnvironmentReport = EnvironmentReport(
        readyReport().checks.map { check ->
            if (check.id == "codex_authenticated") {
                check.copy(status = EnvironmentCheckStatus.ACTION_REQUIRED)
            } else {
                check
            }
        },
    )

    private fun readyProfile() = ProviderProfile(
        id = "provider",
        name = "Provider",
        type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://example.com/v1",
        defaultModel = "model",
        credentialRef = "credential",
        connectionStatus = ProviderConnectionStatus.READY,
    )
}
