package com.agentdeck.app.data.chat

import com.agentdeck.app.data.config.CodexProfileSynchronizer
import com.agentdeck.app.data.config.CodexProfileRuntimeConfig
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxCommandResult
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.data.runtime.TermuxRuntime
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `managed runtime requires a separate credential token`() {
        val credentialToken = "b".repeat(64)

        val endpoint = CodexBridgeLauncher.parseEndpoint(
            """{"port":48123,"token":"${"a".repeat(43)}","credential_token":"$credentialToken"}""",
            "abc123",
            expectsCredentialToken = true,
        )

        assertEquals(credentialToken, endpoint.credentialToken)
        assertTrue(
            runCatching {
                CodexBridgeLauncher.parseEndpoint(
                    """{"port":48123,"token":"${"a".repeat(43)}"}""",
                    "abc123",
                    expectsCredentialToken = true,
                )
            }.isFailure,
        )
    }

    @Test
    fun `launcher gives each card a validated bridge instance key`() = runBlocking {
        var captured: TermuxCommand? = null
        var synchronizedDistro: String? = null
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

        val profileConfig = CodexProfileRuntimeConfig.fromValidatedToml(
            "model_reasoning_effort = \"high\"\n",
        )
        val launcher = CodexBridgeLauncher(
            TermuxRuntime(gateway),
            CodexProfileSynchronizer { distro ->
                synchronizedDistro = distro
                Result.success(profileConfig)
            },
        )
        val endpoint = launcher.launch(card).getOrThrow()

        val command = requireNotNull(captured)
        val keyIndex = command.args.indexOf("--instance-key")
        val instanceKey = command.args[keyIndex + 1]
        assertTrue(keyIndex >= 0)
        assertTrue(instanceKey.matches(Regex("[a-f0-9]{1,16}")))
        assertEquals("agentdeck-chat-$instanceKey", command.sessionName)
        assertEquals("ubuntu", synchronizedDistro)
        assertEquals(
            "high",
            endpoint.profileConfig.sessionConfig(managedProvider = false)
                .getString("model_reasoning_effort"),
        )

        synchronizedDistro = null
        val accountEndpoint = launcher.launchForAccount(card).getOrThrow()
        assertNull(synchronizedDistro)
        assertEquals(0, accountEndpoint.profileConfig.sessionConfig(false).length())
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

        CodexBridgeLauncher(TermuxRuntime(gateway)).stop(
            CodexBridgeEndpoint(48_123, "a".repeat(64), "abc123"),
        ).getOrThrow()

        val command = requireNotNull(captured)
        assertEquals(listOf("--instance-key", "abc123", "--stop"), command.args)
        assertEquals("agentdeck-chat-stop-abc123", command.sessionName)
    }

    @Test
    fun `managed provider is passed without API key`() = runBlocking {
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
                        stdout = """{"port":48123,"token":"${"a".repeat(43)}","credential_token":"${"b".repeat(64)}"}""",
                        stderr = "",
                        exitCode = 0,
                        stdoutOriginalLength = null,
                        stderrOriginalLength = null,
                    ),
                )
            }
        }
        val card = testCard().copy(profileId = "prof_test", modelId = "gpt-5")
        val runtime = ManagedProviderRuntime.from(
            ProviderProfile(
                id = "prof_test",
                name = "Sub2API",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.example.com/v1",
                defaultModel = "gpt-5",
                credentialRef = "cred_test",
            ),
            modelId = "gpt-5",
            credentialBrokerPort = 45_678,
        )

        CodexBridgeLauncher(TermuxRuntime(gateway)).launch(card, runtime).getOrThrow()

        val args = requireNotNull(captured).args
        assertEquals(runtime.providerId, args[args.indexOf("--provider-id") + 1])
        assertEquals("https://api.example.com/v1", args[args.indexOf("--base-url") + 1])
        assertEquals("gpt-5", args[args.indexOf("--model") + 1])
        assertEquals("cred_test", args[args.indexOf("--credential-ref") + 1])
        assertFalse(args.any { it.contains("sk-") })
    }

    private fun testCard() = AgentCard(
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
}
