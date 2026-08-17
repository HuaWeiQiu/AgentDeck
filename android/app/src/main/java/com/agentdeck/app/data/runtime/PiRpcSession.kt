package com.agentdeck.app.data.runtime

import android.content.Context
import android.util.Log
import com.agentdeck.app.domain.model.ProviderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Long-lived [pi --mode rpc] under Codex PRoot.
 *
 * Protocol: LF-delimited JSONL on stdin/stdout (see pi docs/rpc.md).
 * Model/key come from AgentDeck「模型服务」Chat Completions profiles —
 * same vault as Codex, projected into pi-home models.json at start.
 */
internal class PiRpcSession(
    context: Context,
    private val vault: com.agentdeck.app.data.secure.ProviderCredentialVault,
    private val piPaths: PiRuntimePaths = PiRuntimePaths.shared(context),
    private val codexPaths: EmbeddedRuntimePaths = EmbeddedRuntimePaths.shared(context),
) {
    private val mutex = Mutex()
    private val processRef = AtomicReference<Process?>(null)
    private val writerRef = AtomicReference<BufferedWriter?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var activeBinding: Binding? = null

    private val readyState = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = readyState.asStateFlow()

    private val events = MutableSharedFlow<PiRpcEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val eventFlow: SharedFlow<PiRpcEvent> = events.asSharedFlow()

    data class Binding(
        val profileId: String,
        val profileName: String,
        val modelId: String,
        val baseUrl: String,
    )

    fun currentBinding(): Binding? = activeBinding

    /**
     * Start (or restart) pi RPC with an AgentDeck Chat Completions profile.
     * Reuses the process when alive; switches model via set_model when only the
     * model/profile binding changes (same base URL family still needs config write).
     */
    suspend fun ensureStarted(
        profile: ProviderProfile,
        modelId: String,
    ): Result<Unit> = mutex.withLock {
        val desiredModel = modelId.ifBlank { profile.defaultModel }
        val alive = processRef.get()?.isAlive == true && readyState.value
        val sameBinding = alive &&
            activeBinding?.profileId == profile.id &&
            activeBinding?.modelId == desiredModel
        if (sameBinding) {
            return@withLock Result.success(Unit)
        }
        val applied = try {
            PiProviderConfig.applyProfile(
                piHome = piPaths.piHome,
                profile = profile,
                modelId = desiredModel,
                vault = vault,
            )
        } catch (error: Exception) {
            return@withLock Result.failure(
                IllegalStateException(error.message ?: "无法写入 pi 模型配置", error),
            )
        }
        // Hot path: process already up — only rebind model when possible.
        if (alive && activeBinding != null) {
            val setOk = runCatching {
                requestLocked(
                    JSONObject()
                        .put("type", "set_model")
                        .put("provider", PiProviderConfig.PROVIDER_ID)
                        .put("modelId", applied.modelId),
                    timeoutMs = 10_000L,
                )
            }.getOrNull()
            if (setOk != null && setOk.optBoolean("success", true)) {
                activeBinding = Binding(
                    profileId = profile.id,
                    profileName = profile.name,
                    modelId = applied.modelId,
                    baseUrl = applied.baseUrl,
                )
                Log.i(TAG, "pi set_model hot profile=${profile.name} model=${applied.modelId}")
                return@withLock Result.success(Unit)
            }
            Log.w(TAG, "pi set_model failed; cold restart")
        }
        stopLocked()
        if (!piPaths.isReady()) {
            return@withLock Result.failure(IllegalStateException("请先安装 pi（设置 → 运行环境）"))
        }
        if (!codexPaths.isReady()) {
            return@withLock Result.failure(IllegalStateException("请先准备 Codex 运行环境（pi 复用同一套 Linux）"))
        }
        piPaths.ensureLayout()
        val apiKey = try {
            PiProviderConfig.loadApiKey(vault, applied.credentialRef)
        } catch (error: Exception) {
            return@withLock Result.failure(
                IllegalStateException(error.message ?: "无法读取 API Key", error),
            )
        }
        val guestEnv = mapOf(PiProviderConfig.ENV_API_KEY to apiKey)
        val modelArgs =
            " --provider ${PiProviderConfig.PROVIDER_ID} --model ${applied.modelId}"
        // Omit --no-session so pi may persist under PI_HOME; UI also keeps PiChatHistoryStore.
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-pi/node/bin:${'$'}PATH"
            export HOME="/opt/agentdeck-pi-home"
            export PI_HOME="/opt/agentdeck-pi-home"
            export NODE_ENV=production
            export UV_THREADPOOL_SIZE=1
            ${NodeStartupSupport.nodeOptionsExport(160)}
            ${NodeStartupSupport.shellExports(NodeStartupSupport.GUEST_PI_CACHE)}
            export AI_AGENT=pi
            export PI_CODING_AGENT=true
            renice +10 ${'$'}${'$'} >/dev/null 2>&1 || true
            mkdir -p "${'$'}HOME" "${'$'}HOME/.pi/agent" "${'$'}HOME/sessions"
            cd /opt/agentdeck-pi
            # RPC: models from AgentDeck Chat Completions profile → ~/.pi/agent/models.json
            exec node --max-old-space-size=160 \
              /opt/agentdeck-pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js \
              --mode rpc$modelArgs
        """.trimIndent()
        val process = try {
            EmbeddedProotProcess(codexPaths).startExecutable(
                command = listOf("/usr/bin/bash", "-lc", script),
                workingDirectory = "/opt/agentdeck-pi",
                guestEnvironment = guestEnv,
                extraBinds = listOf(
                    piPaths.cliRoot.absolutePath to "/opt/agentdeck-pi",
                    piPaths.piHome.absolutePath to "/opt/agentdeck-pi-home",
                ),
            )
        } catch (error: Exception) {
            return@withLock Result.failure(
                IllegalStateException("无法启动 pi：" + (error.message ?: "未知错误"), error),
            )
        }
        processRef.set(process)
        val writer = BufferedWriter(
            OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8),
            8 * 1024,
        )
        writerRef.set(writer)
        readerJob = scope.launch { readLoop(process) }
        // Drain stderr so the pipe never blocks.
        scope.launch {
            runCatching {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) Log.w(TAG, "pi-stderr: ${line.take(400)}")
                    }
                }
            }
        }
        // Wait until get_state succeeds (process accepted RPC).
        // Fast first probes, then back off — avoids a fixed 400ms tax on warm disks.
        val ok = withTimeoutOrNull(START_TIMEOUT_MS) {
            var pauseMs = 40L
            while (isActive) {
                if (processRef.get()?.isAlive != true) return@withTimeoutOrNull false
                val resp = requestLocked(
                    JSONObject().put("type", "get_state"),
                    timeoutMs = 8_000L,
                )
                if (resp != null && resp.optBoolean("success", false)) {
                    readyState.value = true
                    return@withTimeoutOrNull true
                }
                delay(pauseMs)
                pauseMs = (pauseMs * 2).coerceAtMost(200L)
            }
            false
        } == true
        if (!ok) {
            val alive = process.isAlive
            stopLocked()
            return@withLock Result.failure(
                IllegalStateException(
                    if (!alive) {
                        "pi 进程已退出。请确认已安装并可在运行环境里「验证」。"
                    } else {
                        "pi RPC 启动超时。请重试。"
                    },
                ),
            )
        }
        runCatching {
            requestLocked(
                JSONObject()
                    .put("type", "set_model")
                    .put("provider", PiProviderConfig.PROVIDER_ID)
                    .put("modelId", applied.modelId),
                timeoutMs = 10_000L,
            )
        }
        activeBinding = Binding(
            profileId = profile.id,
            profileName = profile.name,
            modelId = applied.modelId,
            baseUrl = applied.baseUrl,
        )
        Log.i(TAG, "pi RPC ready profile=${profile.name} model=${applied.modelId}")
        Result.success(Unit)
    }

    suspend fun prompt(message: String): Result<Unit> = mutex.withLock {
        if (processRef.get()?.isAlive != true || !readyState.value) {
            return@withLock Result.failure(IllegalStateException("pi 未就绪"))
        }
        val id = "p-" + UUID.randomUUID().toString().take(8)
        val payload = JSONObject()
            .put("id", id)
            .put("type", "prompt")
            .put("message", message)
        val resp = requestLocked(payload, timeoutMs = 15_000L)
            ?: return@withLock Result.failure(IllegalStateException("pi 未响应 prompt"))
        if (!resp.optBoolean("success", false)) {
            val err = resp.optString("error").ifBlank {
                resp.optJSONObject("data")?.toString().orEmpty()
            }.ifBlank { resp.toString() }
            return@withLock Result.failure(IllegalStateException(err.take(400)))
        }
        Result.success(Unit)
    }

    suspend fun abort(): Result<Unit> = mutex.withLock {
        if (processRef.get()?.isAlive != true) return@withLock Result.success(Unit)
        val resp = requestLocked(JSONObject().put("type", "abort"), timeoutMs = 8_000L)
        if (resp != null && !resp.optBoolean("success", true)) {
            Result.failure(IllegalStateException(resp.toString().take(300)))
        } else {
            Result.success(Unit)
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock { stopLocked() }
        }
    }

    private fun stopLocked() {
        readyState.value = false
        activeBinding = null
        readerJob?.cancel()
        readerJob = null
        writerRef.getAndSet(null)?.let { w ->
            runCatching { w.close() }
        }
        val process = processRef.getAndSet(null) ?: return
        process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)
        // Best-effort: kill leftover pi node under our tree
        runCatching {
            val proc = java.io.File("/proc")
            proc.listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                val cmdline = runCatching {
                    java.io.File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                }.getOrNull() ?: return@forEach
                if (cmdline.contains("pi-coding-agent") && cmdline.contains("--mode rpc")) {
                    runCatching { android.os.Process.killProcess(pid) }
                }
            }
        }
    }

    private suspend fun requestLocked(payload: JSONObject, timeoutMs: Long): JSONObject? {
        val id = payload.optString("id").ifBlank {
            val gen = "r-" + UUID.randomUUID().toString().take(8)
            payload.put("id", gen)
            gen
        }
        val waiter = CompletableResponse(id)
        pending[id] = waiter
        writeLine(payload.toString())
        return withTimeoutOrNull(timeoutMs) { waiter.await() }.also {
            pending.remove(id)
        }
    }

    private fun writeLine(line: String) {
        val writer = writerRef.get()
            ?: error("pi stdin 不可用")
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableResponse>()

    private suspend fun readLoop(process: Process) {
        val reader = BufferedReader(
            InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
            16 * 1024,
        )
        try {
            while (process.isAlive) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (line.isEmpty()) continue
                // Strict JSONL: strip optional CR
                val raw = if (line.endsWith("\r")) line.dropLast(1) else line
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                if (obj == null) {
                    Log.w(TAG, "non-json: ${raw.take(200)}")
                    continue
                }
                val type = obj.optString("type")
                if (type == "response") {
                    val id = obj.optString("id")
                    if (id.isNotBlank()) {
                        pending[id]?.complete(obj)
                    }
                    continue
                }
                events.emit(PiRpcEvent.fromJson(obj))
            }
        } catch (error: Exception) {
            Log.w(TAG, "pi read loop ended: ${error.message}")
        } finally {
            readyState.value = false
            events.emit(PiRpcEvent.ProcessEnded)
        }
    }

    private class CompletableResponse(val id: String) {
        private val flow = MutableStateFlow<JSONObject?>(null)
        fun complete(value: JSONObject) {
            flow.value = value
        }

        suspend fun await(): JSONObject {
            while (true) {
                flow.value?.let { return it }
                delay(20)
            }
        }
    }

    companion object {
        private const val TAG = "PiRpcSession"
        private const val START_TIMEOUT_MS = 90_000L
    }
}

/** Subset of pi RPC stream events we render in the chat shell. */
sealed class PiRpcEvent {
    data class TextDelta(val delta: String) : PiRpcEvent()
    data class TextEnd(val content: String) : PiRpcEvent()
    data class ToolStart(val name: String, val detail: String) : PiRpcEvent()
    data class ToolEnd(val name: String, val ok: Boolean) : PiRpcEvent()
    data class TurnEnd(val summary: String?) : PiRpcEvent()
    data class AgentEnd(val summary: String?) : PiRpcEvent()
    data class Error(val message: String) : PiRpcEvent()
    data class Raw(val type: String, val json: String) : PiRpcEvent()
    data object ProcessEnded : PiRpcEvent()

    companion object {
        fun fromJson(obj: JSONObject): PiRpcEvent {
            return when (val type = obj.optString("type")) {
                "message_update" -> {
                    val ev = obj.optJSONObject("assistantMessageEvent") ?: return Raw(type, obj.toString())
                    when (ev.optString("type")) {
                        "text_delta" -> TextDelta(ev.optString("delta"))
                        "text_end" -> TextEnd(ev.optString("content"))
                        else -> Raw(type, obj.toString().take(500))
                    }
                }
                "tool_execution_start" -> ToolStart(
                    name = obj.optString("toolName").ifBlank { obj.optString("name") }.ifBlank { "tool" },
                    detail = obj.optString("args").ifBlank { obj.optString("input") }.take(200),
                )
                "tool_execution_end" -> ToolEnd(
                    name = obj.optString("toolName").ifBlank { obj.optString("name") }.ifBlank { "tool" },
                    ok = obj.optBoolean("success", true) && !obj.has("error"),
                )
                "turn_end" -> TurnEnd(obj.optString("reason").takeIf { it.isNotBlank() })
                "agent_end" -> AgentEnd(null)
                "error" -> Error(obj.optString("message").ifBlank { obj.toString() }.take(400))
                else -> Raw(type, obj.toString().take(400))
            }
        }
    }
}
