package com.agentdeck.app.domain.setup

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
    fun `install does not start on unsupported device`() {
        val unsupported = reportWith("embedded_supported", EnvironmentCheckStatus.BLOCKED)
        val installer = FakeInstaller()
        val coordinator = SetupCoordinator(
            scanner = FakeScanner(unsupported, unsupported),
            installer = installer,
            scope = scope,
        )

        coordinator.installCodex()

        assertEquals(0, installer.calls)
        assertEquals(SetupAction.UNSUPPORTED_DEVICE, coordinator.state.value.action)
    }

    @Test
    fun `failed install rescans and exposes retryable error`() {
        val missingCodex = reportWith("codex_installed", EnvironmentCheckStatus.ACTION_REQUIRED)
        val installer = FakeInstaller(Result.failure(IllegalStateException("download failed")))
        val coordinator = SetupCoordinator(
            scanner = FakeScanner(missingCodex, missingCodex),
            installer = installer,
            scope = scope,
        )

        coordinator.installCodex()

        assertFalse(coordinator.state.value.isInstalling)
        assertEquals("download failed", coordinator.state.value.error)
        assertEquals(SetupAction.INSTALL_CODEX, coordinator.state.value.action)
    }

    @Test
    fun `verified AgentDeck provider satisfies model connection automatically`() {
        val loginRequired = reportWith("codex_authenticated", EnvironmentCheckStatus.ACTION_REQUIRED)
        val coordinator = SetupCoordinator(
            scanner = FakeScanner(loginRequired, loginRequired),
            installer = FakeInstaller(),
            scope = scope,
            managedProviderReady = { true },
        )

        coordinator.scan(force = true)

        val auth = coordinator.state.value.report.check("codex_authenticated")
        assertEquals(EnvironmentCheckStatus.READY, auth?.status)
        assertEquals(
            "AgentDeck 模型服务已验证，将在会话启动时自动连接",
            auth?.detail,
        )
        assertEquals(SetupAction.READY, coordinator.state.value.action)
    }

    @Test
    fun `scan within cache window reuses last result`() {
        val scanner = FakeScanner(readyReport(), readyReport())
        val coordinator = SetupCoordinator(
            scanner = scanner,
            installer = FakeInstaller(),
            scope = scope,
        )

        coordinator.scan()
        coordinator.scan()

        assertEquals(1, scanner.scans)
    }

    @Test
    fun `forced scan bypasses cache window`() {
        val scanner = FakeScanner(readyReport(), readyReport())
        val coordinator = SetupCoordinator(
            scanner = scanner,
            installer = FakeInstaller(),
            scope = scope,
        )

        coordinator.scan()
        coordinator.scan(force = true)

        assertEquals(2, scanner.scans)
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
        var scans = 0
            private set

        override fun initialReport() = initial
        override suspend fun scan(): EnvironmentReport {
            scans += 1
            return scanned
        }
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

}
