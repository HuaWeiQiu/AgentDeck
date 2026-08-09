package com.agentdeck.app.data.provider

import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderModelDiscoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var discovery: OkHttpProviderModelDiscovery

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
            start()
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager,
            )
            .followRedirects(false)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        discovery = OkHttpProviderModelDiscovery(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `discovers bounded deduplicated models with bearer auth`() = kotlinx.coroutines.runBlocking {
        val oversizedModelId = "m".repeat(161)
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[{"id":"gpt-a","display_name":"bad\nname"},{"id":"gpt-a"},{"id":"gpt-b","display_name":"GPT B"},{"id":"$oversizedModelId"}]}""",
            ),
        )

        val models = discovery.discover(profile(), "secret-key".toByteArray(), 123L)

        assertEquals(listOf("gpt-a", "gpt-b"), models.map { it.id })
        assertEquals("gpt-a", models.first().displayName)
        assertEquals("GPT B", models.last().displayName)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer secret-key", request.getHeader("Authorization"))
    }

    @Test
    fun `maps authentication and unsupported discovery failures`() = kotlinx.coroutines.runBlocking {
        listOf(
            401 to ProviderConnectionStatus.CREDENTIAL_REJECTED,
            403 to ProviderConnectionStatus.FORBIDDEN,
            404 to ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
            429 to ProviderConnectionStatus.RATE_LIMITED,
        ).forEach { (code, status) ->
            server.enqueue(MockResponse().setResponseCode(code))
            val error = runCatching {
                discovery.discover(profile(), "secret-key".toByteArray())
            }.exceptionOrNull()
            assertTrue(error is ProviderDiscoveryException)
            assertEquals(status, (error as ProviderDiscoveryException).status)
        }
    }

    @Test
    fun `rejects redirects malformed bodies and oversized responses`() = kotlinx.coroutines.runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://other.example/v1/models"))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))

        val statuses = List(3) {
            val error = runCatching {
                discovery.discover(profile(), "secret-key".toByteArray())
            }.exceptionOrNull() as ProviderDiscoveryException
            error.status
        }

        assertEquals(
            listOf(
                ProviderConnectionStatus.NETWORK_ERROR,
                ProviderConnectionStatus.INVALID_RESPONSE,
                ProviderConnectionStatus.INVALID_RESPONSE,
            ),
            statuses,
        )
    }

    private fun profile() = ProviderProfile(
        id = "profile_test",
        name = "Sub2API",
        type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().removeSuffix("/"),
        defaultModel = "gpt-a",
        adapterId = ProviderAdapterId.SUB2API,
    )
}
