package com.agentdeck.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentReportTest {
    @Test
    fun `login is not required to expose the conversation entry`() {
        val readyForLaunch = setOf(
            "termux_installed",
            "termux_run_command_permission",
            "allow_external_apps",
            "proot_distro",
            "ubuntu_installed",
            "codex_installed",
            "codex_wrapper",
        )
        val report = EnvironmentReport(
            checks = (readyForLaunch + "codex_authenticated").map { id ->
                EnvironmentCheck(
                    id = id,
                    label = id,
                    status = if (id in readyForLaunch) {
                        EnvironmentCheckStatus.READY
                    } else {
                        EnvironmentCheckStatus.ACTION_REQUIRED
                    },
                    detail = id,
                )
            },
        )

        assertTrue(report.canLaunchSessions)
        assertFalse(report.allCriticalOk)
        assertTrue(com.agentdeck.app.domain.setup.SetupState(report).canStartChat)
        assertFalse(com.agentdeck.app.domain.setup.SetupState(report).isReady)
    }
}
