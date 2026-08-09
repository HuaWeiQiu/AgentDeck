package com.agentdeck.app.ui.setup

import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.domain.setup.readyReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupStatusUiTest {
    @Test
    fun `customer setup list groups technical checks`() {
        val steps = customerSetupSteps(readyReport())

        assertEquals(4, steps.size)
        assertEquals(
            listOf("device_ready", "local_runtime", "agent_ready", "model_connection"),
            steps.map { it.id },
        )
        assertEquals(EnvironmentCheckStatus.READY, steps.single { it.id == "local_runtime" }.status)
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

        val runtime = customerSetupSteps(report).single { it.id == "local_runtime" }

        assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, runtime.status)
        assertEquals("需要完成此步骤", runtime.detail)
    }

    @Test
    fun `missing technical check fails its customer group`() {
        val report = EnvironmentReport(
            readyReport().checks.filterNot { it.id == "codex_wrapper" },
        )

        val agent = customerSetupSteps(report).single { it.id == "agent_ready" }

        assertEquals(EnvironmentCheckStatus.ERROR, agent.status)
        assertEquals("暂时无法完成检查", agent.detail)
    }

    @Test
    fun `authentication state asks to connect a model service`() {
        val report = EnvironmentReport(
            readyReport().checks.map { check ->
                if (check.id == "codex_authenticated") {
                    check.copy(status = EnvironmentCheckStatus.ACTION_REQUIRED)
                } else {
                    check
                }
            },
        )

        val presentation = customerSetupPresentation(SetupState(report))

        assertEquals("连接模型服务", presentation.title)
        assertEquals("连接模型服务", presentation.primaryActionLabel)
    }

    @Test
    fun `customer error does not expose raw runtime details`() {
        val presentation = customerSetupPresentation(
            SetupState(
                report = readyReport(),
                error = "Termux app-server exited with code 127",
            ),
        )

        assertEquals("准备未完成", presentation.title)
        assertFalse(presentation.errorMessage.orEmpty().contains("Termux"))
        assertFalse(presentation.errorMessage.orEmpty().contains("127"))
    }

    @Test
    fun `installing state reports installation rather than scanning`() {
        val presentation = customerSetupPresentation(
            SetupState(report = readyReport(), isInstalling = true),
        )

        assertEquals("正在准备 Codex", presentation.title)
        assertEquals("正在安装并验证所需组件", presentation.summary)
        assertEquals("准备中", presentation.primaryActionLabel)
    }
}
