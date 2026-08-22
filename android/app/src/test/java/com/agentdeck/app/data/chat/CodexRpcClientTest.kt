package com.agentdeck.app.data.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class CodexRpcClientTest {
    @Test
    fun `client initializes and handles both inbound message types over framed transport`() = runBlocking {
        val transport = FakeTransport()
        val response = CompletableDeferred<JSONObject>()
        val client = CodexRpcClient(transport)
        val server = launch {
            val initialize = JSONObject(transport.sent.receive())
            assertEquals("initialize", initialize.getString("method"))
            assertEquals(
                "agentdeck",
                initialize.getJSONObject("params").getJSONObject("clientInfo").getString("name"),
            )
            assertTrue(
                initialize.getJSONObject("params")
                    .getJSONObject("capabilities")
                    .getBoolean("experimentalApi"),
            )
            transport.receive(
                JSONObject()
                    .put("id", initialize.getLong("id"))
                    .put("result", JSONObject()),
            )

            assertEquals("initialized", JSONObject(transport.sent.receive()).getString("method"))
            transport.receive(
                JSONObject(
                    """{"method":"item/agentMessage/delta","params":{"itemId":"i1","delta":"hi"}}""",
                ),
            )
            transport.receive(
                JSONObject(
                    """{"id":"approval-1","method":"item/fileChange/requestApproval","params":{"itemId":"i2"}}""",
                ),
            )
            response.complete(JSONObject(transport.sent.receive()))
        }

        try {
            client.initialize("0.1.4-test")
            val notification = withTimeout(2_000) { client.events.first() }
            assertTrue(notification is CodexInbound.Notification)
            notification as CodexInbound.Notification
            assertEquals("item/agentMessage/delta", notification.method)

            val request = withTimeout(2_000) { client.events.first() }
            assertTrue(request is CodexInbound.ServerRequest)
            request as CodexInbound.ServerRequest
            client.respond(request.id, JSONObject().put("decision", "accept"))

            val approval = response.await()
            assertEquals("approval-1", approval.getString("id"))
            assertEquals("accept", approval.getJSONObject("result").getString("decision"))
        } finally {
            client.close()
            server.join()
        }
    }

    @Test
    fun `request timeout is a recoverable RPC error instead of coroutine cancellation`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)

        try {
            val requestReceived = launch {
                assertEquals("turn/start", JSONObject(transport.sent.receive()).getString("method"))
            }
            try {
                client.request("turn/start", timeoutMillis = 100)
                fail("request should time out")
            } catch (error: CodexRpcTimeoutException) {
                assertEquals("turn/start", error.method)
                assertEquals(100L, error.timeoutMillis)
                assertTrue(error.message.orEmpty().contains("没有响应"))
            }
            requestReceived.join()
            assertTrue("timeout must not cancel the caller", true)
        } finally {
            client.close()
        }
    }

    @Test
    fun `websocket endpoint is loopback only and uses bearer capability token`() {
        val token = "a".repeat(64)

        val request = CodexRpcClient.endpointRequest(CodexBridgeEndpoint(48_123, token, "abc123"))

        assertEquals("http", request.url.scheme)
        assertEquals("127.0.0.1", request.url.host)
        assertEquals(48_123, request.url.port)
        assertEquals("Bearer $token", request.header("Authorization"))
    }

    @Test
    fun `disconnect event is delivered even when inbound buffer is full`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)
        val overflow = Channel.BUFFERED + 10

        try {
            repeat(overflow) { index ->
                transport.receive(
                    JSONObject(
                        """{"method":"item/agentMessage/delta","params":{"itemId":"i$index","delta":"x"}}""",
                    ),
                )
            }
            transport.fail(IllegalStateException("socket lost"))

            val received = mutableListOf<CodexInbound>()
            withTimeout(2_000) {
                client.events.take(overflow + 1).toList(received)
            }

            assertEquals(overflow, received.count { it is CodexInbound.Notification })
            val last = received.last()
            assertTrue(last is CodexInbound.Disconnected)
            assertEquals("socket lost", (last as CodexInbound.Disconnected).message)
        } finally {
            client.close()
        }
    }

    @Test
    fun `handoff fence drains prior events and leaves later events for next owner`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)
        val firstOwnerEvents = mutableListOf<CodexInbound>()
        val drained = CompletableDeferred<Unit>()

        try {
            val firstOwner = launch {
                client.eventsUntilHandoff().toList(firstOwnerEvents)
            }
            transport.receive(
                JSONObject(
                    """{"method":"before/handoff","params":{}}""",
                ),
            )
            val handoff = client.tryEnqueueEventHandoff { drained.complete(Unit) }
                ?: error("handoff should fit in an otherwise empty queue")
            transport.receive(
                JSONObject(
                    """{"method":"after/handoff","params":{}}""",
                ),
            )

            withTimeout(1_000) { handoff.await() }
            firstOwner.join()
            assertTrue(drained.isCompleted)
            assertEquals(
                listOf("before/handoff"),
                firstOwnerEvents.filterIsInstance<CodexInbound.Notification>().map { it.method },
            )

            val nextOwner = withTimeout(1_000) { client.eventsUntilHandoff().first() }
            assertEquals("after/handoff", (nextOwner as CodexInbound.Notification).method)
        } finally {
            client.close()
        }
    }

    @Test
    fun `normal transport completion fails pending request immediately`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)
        try {
            val pending = async { runCatching { client.request("thread/list", timeoutMillis = 5_000) } }
            transport.sent.receive()
            transport.completeNormally()

            val result = withTimeout(1_000) { pending.await() }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("连接已断开"))
            assertTrue(withTimeout(1_000) { client.events.first() } is CodexInbound.Disconnected)
        } finally {
            client.close()
        }
    }

    @Test
    fun `malformed JSON disconnects and releases pending request`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)
        try {
            val pending = async { runCatching { client.request("model/list", timeoutMillis = 5_000) } }
            transport.sent.receive()
            transport.receiveRaw("{not-json")

            val result = withTimeout(1_000) { pending.await() }
            assertTrue(result.isFailure)
            assertTrue(withTimeout(1_000) { client.events.first() } is CodexInbound.Disconnected)
        } finally {
            client.close()
        }
    }

    @Test
    fun `late response after timeout is ignored and next request succeeds`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)
        try {
            val timedOut = async { runCatching { client.request("turn/start", timeoutMillis = 50) } }
            val first = JSONObject(transport.sent.receive())
            assertTrue(timedOut.await().exceptionOrNull() is CodexRpcTimeoutException)
            transport.receive(JSONObject().put("id", first.getLong("id")).put("result", JSONObject()))

            val next = async { client.request("thread/list", timeoutMillis = 1_000) }
            val second = JSONObject(transport.sent.receive())
            transport.receive(
                JSONObject().put("id", second.getLong("id")).put("result", JSONObject().put("ok", true)),
            )
            assertTrue(next.await().getBoolean("ok"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `send failure does not leave a pending request`() = runBlocking {
        val transport = FakeTransport(sendSucceeds = false)
        val client = CodexRpcClient(transport)
        try {
            assertTrue(runCatching { client.request("turn/start") }.isFailure)
            transport.sendSucceeds = true
            val request = async { client.request("thread/list", timeoutMillis = 1_000) }
            val payload = JSONObject(transport.sent.receive())
            transport.receive(
                JSONObject().put("id", payload.getLong("id")).put("result", JSONObject().put("ok", true)),
            )
            assertTrue(request.await().getBoolean("ok"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `close releases an active request and is idempotent`() = runBlocking {
        val transport = FakeTransport()
        val client = CodexRpcClient(transport)
        val request = async { runCatching { client.request("turn/start", timeoutMillis = 5_000) } }
        transport.sent.receive()

        client.close()
        client.close()

        val result = withTimeout(1_000) { request.await() }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("连接已关闭"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { client.request("thread/list") }
        }
        Unit
    }

    @Test
    fun `message limit check skips encoding for short text and still rejects oversized`() {
        // At or below length/3 chars can never exceed the byte budget.
        assertTrue(!exceedsMessageLimit("x".repeat(CodexRpcClient.MAX_MESSAGE_BYTES / 3)))

        // Near the threshold the exact UTF-8 size decides (CJK is 3 bytes per char).
        val oversizedCjk = "中".repeat(350_000) // ~1.05 MB UTF-8
        assertTrue(exceedsMessageLimit(oversizedCjk))

        val underLimitAscii = "x".repeat(500_000) // 0.5 MB UTF-8 despite long text
        assertTrue(!exceedsMessageLimit(underLimitAscii))
    }

    private class FakeTransport(
        var sendSucceeds: Boolean = true,
    ) : CodexRpcTransport {
        private val incoming = Channel<CodexSocketEvent>(Channel.BUFFERED)
        val sent = Channel<String>(Channel.BUFFERED)
        override val events: Flow<CodexSocketEvent> = incoming.receiveAsFlow()

        override fun send(text: String): Boolean = sendSucceeds && sent.trySend(text).isSuccess

        override fun tryEnqueueHandoff(event: CodexSocketEvent.Handoff): Boolean =
            incoming.trySend(event).isSuccess

        suspend fun receive(payload: JSONObject) {
            incoming.send(CodexSocketEvent.Text(payload.toString()))
        }

        suspend fun receiveRaw(payload: String) {
            incoming.send(CodexSocketEvent.Text(payload))
        }

        fun completeNormally() {
            incoming.close()
        }

        fun fail(cause: Throwable) {
            incoming.close(cause)
        }

        override fun close() {
            incoming.close()
            sent.close()
        }
    }
}
