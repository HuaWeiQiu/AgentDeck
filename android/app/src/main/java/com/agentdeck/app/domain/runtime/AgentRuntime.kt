package com.agentdeck.app.domain.runtime

enum class RuntimeKind {
    EMBEDDED_PROOT,
    TERMUX_COMPATIBILITY,
}

enum class RuntimeProgram {
    HOST_SHELL,
    CODEX_TERMINAL,
    CLAUDE_TERMINAL,
    CODEX_APP_SERVER,
}

data class RuntimeCommand(
    val instanceId: String,
    val program: RuntimeProgram,
    val args: List<String> = emptyList(),
    val script: String? = null,
    val workDir: String? = null,
    val background: Boolean = false,
    val reuseExistingInstance: Boolean = true,
) {
    init {
        require(instanceId.matches(INSTANCE_ID_PATTERN)) {
            "运行实例标识只能包含字母、数字、点、下划线和连字符，且长度不超过 64"
        }
        require(args.size <= 128 && args.all { it.length <= 4_096 && '\u0000' !in it }) {
            "运行参数无效"
        }
        require(script == null || script.length <= MAX_SCRIPT_LENGTH) { "运行脚本过长" }
        require(program == RuntimeProgram.HOST_SHELL || script == null) {
            "只有主机 Shell 请求可以携带脚本"
        }
        require(workDir == null || (workDir.startsWith('/') && '\u0000' !in workDir)) {
            "运行工作目录必须是绝对路径"
        }
    }

    companion object {
        val INSTANCE_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        private const val MAX_SCRIPT_LENGTH = 512 * 1024
    }
}

data class RuntimeCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val stdoutOriginalLength: Int? = null,
    val stderrOriginalLength: Int? = null,
) {
    val commandSucceeded: Boolean
        get() = exitCode == 0

    val outputWasTruncated: Boolean
        get() = (stdoutOriginalLength ?: stdout.length) > stdout.length ||
            (stderrOriginalLength ?: stderr.length) > stderr.length
}

data class RuntimeStatus(
    val kind: RuntimeKind,
    val supported: Boolean,
    val installed: Boolean,
    val ready: Boolean,
    val detail: String,
)

interface AgentRuntime {
    val kind: RuntimeKind

    fun status(): RuntimeStatus
    fun openConsole(): Boolean
    fun openInstallPage(): Boolean
    fun openAppSettings(): Boolean
    fun runCommand(command: RuntimeCommand): Result<Unit>
    suspend fun runCommandForResult(
        command: RuntimeCommand,
        timeoutMillis: Long = DEFAULT_RESULT_TIMEOUT_MILLIS,
    ): Result<RuntimeCommandResult>

    fun stop(instanceId: String): Result<Unit>

    companion object {
        const val DEFAULT_RESULT_TIMEOUT_MILLIS = 30_000L
    }
}
