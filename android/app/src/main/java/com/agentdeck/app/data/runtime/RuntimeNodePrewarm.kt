package com.agentdeck.app.data.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot, low-priority background prewarm for already-installed CLIs.
 *
 * Seeds [NODE_COMPILE_CACHE] without leaving a warm agent process behind.
 * Safe to call from [com.agentdeck.app.di.ServiceLocator.warmUp]; failures are logged only.
 */
internal object RuntimeNodePrewarm {
    private const val TAG = "RuntimeNodePrewarm"
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleIfNeeded(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                runCatching { prewarmPi(app) }
                    .onFailure { Log.w(TAG, "pi prewarm skipped: ${it.message}") }
                runCatching { prewarmDsh(app) }
                    .onFailure { Log.w(TAG, "dsh prewarm skipped: ${it.message}") }
            }
        }
    }

    private suspend fun prewarmPi(context: Context) {
        val piPaths = PiRuntimePaths.shared(context)
        val codexPaths = EmbeddedRuntimePaths.shared(context)
        if (!piPaths.isReady() || !codexPaths.isReady()) return
        // Skip if cache already has files from a prior smoke/start.
        if (cacheLooksPopulated(piPaths.nodeCompileCache)) {
            Log.i(TAG, "pi compile cache already warm")
            return
        }
        piPaths.ensureLayout()
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-pi/node/bin:${'$'}PATH"
            export HOME="/opt/agentdeck-pi-home"
            export PI_HOME="/opt/agentdeck-pi-home"
            ${NodeStartupSupport.nodeOptionsExport(160)}
            ${NodeStartupSupport.shellExports(NodeStartupSupport.GUEST_PI_CACHE)}
            renice +15 ${'$'}${'$'} >/dev/null 2>&1 || true
            mkdir -p "${'$'}HOME"
            cd /opt/agentdeck-pi
            node /opt/agentdeck-pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js --help >/dev/null
            echo pi-prewarm-ok
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = 90_000L,
            workingDirectory = "/opt/agentdeck-pi",
            extraBinds = listOf(
                piPaths.cliRoot.absolutePath to "/opt/agentdeck-pi",
                piPaths.piHome.absolutePath to "/opt/agentdeck-pi-home",
            ),
        ).getOrElse {
            Log.w(TAG, "pi prewarm failed: ${it.message}")
            return
        }
        if (result.commandSucceeded) {
            Log.i(TAG, "pi prewarm ok")
        } else {
            Log.w(TAG, "pi prewarm exit=${result.exitCode}")
        }
    }

    private suspend fun prewarmDsh(context: Context) {
        val dshPaths = DshRuntimePaths.shared(context)
        val codexPaths = EmbeddedRuntimePaths.shared(context)
        if (!dshPaths.isReady() || !codexPaths.isReady()) return
        if (cacheLooksPopulated(dshPaths.nodeCompileCache)) {
            Log.i(TAG, "dsh compile cache already warm")
            return
        }
        dshPaths.ensureLayout()
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-dsh/node/bin:${'$'}PATH"
            ${NodeStartupSupport.nodeOptionsExport(160)}
            ${NodeStartupSupport.shellExports(NodeStartupSupport.GUEST_DSH_CACHE)}
            renice +15 ${'$'}${'$'} >/dev/null 2>&1 || true
            cd /opt/agentdeck-dsh
            node -e "require('node-pty'); require('/opt/agentdeck-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js'); console.log('dsh-prewarm-ok')"
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = 90_000L,
            workingDirectory = "/opt/agentdeck-dsh",
            extraBinds = listOf(dshPaths.cliRoot.absolutePath to "/opt/agentdeck-dsh"),
        ).getOrElse {
            Log.w(TAG, "dsh prewarm failed: ${it.message}")
            return
        }
        if (result.commandSucceeded) {
            Log.i(TAG, "dsh prewarm ok")
        } else {
            Log.w(TAG, "dsh prewarm exit=${result.exitCode}")
        }
    }

    private fun cacheLooksPopulated(dir: java.io.File): Boolean {
        if (!dir.isDirectory) return false
        val children = dir.listFiles() ?: return false
        return children.any { it.isFile || (it.isDirectory && (it.listFiles()?.isNotEmpty() == true)) }
    }
}
