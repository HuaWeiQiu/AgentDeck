package com.agentdeck.app.data.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

class CodexRpcClientTest {
    @Test
    fun `client authenticates initializes and handles both inbound message types`() = runBlocking {
        val token = "t".repeat(43)
        val responseFuture = CompletableFuture<JSONObject>()
        val server = ServerSocket(0)
        val serverThread = thread(name = "fake-codex-app-server") {
            server.use {
                val connection = it.accept()
                connection.use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    assertEquals(token, JSONObject(input.readLine()).getString("token"))
                    output.appendLine("""{"ok":true}""")
                    output.flush()

                    val initialize = JSONObject(input.readLine())
                    assertEquals("initialize", initialize.getString("method"))
                    assertEquals("agentdeck", initialize.getJSONObject("params")
                        .getJSONObject("clientInfo").getString("name"))
                    output.appendLine(
                        JSONObject()
                            .put("id", initialize.getLong("id"))
                            .put("result", JSONObject())
                            .toString(),
                    )
                    output.flush()

                    assertEquals("initialized", JSONObject(input.readLine()).getString("method"))
                    output.appendLine(
                        """{"method":"item/agentMessage/delta","params":{"itemId":"i1","delta":"hi"}}""",
                    )
                    output.appendLine(
                        """{"id":"approval-1","method":"item/fileChange/requestApproval","params":{"itemId":"i2"}}""",
                    )
                    output.flush()
                    responseFuture.complete(JSONObject(input.readLine()))
                }
            }
        }

        val client = CodexRpcClient.connect(CodexBridgeEndpoint(server.localPort, token))
        try {
            client.initialize("0.1.3-test")
            val notification = withTimeout(2_000) { client.events.first() }
            assertTrue(notification is CodexInbound.Notification)
            notification as CodexInbound.Notification
            assertEquals("item/agentMessage/delta", notification.method)

            val request = withTimeout(2_000) { client.events.first() }
            assertTrue(request is CodexInbound.ServerRequest)
            request as CodexInbound.ServerRequest
            client.respond(request.id, JSONObject().put("decision", "accept"))

            val response = responseFuture.get()
            assertEquals("approval-1", response.getString("id"))
            assertEquals("accept", response.getJSONObject("result").getString("decision"))
        } finally {
            client.close()
            serverThread.join(2_000)
        }
    }
}
