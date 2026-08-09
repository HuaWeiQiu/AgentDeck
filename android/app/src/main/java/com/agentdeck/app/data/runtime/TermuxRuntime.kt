package com.agentdeck.app.data.runtime

import android.annotation.SuppressLint
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import com.agentdeck.app.domain.runtime.RuntimeKind
import com.agentdeck.app.domain.runtime.RuntimeProgram
import com.agentdeck.app.domain.runtime.RuntimeStatus

@SuppressLint("SdCardPath")
class TermuxRuntime(
    private val gateway: TermuxGateway,
) : AgentRuntime {
    override val kind = RuntimeKind.TERMUX_COMPATIBILITY

    override fun status(): RuntimeStatus {
        val installed = gateway.isTermuxInstalled()
        val permission = installed && gateway.hasRunCommandPermission()
        return RuntimeStatus(
            kind = kind,
            supported = true,
            installed = installed,
            ready = permission,
            detail = when {
                !installed -> "未安装 Termux 兼容运行组件"
                !permission -> "Termux RUN_COMMAND 权限未授予"
                else -> "Termux 兼容运行组件可用"
            },
        )
    }

    override fun openConsole(): Boolean = gateway.openTermux()

    override fun openInstallPage(): Boolean = gateway.openTermuxInstallPage()

    override fun openAppSettings(): Boolean = gateway.openTermuxAppSettings()

    override fun runCommand(command: RuntimeCommand): Result<Unit> = runCatching {
        gateway.runCommand(command.toTermuxCommand()).getOrThrow()
    }

    override suspend fun runCommandForResult(
        command: RuntimeCommand,
        timeoutMillis: Long,
    ): Result<RuntimeCommandResult> = gateway.runCommandForResult(
        command.toTermuxCommand(),
        timeoutMillis,
    ).map { result ->
        RuntimeCommandResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            stdoutOriginalLength = result.stdoutOriginalLength,
            stderrOriginalLength = result.stderrOriginalLength,
        )
    }

    override fun stop(instanceId: String): Result<Unit> {
        require(RuntimeCommand.INSTANCE_ID_PATTERN.matches(instanceId)) { "运行实例标识无效" }
        return Result.success(Unit)
    }

    private fun RuntimeCommand.toTermuxCommand(): TermuxCommand {
        val mapping = when (program) {
            RuntimeProgram.HOST_SHELL -> ProgramMapping(
                executable = TERMUX_BASH,
                args = listOf("-c", requireNotNull(script) { "主机 Shell 请求缺少脚本" }),
                defaultWorkDir = TermuxCommand.TERMUX_HOME,
            )
            RuntimeProgram.CODEX_TERMINAL -> ProgramMapping(CODEX_TERMINAL_WRAPPER, args)
            RuntimeProgram.CLAUDE_TERMINAL -> ProgramMapping(CLAUDE_BIN, args)
            RuntimeProgram.CODEX_APP_SERVER -> ProgramMapping(CODEX_APP_SERVER_WRAPPER, args)
        }
        return TermuxCommand(
            sessionName = instanceId,
            executable = mapping.executable,
            args = mapping.args,
            workDir = workDir ?: mapping.defaultWorkDir,
            background = background,
            reuseExistingSession = reuseExistingInstance,
        )
    }

    private data class ProgramMapping(
        val executable: String,
        val args: List<String>,
        val defaultWorkDir: String = TermuxCommand.TERMUX_HOME,
    )

    companion object {
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val CODEX_TERMINAL_WRAPPER =
            "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-ubuntu.sh"
        private const val CODEX_APP_SERVER_WRAPPER =
            "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-app-server-start.sh"
        private const val CLAUDE_BIN = "/data/data/com.termux/files/usr/bin/claude"
    }
}
