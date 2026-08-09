package com.agentdeck.app.domain.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointNormalizerTest {
    @Test
    fun `root endpoint gains canonical v1 path`() {
        val endpoint = ProviderEndpointNormalizer.normalize("https://API.Example.com/").getOrThrow()

        assertEquals("https://api.example.com/v1", endpoint.apiBaseUrl)
        assertEquals("https://api.example.com/v1/models", endpoint.modelsUrl)
    }

    @Test
    fun `existing api path is retained without duplicate slash`() {
        val endpoint = ProviderEndpointNormalizer.normalize("https://example.com/gateway/v1///")
            .getOrThrow()

        assertEquals("https://example.com/gateway/v1", endpoint.apiBaseUrl)
        assertEquals("https://example.com/gateway/v1/models", endpoint.modelsUrl)
    }

    @Test
    fun `cleartext credentials and query values are rejected`() {
        listOf(
            "http://example.com/v1",
            "https://secret@example.com/v1",
            "https://example.com/v1?token=secret",
            "https://example.com/v1#secret",
        ).forEach { value ->
            assertTrue(value, ProviderEndpointNormalizer.normalize(value).isFailure)
        }
    }
}
