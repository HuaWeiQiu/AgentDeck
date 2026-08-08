package com.agentdeck.app.data.chat

import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxCommandResult
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexBridgeLauncherTest {
    @Test
    fun `valid bootstrap becomes memory only endpoint`() {
        val token = "a".repeat(43)

        val endpoint = CodexBridgeLauncher.parseEndpoint(
            """{"port":48123,"token":"$token","pid":42}""",
            "abc123",
        )

        assertEquals(48_123, endpoint.port)
        assertEquals(token, endpoint.token)
        assertEquals("abc123", endpoint.instanceKey)
    }

    @Test
    fun `invalid bootstrap is rejected`() {
        val result = runCatching {
            CodexBridgeLauncher.parseEndpoint(
                """{"port":70000,"token":"short"}""",
                "abc123",
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `launcher gives each card a validated bridge instance key`() = runBlocking {
        var captured: TermuxCommand? = null
        val gateway = object : TermuxGateway {
            override fun isTermuxInstalled() = true
            override fun hasRunCommandPermission() = true
            override fun openTermux() = true
            override fun openTermuxInstallPage() = true
            override fun openTermuxAppSettings() = true
            override fun runCommand(command: TermuxCommand) = Result.success(Unit)
            override suspend fun runCommandForResult(
                command: TermuxCommand,
                timeoutMillis: Long,
            ): Result<TermuxCommandResult> {
                captured = command
                return Result.success(
                    TermuxCommandResult(
                        stdout = """{"port":48123,"token":"${"a".repeat(43)}"}""",
                        stderr = "",
                        exitCode = 0,
                        stdoutOriginalLength = null,
                        stderrOriginalLength = null,
                    ),
                )
            }
        }
        val card = AgentCard(
            id = "card_test",
            name = "Codex",
            icon = "codex",
            recipeId = "recipe_codex",
            templateId = "tpl_codex_ubuntu",
            profileId = null,
            termuxSessionName = "agentdeck-codex",
            workspaceNamespace = PathNamespace.UBUNTU,
            workspacePath = "/root/projects/default",
        )

        CodexBridgeLauncher(gateway).launch(card).getOrThrow()

        val command = requireNotNull(captured)
        val keyIndex = command.args.indexOf("--instance-key")
        val instanceKey = command.args[keyIndex + 1]
        assertTrue(keyIndex >= 0)
        assertTrue(instanceKey.matches(Regex("[a-f0-9]{1,16}")))
        assertEquals("agentdeck-chat-$instanceKey", command.sessionName)
    }

    @Test
    fun `stop targets only the validated app server instance`() {
        var captured: TermuxCommand? = null
        val gateway = object : TermuxGateway {
            override fun isTermuxInstalled() = true
            override fun hasRunCommandPermission() = true
            override fun openTermux() = true
            override fun openTermuxInstallPage() = true
            override fun openTermuxAppSettings() = true
            override fun runCommand(command: TermuxCommand): Result<Unit> {
                captured = command
                return Result.success(Unit)
            }
            override suspend fun runCommandForResult(
                command: TermuxCommand,
                timeoutMillis: Long,
            ) = error("not used")
        }

        CodexBridgeLauncher(gateway).stop(
            CodexBridgeEndpoint(48_123, "a".repeat(64), "abc123"),
        ).getOrThrow()

        val command = requireNotNull(captured)
        assertEquals(listOf("--instance-key", "abc123", "--stop"), command.args)
        assertEquals("agentdeck-chat-stop-abc123", command.sessionName)
    }
}
