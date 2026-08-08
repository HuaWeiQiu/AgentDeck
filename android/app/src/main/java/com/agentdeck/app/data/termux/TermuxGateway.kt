package com.agentdeck.app.data.termux

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

@SuppressLint("SdCardPath")
data class TermuxCommand(
    val sessionName: String,
    val executable: String,
    val args: List<String> = emptyList(),
    val workDir: String = TERMUX_HOME,
    val background: Boolean = false,
    val reuseExistingSession: Boolean = true,
) {
    init {
        require(sessionName.matches(SESSION_NAME_PATTERN)) {
            "Termux 会话名只能包含字母、数字、点、下划线和连字符，且长度不超过 64"
        }
        require(executable.startsWith(TERMUX_FILES_ROOT) && executable.startsWith('/')) {
            "Termux 命令必须使用其私有目录中的绝对路径"
        }
        require(workDir.startsWith(TERMUX_FILES_ROOT) && workDir.startsWith('/')) {
            "Termux 工作目录必须使用其私有目录中的绝对路径"
        }
    }

    companion object {
        // Fixed paths from Termux's RUN_COMMAND contract, not AgentDeck storage paths.
        const val TERMUX_FILES_ROOT = "/data/data/com.termux/files/"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        val SESSION_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/** Bridge to Termux via its RUN_COMMAND service. */
interface TermuxGateway {
    fun isTermuxInstalled(): Boolean
    fun hasRunCommandPermission(): Boolean
    fun openTermux(): Boolean
    fun openTermuxInstallPage(): Boolean
    fun runCommand(command: TermuxCommand): Result<Unit>
    suspend fun runCommandForResult(
        command: TermuxCommand,
        timeoutMillis: Long = DEFAULT_RESULT_TIMEOUT_MILLIS,
    ): Result<TermuxCommandResult>

    companion object {
        const val DEFAULT_RESULT_TIMEOUT_MILLIS = 30_000L
    }
}

class AndroidTermuxGateway(
    private val context: Context,
) : TermuxGateway {

    override fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun hasRunCommandPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            RUN_COMMAND_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun openTermux(): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    override fun openTermuxInstallPage(): Boolean {
        val uri = "https://f-droid.org/packages/com.termux/".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun runCommand(command: TermuxCommand): Result<Unit> {
        return startCommand(command, resultPendingIntent = null)
    }

    override suspend fun runCommandForResult(
        command: TermuxCommand,
        timeoutMillis: Long,
    ): Result<TermuxCommandResult> {
        if (!command.background) {
            return Result.failure(
                IllegalArgumentException("只有后台命令可以等待结构化结果"),
            )
        }
        if (command.reuseExistingSession) {
            return Result.failure(
                IllegalArgumentException("等待结果的命令必须创建独立任务"),
            )
        }
        if (timeoutMillis <= 0) {
            return Result.failure(IllegalArgumentException("结果超时必须大于 0"))
        }

        val requestId = UUID.randomUUID().toString()
        val deferred = TermuxResultRegistry.register(requestId)
        val callback = runCatching { createResultPendingIntent(requestId) }.getOrElse { error ->
            TermuxResultRegistry.remove(requestId)
            return Result.failure(error)
        }
        val start = startCommand(command, callback)
        if (start.isFailure) {
            TermuxResultRegistry.remove(requestId)
            runCatching { callback.cancel() }
            return Result.failure(start.exceptionOrNull() ?: IllegalStateException("命令未启动"))
        }

        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            Result.failure(
                IllegalStateException("Termux 命令在 ${timeoutMillis / 1_000} 秒内没有返回结果"),
            )
        } finally {
            TermuxResultRegistry.remove(requestId)
            runCatching { callback.cancel() }
        }
    }

    private fun startCommand(
        command: TermuxCommand,
        resultPendingIntent: PendingIntent?,
    ): Result<Unit> {
        if (!isTermuxInstalled()) {
            return Result.failure(IllegalStateException("Termux 未安装（需要 F-Droid 版 com.termux）"))
        }
        if (!hasRunCommandPermission()) {
            return Result.failure(IllegalStateException("尚未授予 Termux RUN_COMMAND 权限"))
        }

        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, command.executable)
            putExtra(EXTRA_ARGUMENTS, command.args.toTypedArray())
            putExtra(EXTRA_WORKDIR, command.workDir)
            putExtra(EXTRA_BACKGROUND, command.background)
            putExtra(
                EXTRA_RUNNER,
                if (command.background) RUNNER_APP_SHELL else RUNNER_TERMINAL_SESSION,
            )
            if (!command.background) {
                putExtra(EXTRA_SESSION_ACTION, SESSION_ACTION_SWITCH_TO_SESSION_AND_OPEN)
            }
            putExtra(EXTRA_SHELL_NAME, command.sessionName)
            putExtra(
                EXTRA_SHELL_CREATE_MODE,
                if (command.reuseExistingSession) {
                    SHELL_CREATE_MODE_NO_SHELL_WITH_NAME
                } else {
                    SHELL_CREATE_MODE_ALWAYS
                },
            )
            if (resultPendingIntent != null) {
                putExtra(EXTRA_PENDING_INTENT, resultPendingIntent)
            }
        }

        return runCatching {
            checkNotNull(context.startService(intent)) {
                "Termux RUN_COMMAND 服务未接受命令"
            }
            Unit
        }
    }

    private fun createResultPendingIntent(requestId: String): PendingIntent {
        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = RESULT_CALLBACK_ACTION
            data = Uri.Builder()
                .scheme("agentdeck")
                .authority("termux-result")
                .appendPath(requestId)
                .build()
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        return PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            callbackIntent,
            flags,
        )
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_SHELL_NAME = "com.termux.RUN_COMMAND_SHELL_NAME"
        const val EXTRA_SHELL_CREATE_MODE = "com.termux.RUN_COMMAND_SHELL_CREATE_MODE"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val SESSION_ACTION_SWITCH_TO_SESSION_AND_OPEN = "0"
        const val SHELL_CREATE_MODE_ALWAYS = "always"
        const val SHELL_CREATE_MODE_NO_SHELL_WITH_NAME = "no-shell-with-name"
        const val RUNNER_TERMINAL_SESSION = "terminal-session"
        const val RUNNER_APP_SHELL = "app-shell"
        const val RESULT_CALLBACK_ACTION = "com.agentdeck.app.TERMUX_RESULT"
    }
}
