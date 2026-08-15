package com.agentdeck.app.ui.setup

import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.install.InstallPhase
import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.domain.setup.readyReport
import org.junit.Assert.assertEquals
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
    fun `combined runtime step exposes first missing dependency`() {
        val report = EnvironmentReport(
            readyReport().checks.map { check ->
                when (check.id) {
                    "embedded_runtime" -> check.copy(
                        status = EnvironmentCheckStatus.ACTION_REQUIRED,
                        detail = "missing runtime",
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
    fun `customer error surfaces actionable install reason`() {
        val presentation = customerSetupPresentation(
            SetupState(
                report = readyReport(),
                error = "安装基础工具失败：Could not resolve 'ports.ubuntu.com'",
            ),
        )

        assertEquals("准备未完成", presentation.title)
        val message = presentation.errorMessage.orEmpty()
        assertEquals(true, message.contains("未能完成当前步骤"))
        assertEquals(true, message.contains("现有对话和项目不会受到影响"))
        assertEquals(true, message.contains("原因：安装基础工具失败"))
        assertEquals(true, message.contains("ports.ubuntu.com"))
    }

    @Test
    fun `customer setup error message collapses whitespace`() {
        val message = customerSetupErrorMessage("  安装失败\n\n  网络超时  ")
        assertEquals(
            "未能完成当前步骤。现有对话和项目不会受到影响。\n\n原因：安装失败 网络超时",
            message,
        )
    }

    @Test
    fun `installing state reports installation rather than scanning`() {
        val presentation = customerSetupPresentation(
            SetupState(report = readyReport(), isInstalling = true),
        )

        assertEquals("正在准备聊天环境", presentation.title)
        assertEquals("第一次需要下载组件，请保持网络畅通", presentation.summary)
        assertEquals("准备中", presentation.primaryActionLabel)
    }

    @Test
    fun `download progress exposes bytes and weighted overall progress`() {
        val presentation = setupInstallProgressPresentation(
            RecipeInstallProgress(
                recipeId = "recipe_codex",
                recipeName = "Codex Runtime",
                recipeIndex = 0,
                recipeCount = 1,
                phase = InstallPhase.DOWNLOADING,
                bytesDone = 60L * 1024 * 1024,
                bytesTotal = 120L * 1024 * 1024,
            ),
        )

        assertEquals("正在下载聊天组件", presentation.title)
        assertEquals("60 MB / 120 MB（50%）", presentation.detail)
        assertEquals("正在下载", presentation.stageLabel)
        assertEquals(0.275f, presentation.overallFraction, 0.0001f)
        assertEquals("请保持 Wi-Fi 连接，第一次准备大约需要几分钟", presentation.hint)
    }

    @Test
    fun `download hint explains international route and source switch`() {
        val presentation = setupInstallProgressPresentation(
            RecipeInstallProgress(
                recipeId = "recipe_codex",
                recipeName = "Codex Runtime",
                recipeIndex = 0,
                recipeCount = 1,
                phase = InstallPhase.DOWNLOADING,
                bytesDone = 28L * 1024 * 1024,
                bytesTotal = 116L * 1024 * 1024,
                prefersDomesticSources = false,
                sourceSwitchCount = 1,
                switchingSource = true,
            ),
        )
        assertEquals("当前线路较慢，正在换一条线路继续下", presentation.hint)
    }

    @Test
    fun `tool installation explains long indeterminate work`() {
        val presentation = setupInstallProgressPresentation(
            RecipeInstallProgress(
                recipeId = "recipe_codex",
                recipeName = "Codex Runtime",
                recipeIndex = 0,
                recipeCount = 1,
                phase = InstallPhase.INSTALLING_TOOLS,
            ),
        )

        assertEquals("安装基础工具", presentation.title)
        assertEquals("正在安装工具", presentation.stageLabel)
        assertEquals(true, presentation.detail.contains("国内软件源"))
        assertEquals(true, presentation.detail.contains("可能需要几分钟"))
    }
}
