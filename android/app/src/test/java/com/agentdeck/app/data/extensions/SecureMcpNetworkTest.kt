package com.agentdeck.app.data.extensions

import com.agentdeck.app.data.secure.ExtensionCredentialVault
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket

class SecureMcpNetworkTest {
    @Test
    fun `proxy forwards mcp payload and adds vault bearer without exposing it in url`() {
        val server = MockWebServer()
        val tls = configureTls(server)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"),
        )
        server.start()
        val vault = MemoryExtensionVault("extcred_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "private-token")
        val proxy = SecureMcpProxy(
            upstream = server.url("/mcp"),
            credentialVault = vault,
            credentialRef = vault.ref,
            client = tls,
        )
        try {
            assertTrue("private-token" !in proxy.url)
            val response = OkHttpClient().newCall(
                Request.Builder()
                    .url(proxy.url)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute()
            response.use {
                assertEquals(200, it.code)
                assertTrue(it.body?.string().orEmpty().contains("result"))
            }
            val forwarded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer private-token", forwarded.getHeader("Authorization"))
            assertEquals("/mcp", forwarded.path)
        } finally {
            proxy.close()
            server.shutdown()
        }
    }

    @Test
    fun `proxy keeps the session credential after settings replace the vault entry`() {
        val server = MockWebServer()
        val tls = configureTls(server)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        val vault = MemoryExtensionVault("extcred_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "old-token")
        val proxy = SecureMcpProxy(server.url("/mcp"), vault, vault.ref, tls)
        try {
            vault.delete(requireNotNull(vault.ref))
            OkHttpClient().newCall(
                Request.Builder()
                    .url(proxy.url)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().close()

            assertEquals("Bearer old-token", server.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Authorization"))
        } finally {
            proxy.close()
            server.shutdown()
        }
    }

    @Test
    fun `proxy rejects non https upstream and discovery parses json and sse`() {
        val server = MockWebServer().apply { start() }
        try {
            assertTrue(
                runCatching {
                    SecureMcpProxy(server.url("/mcp"), MemoryExtensionVault(null, null), null)
                }.isFailure,
            )
        } finally {
            server.shutdown()
        }

        val json = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}"
        assertEquals(7, RemoteMcpToolDiscovery.parseMcpResponse(json, "application/json", 7).getInt("id"))
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{}}\n\n"
        assertEquals(8, RemoteMcpToolDiscovery.parseMcpResponse(sse, "text/event-stream", 8).getInt("id"))
        assertTrue(runCatching { RemoteMcpToolDiscovery.parseMcpResponse(json, null, 9) }.isFailure)
        assertSame(Proxy.NO_PROXY, secureMcpHttpClient().proxy)
    }

    @Test
    fun `sse discovery returns after the matching event without waiting for eof`() {
        val pipe = Pipe(1_024L)
        val sink = pipe.sink.buffer()
        val source = pipe.source.buffer()
        try {
            sink.writeUtf8("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{}}\n\n")
            sink.flush()

            assertEquals(
                8,
                RemoteMcpToolDiscovery.parseMcpResponse(source, "text/event-stream", 8).getInt("id"),
            )
        } finally {
            sink.close()
            source.close()
        }
    }

    @Test
    fun `discovery negotiates protocol and follows bounded tool pagination`() {
        val server = MockWebServer()
        val client = configureTls(server, "mcp.example")
            .newBuilder()
            .dns(LoopbackDns)
            .build()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("MCP-Session-Id", "session-1")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"read"}],"nextCursor":"page-2"}}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"write"}]}}""",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val endpoint = server.url("/mcp").newBuilder().host("mcp.example").build().toString()
            val tools = RemoteMcpToolDiscovery(
                policy = com.agentdeck.app.domain.extensions.ExtensionPolicy(2),
                client = client,
            ).discover(endpoint)

            assertEquals(listOf("read", "write"), tools.map { it.name })
            val requests = List(5) { server.takeRequest(2, TimeUnit.SECONDS)!! }
            assertEquals(null, requests.first().getHeader("MCP-Protocol-Version"))
            requests.drop(1).forEach { request ->
                assertEquals("2025-06-18", request.getHeader("MCP-Protocol-Version"))
                assertEquals("session-1", request.getHeader("MCP-Session-Id"))
            }
            assertTrue(requests[3].body.readUtf8().contains("page-2"))
            assertEquals("DELETE", requests.last().method)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `discovery uses one overall deadline across pages`() {
        val server = MockWebServer()
        val client = configureTls(server, "mcp.example")
            .newBuilder()
            .dns(LoopbackDns)
            .build()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("MCP-Session-Id", "session-1")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
                ),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val endpoint = server.url("/mcp").newBuilder().host("mcp.example").build().toString()
            val started = System.nanoTime()
            val result = runCatching {
                RemoteMcpToolDiscovery(
                    policy = com.agentdeck.app.domain.extensions.ExtensionPolicy(2),
                    client = client,
                    totalTimeoutMillis = 250,
                ).discover(endpoint)
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(result.isFailure)
            assertTrue("elapsed=$elapsedMillis", elapsedMillis < 1_500)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `proxy bounds accepted and queued client sockets`() {
        val server = MockWebServer()
        val tls = configureTls(server)
        server.start()
        val proxy = SecureMcpProxy(server.url("/mcp"), MemoryExtensionVault(null, null), null, tls)
        val clients = mutableListOf<Socket>()
        try {
            val port = proxy.url.toHttpUrl().port
            repeat(24) {
                runCatching { Socket("127.0.0.1", port) }
                    .getOrNull()
                    ?.let(clients::add)
            }
            Thread.sleep(200)
            assertTrue(proxy.trackedSocketCount <= 8)
        } finally {
            clients.forEach { runCatching(it::close) }
            proxy.close()
            server.shutdown()
        }
    }

    @Test
    fun `closing proxy cancels registered upstream calls`() {
        val server = MockWebServer()
        val tls = configureTls(server)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val proxy = SecureMcpProxy(server.url("/mcp"), MemoryExtensionVault(null, null), null, tls)
        val requestThread = Thread {
            runCatching {
                OkHttpClient().newCall(
                    Request.Builder()
                        .url(proxy.url)
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute().close()
            }
        }
        try {
            requestThread.start()
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

            proxy.close()
            requestThread.join(2_000)

            assertTrue(!requestThread.isAlive)
            assertEquals(0, proxy.trackedCallCount)
        } finally {
            proxy.close()
            requestThread.interrupt()
            server.shutdown()
        }
    }

    private fun configureTls(server: MockWebServer, hostname: String = "localhost"): OkHttpClient {
        val certificate = HeldCertificate.Builder()
            .commonName(hostname)
            .addSubjectAlternativeName(hostname)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        return OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

private object LoopbackDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByName("127.0.0.1"))
}

private class MemoryExtensionVault(
    val ref: String?,
    token: String?,
) : ExtensionCredentialVault {
    private val values = mutableMapOf<String, ByteArray>().apply {
        if (ref != null && token != null) put(ref, token.toByteArray())
    }

    override fun save(credentialRef: String, secret: ByteArray) {
        values[credentialRef] = secret.copyOf()
    }

    override fun load(credentialRef: String): ByteArray? = values[credentialRef]?.copyOf()
    override fun contains(credentialRef: String): Boolean = credentialRef in values
    override fun delete(credentialRef: String) {
        values.remove(credentialRef)?.fill(0)
    }
}
