package com.agentdeck.app.data.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Bridge to Termux via RUN_COMMAND intent.
 *
 * Notes:
 * - Prefer F-Droid Termux build (package com.termux).
 * - User must set allow-external-apps=true in ~/.termux/termux.properties
 */
interface TermuxGateway {
    fun isTermuxInstalled(): Boolean
    fun hasRunCommandPermission(): Boolean
    fun openTermux(): Boolean
    fun openTermuxInstallPage(): Boolean

    /**
     * Launch a foreground Termux session running [executable] with [args].
     * [env] is best-effort: values are exported via a bash -lc prelude when possible.
     */
    fun runCommand(
        sessionName: String,
        executable: String,
        args: List<String> = emptyList(),
        workDir: String? = null,
        env: Map<String, String> = emptyMap(),
        background: Boolean = false,
    ): Result<Unit>
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
        val uri = Uri.parse("https://f-droid.org/packages/com.termux/")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun runCommand(
        sessionName: String,
        executable: String,
        args: List<String>,
        workDir: String?,
        env: Map<String, String>,
        background: Boolean,
    ): Result<Unit> {
        if (!isTermuxInstalled()) {
            return Result.failure(IllegalStateException("Termux 未安装（需要 F-Droid 版 com.termux）"))
        }

        // Build a single bash invocation so we can export env before running the real binary.
        val exportPrelude = env.entries.joinToString(" ") { (k, v) ->
            "export ${shellQuote(k)}=${shellQuote(v)};"
        }
        val fullCommand = buildString {
            if (exportPrelude.isNotEmpty()) append(exportPrelude).append(' ')
            append("exec ")
            append(shellQuote(executable))
            args.forEach { arg ->
                append(' ')
                append(shellQuote(arg))
            }
        }

        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", fullCommand))
            putExtra(EXTRA_WORKDIR, workDir ?: "/data/data/com.termux/files/home")
            putExtra(EXTRA_BACKGROUND, background)
            putExtra(EXTRA_SESSION_ACTION, SESSION_ACTION_SWITCH_TO_NEW)
            // Help identify the session in Termux UI / logs
            putExtra(EXTRA_STDIN, "")
            putExtra("com.termux.RUN_COMMAND_SESSION_NAME", sessionName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
            // Also bring Termux UI forward so user lands in the Codex chat TUI.
            openTermux()
            Unit
        }
    }

    private fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\"'\"'") + "'"
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
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"

        /** Open new session and switch to it (Termux convention value 0). */
        const val SESSION_ACTION_SWITCH_TO_NEW = 0
    }
}
