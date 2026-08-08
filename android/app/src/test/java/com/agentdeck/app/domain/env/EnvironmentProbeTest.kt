package com.agentdeck.app.domain.env

import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxCommandResult
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentProbeTest {
    @Test
    fun `runtime timeout preserves successful Termux host checks`() = runBlocking {
        val hostOutput = """
            allow_external_apps	ready	已启用
            codex_wrapper	ready	Native Chat WebSocket 组件已安装
            proot_distro	ready	proot-distro 已安装
        """.trimIndent()
        val gateway = FakeTermuxGateway(
            mutableListOf(
                Result.success(successResult(hostOutput)),
                Result.failure(IllegalStateException("Termux 命令在 25 秒内没有返回结果")),
            ),
        )

        val report = EnvironmentProbe(gateway).scan()

        assertEquals(
            EnvironmentCheckStatus.READY,
            report.check("termux_background_execution")?.status,
        )
        assertEquals(EnvironmentCheckStatus.READY, report.check("allow_external_apps")?.status)
        assertEquals(EnvironmentCheckStatus.READY, report.check("proot_distro")?.status)
        assertEquals(EnvironmentCheckStatus.READY, report.check("codex_wrapper")?.status)
        assertEquals(EnvironmentCheckStatus.ERROR, report.check("ubuntu_installed")?.status)
        assertEquals(EnvironmentCheckStatus.BLOCKED, report.check("codex_installed")?.status)
        assertEquals(EnvironmentCheckStatus.BLOCKED, report.check("codex_authenticated")?.status)
        assertEquals(
            "Ubuntu 运行时检查失败：Termux 命令在 25 秒内没有返回结果",
            report.check("ubuntu_installed")?.detail,
        )
        assertEquals(
            listOf("agentdeck-doctor-host", "agentdeck-doctor-runtime"),
            gateway.commands.map { it.sessionName },
        )
        assertEquals(listOf(10_000L, 25_000L), gateway.timeouts)
    }

    @Test
    fun `missing proot stops before Ubuntu phase`() = runBlocking {
        val hostOutput = """
            allow_external_apps	ready	已启用
            codex_wrapper	action_required	需要补齐 2 个启动组件
            proot_distro	action_required	Termux 内未找到 proot-distro
            ubuntu_installed	blocked	先安装 proot-distro
            codex_installed	blocked	先安装 Ubuntu
            codex_authenticated	blocked	先安装 Codex
        """.trimIndent()
        val gateway = FakeTermuxGateway(
            mutableListOf(Result.success(successResult(hostOutput))),
        )

        val report = EnvironmentProbe(gateway).scan()

        assertEquals(EnvironmentCheckStatus.ACTION_REQUIRED, report.check("proot_distro")?.status)
        assertEquals(EnvironmentCheckStatus.BLOCKED, report.check("ubuntu_installed")?.status)
        assertEquals(1, gateway.commands.size)
    }

    @Test
    fun `host callback timeout is reported as restricted Termux background execution`() =
        runBlocking {
            val gateway = FakeTermuxGateway(
                mutableListOf(
                    Result.failure(
                        IllegalStateException("Termux 命令在 10 秒内没有返回结果"),
                    ),
                ),
            )

            val report = EnvironmentProbe(gateway).scan()

            assertEquals(
                EnvironmentCheckStatus.ACTION_REQUIRED,
                report.check("termux_background_execution")?.status,
            )
            assertEquals(
                true,
                report.check("termux_background_execution")?.detail?.contains("后台高耗电"),
            )
            assertEquals(
                EnvironmentCheckStatus.ERROR,
                report.check("allow_external_apps")?.status,
            )
        }

    private class FakeTermuxGateway(
        private val results: MutableList<Result<TermuxCommandResult>>,
    ) : TermuxGateway {
        val commands = mutableListOf<TermuxCommand>()
        val timeouts = mutableListOf<Long>()

        override fun isTermuxInstalled() = true
        override fun hasRunCommandPermission() = true
        override fun openTermux() = true
        override fun openTermuxInstallPage() = true
        override fun openTermuxAppSettings() = true
        override fun runCommand(command: TermuxCommand) = Result.success(Unit)

        override suspend fun runCommandForResult(
            command: TermuxCommand,
            timeoutMillis: Long,
        ): Result<TermuxCommandResult> {
            commands += command
            timeouts += timeoutMillis
            return results.removeAt(0)
        }
    }

    companion object {
        private fun successResult(stdout: String) = TermuxCommandResult(
            stdout = stdout,
            stderr = "",
            exitCode = 0,
            stdoutOriginalLength = stdout.length,
            stderrOriginalLength = 0,
        )
    }
}
