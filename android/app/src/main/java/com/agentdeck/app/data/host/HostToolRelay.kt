package com.agentdeck.app.data.host

import android.os.FileObserver
import android.system.Os
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.domain.host.HostAuthToken
import com.agentdeck.app.domain.host.HostToolBroker
import com.agentdeck.app.domain.host.HostToolCall
import com.agentdeck.app.domain.host.HostToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Guest CLI (`agentdeck-host`) ↔ Android [HostToolBroker] 文件 IPC。
 * 请求：`state/host-req/<id>.json`  响应：`state/host-res/<id>.json`
 * 会话：`state/host-session.json`（仅内存 token，短时文件 0600）
 */
class HostToolRelay(
    context: android.content.Context,
    private val broker: HostToolBroker,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val paths = EmbeddedRuntimePaths(app)
    private val reqDir = File(paths.stateDir, "host-req")
    private val resDir = File(paths.stateDir, "host-res")
    private val sessionFile = File(paths.stateDir, "host-session.json")
    private val mutex = Mutex()
    private var pollJob: Job? = null
    private var observer: FileObserver? = null

    data class BoundSession(
        val conversationId: String,
        val instanceId: String,
        val token: HostAuthToken,
    )

    @Volatile
    private var session: BoundSession? = null

    fun bind(conversationId: String, instanceId: String): BoundSession {
        paths.ensureHostLayout()
        reqDir.mkdirs()
        resDir.mkdirs()
        ensureGuestCli()
        val token = broker.mintToken(conversationId, instanceId)
        val bound = BoundSession(conversationId, instanceId, token)
        session = bound
        writeSession(bound)
        writeMirrorReadme()
        startWatching()
        return bound
    }

    private fun writeMirrorReadme() {
        runCatching {
            paths.ensureHostLayout()
            val mirror = File(paths.projectsHome, "host-mirror")
            mirror.mkdirs()
            val readme = File(mirror, "README.txt")
            readme.writeText(
                """
                这是 Runtime 内的「真实目录镜像」，不是 Android 文件夹的实时挂载。

                真实文件夹：设置 → 本机工作区（SAF 授权）
                访问方式：
                  agentdeck-host workspace.list --path .
                  agentdeck-host workspace.pull --path 相对路径
                  # 文件会出现在本目录下同名路径
                  agentdeck-host workspace.push --path 相对路径

                Codex 的默认 cwd（/root/projects/...）与真实文件夹是分开的。
                """.trimIndent() + "\n",
            )
        }
    }

    /** 覆盖安装/旧 Runtime 补齐 guest CLI，不改动已验证 marker。 */
    private fun ensureGuestCli() {
        if (!paths.isReady()) return
        val dest = File(paths.activeRootfs, "usr/local/bin/agentdeck-host")
        if (dest.isFile && dest.canExecute()) return
        runCatching {
            dest.parentFile?.mkdirs()
            app.assets.open("wrappers/agentdeck-host").use { input ->
                dest.outputStream().use(input::copyTo)
            }
            Os.chmod(dest.absolutePath, 0b111101101)
        }
    }

    fun unbind() {
        session = null
        stopWatching()
        runCatching { sessionFile.delete() }
        reqDir.listFiles()?.forEach { runCatching { it.delete() } }
        resDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun writeSession(bound: BoundSession) {
        val json = JSONObject()
            .put("conversationId", bound.conversationId)
            .put("instanceId", bound.instanceId)
            .put("token", bound.token.value)
            .put("expiresAtEpochMs", bound.token.expiresAtEpochMs)
            .toString()
        sessionFile.writeText(json)
        runCatching { Os.chmod(sessionFile.absolutePath, 0b110000000) }
    }

    private fun startWatching() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            // 初始扫一次 + 周期扫，兼容部分 OEM 上 FileObserver 不可靠
            while (isActive && session != null) {
                drainRequests()
                delay(150)
            }
        }
        @Suppress("DEPRECATION")
        observer = object : FileObserver(reqDir.absolutePath, CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrBlank() || !path.endsWith(".json") || path.startsWith(".")) return
                scope.launch(Dispatchers.IO) { drainRequests() }
            }
        }.also { it.startWatching() }
    }

    private fun stopWatching() {
        pollJob?.cancel()
        pollJob = null
        observer?.stopWatching()
        observer = null
    }

    private suspend fun drainRequests() = mutex.withLock {
        val files = reqDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && !it.name.startsWith(".") }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (file in files) {
            processFile(file)
        }
    }

    private suspend fun processFile(file: File) {
        val raw = runCatching { file.readText() }.getOrNull() ?: return
        runCatching { file.delete() }
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = obj.optString("id").takeIf { it.matches(ID_PATTERN) } ?: return
        val tool = obj.optString("tool")
        val conversationId = obj.optString("conversationId")
        val instanceId = obj.optString("instanceId")
        val tokenValue = obj.optString("token")
        val argsJson = obj.optJSONObject("args") ?: JSONObject()
        val args = buildMap {
            argsJson.keys().forEach { key ->
                val value = argsJson.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    put(key, value.toString())
                }
            }
        }
        val bound = session
        val result = if (bound == null) {
            HostToolResult.Denied("host_session_inactive", "宿主工作区会话未激活")
        } else {
            val token = HostAuthToken(
                value = tokenValue,
                conversationId = conversationId,
                instanceId = instanceId,
                expiresAtEpochMs = bound.token.expiresAtEpochMs,
            )
            broker.invoke(
                HostToolCall(
                    conversationId = conversationId,
                    instanceId = instanceId,
                    tool = tool,
                    args = args,
                    auth = token,
                ),
            )
        }
        writeResponse(id, result)
    }

    private fun writeResponse(id: String, result: HostToolResult) {
        val payload = JSONObject()
        when (result) {
            is HostToolResult.Ok -> {
                payload.put("outcome", "ok")
                payload.put("truncated", result.truncated)
                val map = JSONObject()
                result.payload.forEach { (k, v) -> map.put(k, v) }
                payload.put("payload", map)
            }
            is HostToolResult.Denied -> {
                payload.put("outcome", "denied")
                payload.put("code", result.code)
                payload.put("userMessage", result.userMessage)
            }
            is HostToolResult.Error -> {
                payload.put("outcome", "error")
                payload.put("code", result.code)
                payload.put("userMessage", result.userMessage)
            }
        }
        val out = File(resDir, "$id.json")
        val tmp = File(resDir, ".$id.json.tmp")
        tmp.writeText(payload.toString())
        runCatching { Os.chmod(tmp.absolutePath, 0b110000000) }
        tmp.renameTo(out)
    }

    companion object {
        private val ID_PATTERN = Regex("[a-f0-9]{8,64}")
    }
}
