package com.agentdeck.app.ui.setup

import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.setup.readyReport
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStatusUiTest {
    @Test
    fun `primary setup list combines proot and ubuntu`() {
        val steps = primarySetupSteps(readyReport())

        assertEquals(7, steps.size)
        assertEquals(1, steps.count { it.id == "ubuntu_runtime" })
        assertEquals(EnvironmentCheckStatus.READY, steps.single { it.id == "ubuntu_runtime" }.status)
    }

    @Test
    fun `combined ubuntu step exposes first missing dependency`() {
        val report = EnvironmentReport(
            readyReport().checks.map { check ->
                when (check.id) {
                    "proot_distro" -> check.copy(
                        status = EnvironmentCheckStatus.ACTION_REQUIRED,
                        detail = "missing proot",
                    )
                    "ubuntu_installed" -> check.copy(status = EnvironmentCheckStatus.BLOCKED)
                    else -> check
                }
            },
        )

        val ubuntu = primarySetupSteps(report).single { it.id == "ubuntu_runtime" }

        assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, ubuntu.status)
        assertEquals("missing proot", ubuntu.detail)
    }
}
