package com.agentdeck.app.data.chat

import kotlinx.coroutines.CompletableDeferred
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

    private class FakeTransport : CodexRpcTransport {
        private val incoming = Channel<CodexSocketEvent>(Channel.BUFFERED)
        val sent = Channel<String>(Channel.BUFFERED)
        override val events: Flow<CodexSocketEvent> = incoming.receiveAsFlow()

        override fun send(text: String): Boolean = sent.trySend(text).isSuccess

        suspend fun receive(payload: JSONObject) {
            incoming.send(CodexSocketEvent.Text(payload.toString()))
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
