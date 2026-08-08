package com.agentdeck.app.domain.setup

import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupActionResolverTest {
    @Test
    fun `actions follow setup dependency order`() {
        assertEquals(
            SetupAction.INSTALL_TERMUX,
            stateWith("termux_installed", EnvironmentCheckStatus.ACTION_REQUIRED).action,
        )
        assertEquals(
            SetupAction.GRANT_PERMISSION,
            stateWith("termux_run_command_permission", EnvironmentCheckStatus.ACTION_REQUIRED).action,
        )
        assertEquals(
            SetupAction.ALLOW_TERMUX_BACKGROUND,
            stateWith("termux_background_execution", EnvironmentCheckStatus.ACTION_REQUIRED).action,
        )
        assertEquals(
            SetupAction.ENABLE_EXTERNAL_APPS,
            stateWith("allow_external_apps", EnvironmentCheckStatus.ACTION_REQUIRED).action,
        )
        assertEquals(
            SetupAction.INSTALL_CODEX,
            stateWith("ubuntu_installed", EnvironmentCheckStatus.BLOCKED).action,
        )
        assertEquals(
            SetupAction.CONFIGURE_CODEX_AUTH,
            stateWith("codex_authenticated", EnvironmentCheckStatus.ACTION_REQUIRED).action,
        )
        assertEquals(SetupAction.READY, SetupState(readyReport()).action)
    }

    @Test
    fun `unknown checks and active work resolve to scan`() {
        assertEquals(
            SetupAction.SCAN,
            stateWith("codex_installed", EnvironmentCheckStatus.UNKNOWN).action,
        )
        assertEquals(
            SetupAction.SCAN,
            SetupState(readyReport(), isInstalling = true).action,
        )
        assertEquals(
            SetupAction.SCAN,
            stateWith("allow_external_apps", EnvironmentCheckStatus.ERROR).action,
        )
        assertEquals(true, SetupState(readyReport(), isScanning = true).isReady)
    }

    private fun stateWith(id: String, status: EnvironmentCheckStatus): SetupState {
        val checks = readyReport().checks.map { check ->
            if (check.id == id) check.copy(status = status) else check
        }
        return SetupState(EnvironmentReport(checks))
    }
}

internal fun readyReport(): EnvironmentReport = EnvironmentReport(
    listOf(
        "termux_installed",
        "termux_run_command_permission",
        "termux_background_execution",
        "allow_external_apps",
        "proot_distro",
        "ubuntu_installed",
        "codex_installed",
        "codex_authenticated",
        "codex_wrapper",
    ).map { id ->
        EnvironmentCheck(
            id = id,
            label = id,
            status = EnvironmentCheckStatus.READY,
            detail = "ready",
        )
    },
)
