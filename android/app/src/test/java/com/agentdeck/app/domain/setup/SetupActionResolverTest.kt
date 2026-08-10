package com.agentdeck.app.domain.setup

import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupActionResolverTest {
    @Test
    fun `actions follow embedded setup dependency order`() {
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
            SetupState(EnvironmentReport(emptyList())).action,
        )
        assertEquals(true, SetupState(readyReport(), isScanning = true).isReady)
    }

    @Test
    fun `embedded setup installs runtime before authentication`() {
        val missingRuntime = embeddedReport().checks.map { check ->
            if (check.id == "embedded_runtime") {
                check.copy(status = EnvironmentCheckStatus.ACTION_REQUIRED)
            } else if (check.id != "embedded_supported") {
                check.copy(status = EnvironmentCheckStatus.BLOCKED)
            } else {
                check
            }
        }
        assertEquals(
            SetupAction.INSTALL_CODEX,
            SetupState(EnvironmentReport(missingRuntime)).action,
        )
        assertEquals(SetupAction.READY, SetupState(embeddedReport()).action)
    }

    private fun stateWith(id: String, status: EnvironmentCheckStatus): SetupState {
        val checks = readyReport().checks.map { check ->
            if (check.id == id) check.copy(status = status) else check
        }
        return SetupState(EnvironmentReport(checks))
    }
}

private fun embeddedReport(): EnvironmentReport = EnvironmentReport(
    listOf(
        "embedded_supported",
        "embedded_runtime",
        "ubuntu_installed",
        "embedded_tools",
        "codex_installed",
        "codex_wrapper",
        "codex_authenticated",
    ).map { id ->
        EnvironmentCheck(id, id, EnvironmentCheckStatus.READY, "ready")
    },
)

internal fun readyReport(): EnvironmentReport = EnvironmentReport(
    listOf(
        "embedded_supported",
        "embedded_runtime",
        "ubuntu_installed",
        "embedded_tools",
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
