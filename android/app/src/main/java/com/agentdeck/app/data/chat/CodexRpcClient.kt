package com.agentdeck.app.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface CodexInbound {
    data class Notification(val method: String, val params: JSONObject) : CodexInbound
    data class ServerRequest(
        val id: RpcRequestId,
        val method: String,
        val params: JSONObject,
    ) : CodexInbound

    data class Disconnected(val message: String) : CodexInbound
}

sealed interface RpcRequestId {
    data class Number(val value: Long) : RpcRequestId
    data class Text(val value: String) : RpcRequestId

    fun jsonValue(): Any = when (this) {
        is Number -> value
        is Text -> value
    }

    companion object {
        fun from(value: Any): RpcRequestId = when (value) {
            is kotlin.Number -> Number(value.toLong())
            is String -> Text(value)
            else -> error("不支持的 app-server request id")
        }
    }
}

class CodexRpcException(
    val code: Int,
    override val message: String,
) : Exception(message)

class CodexRpcClient private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val writeMutex = Mutex()
    private val inbound = Channel<CodexInbound>(Channel.BUFFERED)
    private var closed = false

    val events: Flow<CodexInbound> = inbound.receiveAsFlow()

    init {
        scope.launch { readLoop() }
    }

    suspend fun initialize(version: String) {
        request(
            "initialize",
            JSONObject().put(
                "clientInfo",
                JSONObject()
                    .put("name", "agentdeck")
                    .put("title", "AgentDeck")
                    .put("version", version),
            ),
        )
        notify("initialized", JSONObject())
    }

    suspend fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): JSONObject {
        check(!closed) { "Codex 连接已关闭" }
        val id = requestIds.getAndIncrement()
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        try {
            send(JSONObject().put("method", method).put("id", id).put("params", params))
            return withTimeout(timeoutMillis) { result.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JSONObject = JSONObject()) {
        send(JSONObject().put("method", method).put("params", params))
    }

    suspend fun respond(id: RpcRequestId, result: JSONObject) {
        send(JSONObject().put("id", id.jsonValue()).put("result", result))
    }

    suspend fun respondUnsupported(id: RpcRequestId, method: String) {
        send(
            JSONObject()
                .put("id", id.jsonValue())
                .put(
                    "error",
                    JSONObject().put("code", -32601).put("message", "Unsupported request: $method"),
                ),
        )
    }

    private suspend fun send(payload: JSONObject) {
        val bytes = (payload.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_LINE_BYTES) { "Codex 请求过大" }
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                output.write(bytes)
                output.flush()
            }
        }
    }

    private suspend fun readLoop() {
        try {
            while (!closed) {
                val message = readJsonLine(input)
                val method = message.optString("method").takeIf(String::isNotBlank)
                val rawId = message.opt("id")?.takeUnless { it == JSONObject.NULL }
                if (method != null && rawId != null) {
                    inbound.send(
                        CodexInbound.ServerRequest(
                            RpcRequestId.from(rawId),
                            method,
                            message.optJSONObject("params") ?: JSONObject(),
                        ),
                    )
                } else if (method != null) {
                    inbound.send(
                        CodexInbound.Notification(
                            method,
                            message.optJSONObject("params") ?: JSONObject(),
                        ),
                    )
                } else if (rawId is kotlin.Number) {
                    completeResponse(rawId.toLong(), message)
                }
            }
        } catch (error: Exception) {
            if (!closed) {
                failPending(IllegalStateException("Codex 连接已断开", error))
                inbound.trySend(CodexInbound.Disconnected(error.message ?: "Codex 连接已断开"))
            }
        }
    }

    private fun completeResponse(id: Long, message: JSONObject) {
        val deferred = pending[id] ?: return
        val error = message.optJSONObject("error")
        if (error != null) {
            deferred.completeExceptionally(
                CodexRpcException(error.optInt("code", -32_000), error.optString("message", "Codex 请求失败")),
            )
            return
        }
        val result = message.optJSONObject("result")
        if (result == null) {
            deferred.completeExceptionally(IllegalStateException("Codex 响应缺少 result"))
        } else {
            deferred.complete(result)
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        failPending(IllegalStateException("Codex 连接已关闭"))
        inbound.close()
        scope.cancel()
    }

    companion object {
        private const val MAX_LINE_BYTES = 1024 * 1024
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val AUTH_TIMEOUT_MILLIS = 10_000
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L

        suspend fun connect(endpoint: CodexBridgeEndpoint): CodexRpcClient = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1", endpoint.port), CONNECT_TIMEOUT_MILLIS)
                socket.soTimeout = AUTH_TIMEOUT_MILLIS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val auth = (JSONObject().put("token", endpoint.token).toString() + "\n")
                    .toByteArray(StandardCharsets.UTF_8)
                output.write(auth)
                output.flush()
                val response = readJsonLine(input)
                check(response.optBoolean("ok")) { "Codex 聊天桥鉴权失败" }
                socket.soTimeout = 0
                CodexRpcClient(socket, input, output)
            } catch (error: Exception) {
                runCatching { socket.close() }
                throw error
            }
        }

        internal fun readJsonLine(input: BufferedInputStream): JSONObject {
            val bytes = ArrayList<Byte>(256)
            while (bytes.size <= MAX_LINE_BYTES) {
                val next = input.read()
                if (next == -1) error("Codex 连接已关闭")
                if (next == '\n'.code) {
                    val payload = ByteArray(bytes.size) { index -> bytes[index] }
                    return JSONObject(String(payload, StandardCharsets.UTF_8))
                }
                bytes += next.toByte()
            }
            error("Codex 响应超过大小限制")
        }
    }
}
