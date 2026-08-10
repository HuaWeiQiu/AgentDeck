package com.agentdeck.app.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

class CodexRpcTimeoutException(
    val method: String,
    val timeoutMillis: Long,
) : Exception(
    "Codex 请求 $method 在 ${timeoutMillis / 1_000} 秒内没有响应；" +
        "内嵌运行环境可能已被系统暂停，请重试连接",
)

internal sealed interface CodexSocketEvent {
    data class Text(val value: String) : CodexSocketEvent
    data class Disconnected(val message: String, val cause: Throwable? = null) : CodexSocketEvent
}

internal interface CodexRpcTransport : AutoCloseable {
    val events: Flow<CodexSocketEvent>
    fun send(text: String): Boolean
}

private class OkHttpCodexTransport(
    private val socket: WebSocket,
    eventChannel: Channel<CodexSocketEvent>,
) : CodexRpcTransport {
    override val events: Flow<CodexSocketEvent> = eventChannel.receiveAsFlow()

    override fun send(text: String): Boolean = socket.send(text)

    override fun close() {
        if (!socket.close(NORMAL_CLOSE_CODE, "AgentDeck closed")) {
            socket.cancel()
        }
    }

    companion object {
        private const val NORMAL_CLOSE_CODE = 1_000

        suspend fun connect(endpoint: CodexBridgeEndpoint): OkHttpCodexTransport {
            val opened = CompletableDeferred<WebSocket>()
            val eventChannel = Channel<CodexSocketEvent>(Channel.BUFFERED)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.toByteArray(StandardCharsets.UTF_8).size > CodexRpcClient.MAX_MESSAGE_BYTES) {
                        eventChannel.close(IllegalStateException("Codex 响应超过大小限制"))
                        webSocket.close(1_009, "message too large")
                        return
                    }
                    if (eventChannel.trySend(CodexSocketEvent.Text(text)).isFailure) {
                        eventChannel.close(IllegalStateException("Codex 消息过多，客户端无法继续处理"))
                        webSocket.close(1_013, "client overloaded")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    eventChannel.close(IllegalStateException("Codex 返回了不支持的二进制消息"))
                    webSocket.close(1_003, "binary messages are unsupported")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val error = IllegalStateException("Codex WebSocket 已关闭（$code）")
                    if (!opened.completeExceptionally(error)) {
                        deliverDisconnected(
                            eventChannel,
                            CodexSocketEvent.Disconnected(
                                reason.ifBlank { "Codex WebSocket 已关闭（$code）" },
                            ),
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    val detail = response?.let { "HTTP ${it.code}" }
                        ?: error.message
                        ?: "Codex WebSocket 连接失败"
                    if (!opened.completeExceptionally(IllegalStateException(detail, error))) {
                        deliverDisconnected(
                            eventChannel,
                            CodexSocketEvent.Disconnected(detail, error),
                        )
                    }
                }
            }
            val pendingSocket = CodexRpcClient.HTTP_CLIENT.newWebSocket(
                CodexRpcClient.endpointRequest(endpoint),
                listener,
            )
            return try {
                val socket = withTimeout(CodexRpcClient.CONNECT_TIMEOUT_MILLIS) { opened.await() }
                OkHttpCodexTransport(socket, eventChannel)
            } catch (_: TimeoutCancellationException) {
                pendingSocket.cancel()
                throw IllegalStateException("连接 Codex WebSocket 超时")
            } catch (error: Exception) {
                pendingSocket.cancel()
                throw error
            }
        }
    }
}

/**
 * OkHttp listener callbacks cannot suspend, so a full buffer cannot be awaited.
 * If the disconnected event does not fit, close the channel with the same error
 * instead: the consumer drains buffered messages first and then throws the close
 * cause, so the disconnect is never silently dropped.
 */
private fun deliverDisconnected(
    eventChannel: Channel<CodexSocketEvent>,
    event: CodexSocketEvent.Disconnected,
) {
    if (eventChannel.trySend(event).isFailure) {
        eventChannel.close(IllegalStateException(event.message, event.cause))
    }
}

class CodexRpcClient internal constructor(
    private val transport: CodexRpcTransport,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val writeMutex = Mutex()
    private val inbound = Channel<CodexInbound>(Channel.BUFFERED)
    private val closed = AtomicBoolean(false)

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
            ).put(
                "capabilities",
                JSONObject().put("experimentalApi", true),
            ),
        )
        notify("initialized", JSONObject())
    }

    suspend fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    ): JSONObject {
        check(!closed.get()) { "Codex 连接已关闭" }
        val id = requestIds.getAndIncrement()
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        try {
            return try {
                withTimeout(timeoutMillis) {
                    send(JSONObject().put("method", method).put("id", id).put("params", params))
                    result.await()
                }
            } catch (_: TimeoutCancellationException) {
                throw CodexRpcTimeoutException(method, timeoutMillis)
            }
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
        val text = payload.toString()
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "Codex 请求过大"
        }
        writeMutex.withLock {
            check(transport.send(text)) { "Codex WebSocket 无法发送消息" }
        }
    }

    private suspend fun readLoop() {
        try {
            transport.events.collect { event ->
                when (event) {
                    is CodexSocketEvent.Text -> handleMessage(JSONObject(event.value))
                    is CodexSocketEvent.Disconnected -> throw IllegalStateException(
                        event.message,
                        event.cause,
                    )
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                failPending(IllegalStateException("Codex 连接已断开", error))
                // Suspending send: the disconnect must always reach the collector,
                // even when the inbound buffer is momentarily full. close() cancels
                // this scope, so a dead collector cannot deadlock the loop.
                inbound.send(CodexInbound.Disconnected(error.message ?: "Codex 连接已断开"))
            }
        }
    }

    private suspend fun handleMessage(message: JSONObject) {
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
        if (!closed.compareAndSet(false, true)) return
        runCatching { transport.close() }
        failPending(IllegalStateException("Codex 连接已关闭"))
        inbound.close()
        scope.cancel()
    }

    companion object {
        internal const val MAX_MESSAGE_BYTES = 1024 * 1024
        internal const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        internal val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        suspend fun connect(endpoint: CodexBridgeEndpoint): CodexRpcClient =
            CodexRpcClient(OkHttpCodexTransport.connect(endpoint))

        internal fun endpointRequest(endpoint: CodexBridgeEndpoint): Request = Request.Builder()
            .url("ws://127.0.0.1:${endpoint.port}/")
            .header("Authorization", "Bearer ${endpoint.token}")
            .build()
    }
}
