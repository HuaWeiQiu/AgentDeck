package com.agentdeck.app.domain.env

import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import com.agentdeck.app.domain.runtime.RuntimeKind
import com.agentdeck.app.domain.runtime.RuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedEnvironmentProbeTest {
    @Test
    fun `not installed runtime offers one install action`() = runBlocking {
        val runtime = FakeRuntime(
            RuntimeStatus(RuntimeKind.EMBEDDED_PROOT, true, false, false, "尚未安装"),
        )

        val report = EmbeddedEnvironmentProbe(runtime).scan()

        assertEquals(EnvironmentCheckStatus.READY, report.check("embedded_supported")?.status)
        assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, report.check("embedded_runtime")?.status)
        assertEquals(EnvironmentCheckStatus.BLOCKED, report.check("codex_installed")?.status)
    }

    @Test
    fun `ready runtime uses doctor facts`() = runBlocking {
        val runtime = FakeRuntime(
            RuntimeStatus(RuntimeKind.EMBEDDED_PROOT, true, true, true, "可用"),
            RuntimeCommandResult(
                stdout = """
                    ubuntu_installed	ready	Ubuntu 24.04
                    embedded_tools	ready	工具可用
                    codex_installed	ready	Codex 0.147.0
                    codex_wrapper	ready	app-server 可用
                    codex_authenticated	action_required	需要连接
                """.trimIndent(),
                stderr = "",
                exitCode = 0,
            ),
        )

        val report = EmbeddedEnvironmentProbe(runtime).scan()

        assertEquals(true, report.canLaunchSessions)
        assertEquals(false, report.allCriticalOk)
        assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, report.check("codex_authenticated")?.status)
        assertTrue(runtime.lastCommand?.script.orEmpty().contains("agentdeck.config.toml"))
        assertTrue(runtime.lastCommand?.script.orEmpty().contains("codex login status"))
    }

    private class FakeRuntime(
        private val status: RuntimeStatus,
        private val result: RuntimeCommandResult = RuntimeCommandResult("", "", 0),
    ) : AgentRuntime {
        var lastCommand: RuntimeCommand? = null
            private set

        override val kind = RuntimeKind.EMBEDDED_PROOT
        override fun status() = status
        override fun openConsole() = false
        override fun openInstallPage() = false
        override fun openAppSettings() = false
        override fun runCommand(command: RuntimeCommand) = Result.success(Unit)
        override suspend fun runCommandForResult(command: RuntimeCommand, timeoutMillis: Long) =
            Result.success(result).also { lastCommand = command }
        override fun stop(instanceId: String) = Result.success(Unit)
    }
}
