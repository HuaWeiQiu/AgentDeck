package com.agentdeck.app.data.runtime

import android.content.Context
import android.util.Log
import com.agentdeck.app.domain.runtime.LoopbackWebPolicy
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class DshWebSession(
    val url: String,
    val port: Int,
    val pid: Long?,
    val managedProcess: Boolean,
)

/**
 * Starts or attaches to a loopback dsh Web UI.
 *
 * Managed launch runs Node + dsh **inside Codex PRoot** with
 * `runtimes/deepseek-harness` bound at `/opt/agentdeck-dsh` and `dsh-home` at
 * `/opt/agentdeck-dsh-home`. Cold start on phone can take 30–90s (loads a large
 * ESM tree under proot); health wait is sized for that.
 */
internal class DshWebSupervisor(
    context: Context,
    private val paths: DshRuntimePaths = DshRuntimePaths.shared(context),
    private val codexPaths: EmbeddedRuntimePaths = EmbeddedRuntimePaths.shared(context),
) {
    private val lock = Any()
    private val processRef = AtomicReference<Process?>(null)
    private val sessionRef = AtomicReference<DshWebSession?>(null)
    private val recentLogs = ArrayDeque<String>(LOG_RING)

    fun isInstalled(): Boolean = paths.isReady()

    fun usedBytes(): Long = paths.usedBytes()

    fun currentSession(): DshWebSession? = sessionRef.get()

    /**
     * Delete dsh chat sessions under DSH_HOME so the next page load starts empty.
     * Does not wipe credentials or settings.yaml.
     */
    fun clearChatSessions(): Result<Unit> = runCatching {
        val sessions = java.io.File(paths.dshHome, "sessions")
        if (sessions.isDirectory) {
            sessions.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    child.deleteRecursively()
                } else {
                    child.delete()
                }
            }
        }
        java.io.File(paths.dshHome, "storages/session_projcache.json").delete()
        Unit
    }

    suspend fun open(): Result<DshWebSession> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            sessionRef.get()?.let { existing ->
                if (isHealthy(existing.url)) return@withContext Result.success(existing)
                Log.i(TAG, "existing session unhealthy, restarting")
                stopLocked()
            }
        }
        if (paths.isReady() && codexPaths.isReady()) {
            return@withContext launchManaged()
        }
        val external = probeExternal(DEFAULT_PORT)
        if (external != null) {
            sessionRef.set(external)
            return@withContext Result.success(external)
        }
        val reason = when {
            !codexPaths.isReady() -> "请先准备 Codex 运行环境（dsh 复用同一套本机 Linux）。"
            paths.dshEntry.isFile && !paths.hasNodePtyNative() ->
                "dsh 组件不完整：缺少 node-pty 原生模块。请在「运行环境」再点一次安装（会自动编译）。"
            !paths.isReady() -> "请先在「运行环境」里准备 DeepSeek Harness。"
            else -> "无法启动 dsh。"
        }
        Result.failure(IllegalStateException(reason))
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private suspend fun launchManaged(): Result<DshWebSession> = withContext(Dispatchers.IO) {
        paths.ensureLayout()
        val port = allocatePort()
        // Official CLI: `dsh web` == `--profile web`; app flags are --host / --port.
        // Rejects 0.0.0.0 by design — loopback only.
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-dsh/node/bin:${'$'}PATH"
            export DSH_HOME="/opt/agentdeck-dsh-home"
            export HOME="/opt/agentdeck-dsh-home"
            export NODE_ENV=production
            # Phone extremes under PRoot: small heap, fewer libuv workers, quiet V8.
            export UV_THREADPOOL_SIZE=1
            ${NodeStartupSupport.nodeOptionsExport(160)}
            ${NodeStartupSupport.shellExports(NodeStartupSupport.GUEST_DSH_CACHE)}
            # Best-effort lower CPU contention vs UI thread (may be ignored).
            renice +10 ${'$'}${'$'} >/dev/null 2>&1 || true
            mkdir -p "${'$'}DSH_HOME"
            cd /opt/agentdeck-dsh
            exec node --max-old-space-size=160 \
              /opt/agentdeck-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js web \
              --host 127.0.0.1 --port $port
        """.trimIndent()
        clearLogs()
        val process = try {
            EmbeddedProotProcess(codexPaths).startExecutable(
                command = listOf("/usr/bin/bash", "-lc", script),
                workingDirectory = "/opt/agentdeck-dsh",
                extraBinds = listOf(
                    paths.cliRoot.absolutePath to "/opt/agentdeck-dsh",
                    paths.dshHome.absolutePath to "/opt/agentdeck-dsh-home",
                ),
            )
        } catch (error: Exception) {
            return@withContext Result.failure(
                IllegalStateException("无法启动 dsh：" + (error.message ?: "未知错误"), error),
            )
        }
        processRef.set(process)
        // Prefer the app UI: deprioritize proot/node tree when the API allows.
        processPidOrNull(process)?.let { pid ->
            runCatching {
                android.os.Process.setThreadPriority(
                    pid.toInt(),
                    android.os.Process.THREAD_PRIORITY_BACKGROUND,
                )
            }
        }
        drainStream(process, stdout = true)
        drainStream(process, stdout = false)

        val url = LoopbackWebPolicy.defaultDshUrl(port)
        Log.i(TAG, "waiting for dsh web at $url (cold start may take ~1 min)")
        val healthy = waitUntilHealthy(url, process, timeoutMs = HEALTH_WAIT_MS)
        if (!healthy) {
            val alive = process.isAlive
            val tail = snapshotLogs()
            if (!alive) {
                stopLocked()
                return@withContext Result.failure(
                    IllegalStateException(
                        "dsh 进程已退出。" + if (tail.isNotBlank()) " 详情：$tail" else " 请重试打开网页。",
                    ),
                )
            }
            // Still booting: do not kill — let a later open reuse if it becomes healthy,
            // but clear process bookkeeping only after hard kill for consistency.
            stopLocked()
            return@withContext Result.failure(
                IllegalStateException(
                    "dsh 冷启动较慢，健康检查超时。请再点一次「打开网页」。" +
                        if (tail.isNotBlank()) " 日志：$tail" else "",
                ),
            )
        }
        val session = DshWebSession(
            url = url,
            port = port,
            pid = processPidOrNull(process),
            managedProcess = true,
        )
        sessionRef.set(session)
        Log.i(TAG, "dsh web ready: $url")
        Result.success(session)
    }

    private fun probeExternal(port: Int): DshWebSession? {
        val url = LoopbackWebPolicy.defaultDshUrl(port)
        if (!LoopbackWebPolicy.isAllowedUrl(url)) return null
        if (!isHealthy(url)) return null
        return DshWebSession(url = url, port = port, pid = null, managedProcess = false)
    }

    private suspend fun waitUntilHealthy(
        url: String,
        process: Process,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (isHealthy(url)) return true
            if (!process.isAlive) return false
            delay(HEALTH_POLL_MS)
        }
        return isHealthy(url)
    }

    private fun isHealthy(url: String): Boolean {
        if (!LoopbackWebPolicy.isAllowedUrl(url)) return false
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_000
                readTimeout = 2_000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            try {
                val code = connection.responseCode
                // Accept any non-server-error while the SPA / static host is up.
                code in 200..499
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun allocatePort(): Int {
        ServerSocket(0).use { socket -> return socket.localPort }
    }

    private fun stopLocked() {
        sessionRef.set(null)
        val process = processRef.getAndSet(null)
        val rootPid = process?.let { processPidOrNull(it) }
        if (process != null) {
            // Prefer destroyForcibly — proot trees often ignore polite SIGTERM under Android.
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
        }
        // Walk /proc for leftover libproot/node belonging to this app uid and kill them.
        killOrphanDshProcesses(preferPid = rootPid)
    }

    /**
     * proot + node can outlive Process.destroy when the Java Process handle is only
     * the outer wrapper. Sweep our uid's matching cmdline entries.
     */
    private fun killOrphanDshProcesses(preferPid: Long?) {
        val candidates = LinkedHashSet<Int>()
        preferPid?.takeIf { it > 1 }?.let { candidates.add(it.toInt()) }
        val proc = java.io.File("/proc")
        val dirs = proc.listFiles() ?: return
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid <= 1) continue
            val cmdline = runCatching {
                java.io.File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            val hit =
                cmdline.contains("libproot.so") ||
                    cmdline.contains("/opt/agentdeck-dsh") ||
                    (cmdline.contains("node") && cmdline.contains("@deepseek-ai/dsh"))
            if (hit) candidates.add(pid)
        }
        for (pid in candidates) {
            runCatching { android.os.Process.sendSignal(pid, 9) }
            runCatching { android.os.Process.killProcess(pid) }
        }
    }

    private fun drainStream(process: Process, stdout: Boolean) {
        val stream = if (stdout) process.inputStream else process.errorStream
        val name = if (stdout) "dsh-web-stdout" else "dsh-web-stderr"
        Thread(
            {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                runCatching {
                    BufferedReader(InputStreamReader(stream), 2_048).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            rememberLog(line)
                        }
                    }
                }
            },
            name,
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun rememberLog(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        // Never keep lines that look like secrets.
        if (trimmed.contains("key", ignoreCase = true) &&
            (trimmed.contains("sk-") || trimmed.contains("api", ignoreCase = true))
        ) {
            return
        }
        synchronized(recentLogs) {
            if (recentLogs.size >= LOG_RING) recentLogs.removeFirst()
            recentLogs.addLast(trimmed.take(240))
        }
    }

    private fun clearLogs() = synchronized(recentLogs) { recentLogs.clear() }

    private fun snapshotLogs(): String = synchronized(recentLogs) {
        recentLogs.toList().takeLast(6).joinToString(" · ")
    }

    companion object {
        const val DEFAULT_PORT = 3080
        private const val TAG = "AgentDeckRuntime"
        private const val LOG_RING = 12
        /** Cold ESM load under proot on phone often needs 30–90s. */
        private const val HEALTH_WAIT_MS = 90_000L
        /** Faster first-paint detection once node starts answering. */
        private const val HEALTH_POLL_MS = 250L

        private fun processPidOrNull(process: Process): Long? = runCatching {
            val method = Process::class.java.methods.firstOrNull {
                it.name == "pid" && it.parameterCount == 0
            } ?: return null
            (method.invoke(process) as? Number)?.toLong()
        }.getOrNull()
    }
}
