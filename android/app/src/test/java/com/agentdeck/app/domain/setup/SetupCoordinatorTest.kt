package com.agentdeck.app.domain.setup

import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxCommandResult
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.data.runtime.TermuxRuntime
import com.agentdeck.app.domain.env.EnvironmentScanner
import com.agentdeck.app.domain.install.InstallPhase
import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `install reports progress then rescans to ready`() {
        val missingCodex = reportWith("codex_installed", EnvironmentCheckStatus.ACTION_REQUIRED)
        val scanner = FakeScanner(initial = missingCodex, scanned = readyReport())
        val installer = FakeInstaller()
        val reports = mutableListOf<EnvironmentReport>()
        val coordinator = SetupCoordinator(
            scanner = scanner,
            installer = installer,
            runtime = TermuxRuntime(FakeTermuxGateway()),
            scope = scope,
            onReport = reports::add,
        )

        coordinator.installCodex()

        assertEquals(1, installer.calls)
        assertFalse(coordinator.state.value.isInstalling)
        assertEquals(SetupAction.READY, coordinator.state.value.action)
        assertEquals("Codex CLI 已可用并验证", coordinator.state.value.message)
        assertEquals(1, reports.size)
    }

    @Test
    fun `install does not start before runtime prerequisites`() {
        val missingTermux = reportWith("termux_installed", EnvironmentCheckStatus.ACTION_REQUIRED)
        val installer = FakeInstaller()
        val coordinator = SetupCoordinator(
            scanner = FakeScanner(missingTermux, missingTermux),
            installer = installer,
            runtime = TermuxRuntime(FakeTermuxGateway()),
            scope = scope,
        )

        coordinator.installCodex()

        assertEquals(0, installer.calls)
        assertEquals(SetupAction.INSTALL_TERMUX, coordinator.state.value.action)
    }

    @Test
    fun `failed install rescans and exposes retryable error`() {
        val missingCodex = reportWith("codex_installed", EnvironmentCheckStatus.ACTION_REQUIRED)
        val installer = FakeInstaller(Result.failure(IllegalStateException("download failed")))
        val coordinator = SetupCoordinator(
            scanner = FakeScanner(missingCodex, missingCodex),
            installer = installer,
            runtime = TermuxRuntime(FakeTermuxGateway()),
            scope = scope,
        )

        coordinator.installCodex()

        assertFalse(coordinator.state.value.isInstalling)
        assertEquals("download failed", coordinator.state.value.error)
        assertEquals(SetupAction.INSTALL_CODEX, coordinator.state.value.action)
    }

    @Test
    fun `authentication opens fixed helper through wrapper`() {
        val loginRequired = reportWith("codex_authenticated", EnvironmentCheckStatus.ACTION_REQUIRED)
        val gateway = FakeTermuxGateway()
        val coordinator = SetupCoordinator(
            scanner = FakeScanner(loginRequired, loginRequired),
            installer = FakeInstaller(),
            runtime = TermuxRuntime(gateway),
            scope = scope,
        )

        val result = coordinator.startCodexAuthentication()

        assertTrue(result.isSuccess)
        assertTrue(gateway.opened)
        assertEquals(1, gateway.commands.size)
        assertEquals(
            "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-ubuntu.sh",
            gateway.commands.single().executable,
        )
        assertEquals("bash", gateway.commands.single().args[5])
        assertEquals("-lc", gateway.commands.single().args[7])
        val script = gateway.commands.single().args.last()
        assertTrue(script.contains("codex login --with-api-key"))
        assertTrue(script.contains("timeout --kill-after=1s 5s codex login status"))
        assertTrue(script.indexOf("OPENAI_API_KEY") < script.indexOf("codex login status"))
        assertTrue(script.indexOf("model_providers") < script.indexOf("codex login status"))
    }

    private fun reportWith(id: String, status: EnvironmentCheckStatus): EnvironmentReport =
        EnvironmentReport(
            readyReport().checks.map { check ->
                if (check.id == id) check.copy(status = status) else check
            },
        )

    private class FakeScanner(
        private val initial: EnvironmentReport,
        private val scanned: EnvironmentReport,
    ) : EnvironmentScanner {
        override fun initialReport() = initial
        override suspend fun scan() = scanned
        override fun allowExternalAppsFixCommand() = "fix"
        override fun errorReport(message: String) = initial
    }

    private class FakeInstaller(
        private val result: Result<String> = Result.success("Codex CLI 已可用并验证"),
    ) : RecipeInstallation {
        var calls = 0

        override suspend fun install(
            recipeId: String,
            onProgress: (RecipeInstallProgress) -> Unit,
        ): Result<String> {
            calls += 1
            onProgress(
                RecipeInstallProgress(
                    recipeId = recipeId,
                    recipeName = "Codex CLI",
                    recipeIndex = 0,
                    recipeCount = 1,
                    phase = InstallPhase.INSTALLING,
                ),
            )
            return result
        }
    }

    private class FakeTermuxGateway : TermuxGateway {
        val commands = mutableListOf<TermuxCommand>()
        var opened = false

        override fun isTermuxInstalled() = true
        override fun hasRunCommandPermission() = true
        override fun openTermux(): Boolean {
            opened = true
            return true
        }
        override fun openTermuxInstallPage() = true
        override fun openTermuxAppSettings() = true
        override fun runCommand(command: TermuxCommand): Result<Unit> {
            commands += command
            return Result.success(Unit)
        }
        override suspend fun runCommandForResult(
            command: TermuxCommand,
            timeoutMillis: Long,
        ) = Result.success(TermuxCommandResult("", "", 0, 0, 0))
    }
}
