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
    fun `search filters by title summary and model, pinned sorts first`() {
        val alpha = sessionItem("Alpha", model = "gpt-5", lastActive = 20)
        val beta = sessionItem("Beta", pinned = true, model = "claude", lastActive = 10)
        val archived = sessionItem("Alpha-old", archived = true, model = "gpt-5", lastActive = 30)

        // 置顶优先，其余按最近活动时间
        val all = filterAndSortSessions(listOf(alpha, beta, archived), "")
        assertEquals(listOf("Beta", "Alpha-old", "Alpha"), all.map { it.card.name })

        // 命中标题
        val byTitle = filterAndSortSessions(listOf(alpha, beta, archived), "alpha")
        assertEquals(listOf("Alpha-old", "Alpha"), byTitle.map { it.card.name })

        // 命中模型名
        val byModel = filterAndSortSessions(listOf(alpha, beta), "claude")
        assertEquals(listOf("Beta"), byModel.map { it.card.name })

        // 大小写不敏感
        assertEquals(1, filterAndSortSessions(listOf(alpha, beta), "GPT-5").size)

        // 无匹配
        assertTrue(filterAndSortSessions(listOf(alpha, beta), "不存在的词").isEmpty())
    }

    private fun sessionItem(
        name: String,
        pinned: Boolean = false,
        archived: Boolean = false,
        model: String? = null,
        lastActive: Long = 0L,
    ) = SessionCardUi(
        card = AgentCard(
            id = name,
            name = name,
            icon = "codex",
            recipeId = "recipe_codex",
            templateId = "template",
            profileId = null,
            termuxSessionName = "session",
            workspaceNamespace = PathNamespace.UBUNTU,
            workspacePath = "/root/project",
            pinned = pinned,
            archived = archived,
            lastActiveAtEpochMs = lastActive,
        ),
        recipeAvailable = true,
        cliDisplayName = "Codex",
        profile = null,
        modelDisplayName = model,
        displayTitle = name,
        summary = model ?: "当前 Codex 配置",
        lastActiveLabel = formatLastActivity(lastActive),
    )

    @Test
    fun `last activity displays an explicit date and time`() {
        assertEquals("尚未开始", formatLastActivity(0L))
        assertEquals(
            "2023/11/14 22:13",
            formatLastActivity(
                timestamp = 1_700_000_000_000L,
                locale = java.util.Locale.CHINA,
                timeZone = java.util.TimeZone.getTimeZone("UTC"),
            ),
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
