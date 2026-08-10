package com.agentdeck.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentReportTest {
    @Test
    fun `login is not required to expose the conversation entry`() {
        val readyForLaunch = setOf(
            "embedded_supported",
            "embedded_runtime",
            "ubuntu_installed",
            "embedded_tools",
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
