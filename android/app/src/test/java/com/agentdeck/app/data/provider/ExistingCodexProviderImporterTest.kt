package com.agentdeck.app.data.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ExistingCodexProviderImporterTest {
    @Test
    fun `parser accepts bounded responses provider export`() {
        val payload = JSONObject()
            .put("version", 1)
            .put("provider", "deepseek")
            .put("name", "DeepSeek")
            .put("base_url", "https://api.example.com/v1")
            .put("model", "deepseek-v4-flash")
            .put("wire_api", "responses")
            .put("api_key", "secret")
            .toString()
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())

        val parsed = ExistingCodexProviderExport.parse("noise\nAGENTDECK_PROVIDER_V1\t$encoded\n")

        assertEquals("DeepSeek", parsed.name)
        assertEquals("https://api.example.com/v1", parsed.baseUrl)
        assertEquals("deepseek-v4-flash", parsed.model)
        assertEquals("secret", parsed.apiKey.decodeToString())
        parsed.apiKey.fill(0)
    }

    @Test
    fun `parser rejects chat provider and missing credentials`() {
        fun encoded(wireApi: String, key: String): String {
            val payload = JSONObject()
                .put("version", 1)
                .put("provider", "custom")
                .put("base_url", "https://api.example.com/v1")
                .put("model", "model")
                .put("wire_api", wireApi)
                .put("api_key", key)
                .toString()
            return "AGENTDECK_PROVIDER_V1\t" +
                Base64.getEncoder().encodeToString(payload.toByteArray())
        }

        assertTrue(runCatching { ExistingCodexProviderExport.parse(encoded("chat", "secret")) }.isFailure)
        assertTrue(runCatching { ExistingCodexProviderExport.parse(encoded("responses", "")) }.isFailure)
    }

    @Test
    fun `export command reads Ubuntu through proot distro without embedding credentials`() {
        val command = ExistingCodexProviderExport.command()

        assertTrue(command.contains("proot-distro login ubuntu"))
        assertTrue(command.contains("timeout --kill-after=2s 20s"))
        assertTrue(!command.contains("experimental_bearer_token"))
    }
}
