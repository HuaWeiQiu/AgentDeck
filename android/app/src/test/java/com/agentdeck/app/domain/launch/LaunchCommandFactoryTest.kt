package com.agentdeck.app.domain.launch

import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.PathNamespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchCommandFactoryTest {
    @Test
    fun `successful command opens Termux in foreground`() {
        var opened = false

        val result = foregroundLaunchResult(Result.success(Unit)) {
            opened = true
            true
        }

        assertTrue(opened)
        assertSame(LaunchResult.Success, result)
    }

    @Test
    fun `foreground launch reports when Termux cannot be opened`() {
        val result = foregroundLaunchResult(Result.success(Unit)) { false }

        assertEquals(
            "命令已发送，但无法打开 Termux，请从桌面手动打开",
            (result as LaunchResult.Failed).message,
        )
    }

    @Test
    fun `failed command does not try to open Termux`() {
        var openAttempts = 0

        val result = foregroundLaunchResult(Result.failure(IllegalStateException("命令失败"))) {
            openAttempts += 1
            true
        }

        assertEquals(0, openAttempts)
        assertEquals("命令失败", (result as LaunchResult.Failed).message)
    }

    @Test
    fun `codex values remain separate arguments`() {
        val workspace = "/root/project with space/'quoted'; \$(touch nope)"
        val card = card(
            workspacePath = workspace,
            innerArgs = listOf("resume", "thread with space", "'; echo nope"),
        )

        val command = LaunchCommandFactory.create(card).getOrThrow()

        assertEquals(
            "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-ubuntu.sh",
            command.executable,
        )
        assertEquals(
            listOf(
                "--distro",
                "ubuntu",
                "--cwd",
                workspace,
                "--bin",
                "codex",
                "--",
                "resume",
                "thread with space",
                "'; echo nope",
            ),
            command.args,
        )
        assertFalse(command.background)
        assertTrue(command.reuseExistingSession)
    }

    @Test
    fun `disabled card is rejected`() {
        val result = LaunchCommandFactory.create(card(enabled = false))

        assertTrue(result.isFailure)
        assertEquals("卡片已停用", result.exceptionOrNull()?.message)
    }

    @Test
    fun `codex template rejects Termux workspace namespace`() {
        val result = LaunchCommandFactory.create(
            card(workspaceNamespace = PathNamespace.TERMUX),
        )

        assertTrue(result.isFailure)
        assertEquals("Codex Ubuntu 模板需要 Ubuntu 工作目录", result.exceptionOrNull()?.message)
    }

    @Test
    fun `claude template uses fixed executable directly`() {
        val command = LaunchCommandFactory.create(
            card(
                recipeId = "recipe_claude_code",
                templateId = "tpl_claude_termux",
                workspaceNamespace = PathNamespace.TERMUX,
                workspacePath = "/data/data/com.termux/files/home/project with space",
                innerBin = "claude",
                innerArgs = listOf("--continue"),
            ),
        ).getOrThrow()

        assertEquals("/data/data/com.termux/files/usr/bin/claude", command.executable)
        assertEquals(listOf("--continue"), command.args)
        assertEquals(
            "/data/data/com.termux/files/home/project with space",
            command.workDir,
        )
    }

    @Test
    fun `unknown template is rejected`() {
        val result = LaunchCommandFactory.create(card(templateId = "custom_shell"))

        assertTrue(result.isFailure)
        assertEquals("不支持的启动模板: custom_shell", result.exceptionOrNull()?.message)
    }

    @Test
    fun `codex adapter rejects a distro not owned by its recipe`() {
        val result = LaunchCommandFactory.create(card(distro = "--help"))

        assertTrue(result.isFailure)
        assertEquals("Codex 配方只支持 ubuntu 发行版", result.exceptionOrNull()?.message)
    }

    @Test
    fun `recipe and template mismatch is rejected`() {
        val result = LaunchCommandFactory.create(
            card(templateId = "tpl_claude_termux"),
        )

        assertTrue(result.isFailure)
        assertEquals("卡片配方与启动模板不匹配", result.exceptionOrNull()?.message)
    }

    private fun card(
        recipeId: String = "recipe_codex",
        templateId: String = "tpl_codex_ubuntu",
        workspaceNamespace: PathNamespace = PathNamespace.UBUNTU,
        workspacePath: String = "/root/projects/default",
        innerBin: String = "codex",
        innerArgs: List<String> = emptyList(),
        enabled: Boolean = true,
        distro: String = "ubuntu",
    ) = AgentCard(
        id = "card_test",
        name = "Test",
        icon = "codex",
        recipeId = recipeId,
        templateId = templateId,
        profileId = null,
        termuxSessionName = "agentdeck-test",
        workspaceNamespace = workspaceNamespace,
        workspacePath = workspacePath,
        distro = distro,
        innerBin = innerBin,
        innerArgs = innerArgs,
        enabled = enabled,
    )
}
