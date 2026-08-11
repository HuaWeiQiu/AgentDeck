package com.agentdeck.app.data.extensions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionConfigComposerTest {
    @Test
    fun `secure disables inherited servers and keeps only managed definitions`() {
        val base = JSONObject(
            """{"model":"gpt","mcp_servers":{"raw":{"command":"/usr/bin/server"}}}""",
        )
        val managed = JSONObject()
            .put("enabled", true)
            .put("url", "http://127.0.0.1:1234/capability")
            .put("default_tools_approval_mode", "prompt")
        val overlay = JSONObject().put("mcp_servers", JSONObject().put("agentdeck_random", managed))

        val merged = ExtensionConfigComposer.merge(
            base,
            overlay,
            managedOnly = true,
            inheritedServerIds = setOf("raw", "project_server"),
        )

        assertEquals("gpt", merged.getString("model"))
        val servers = merged.getJSONObject("mcp_servers")
        assertFalse(servers.getJSONObject("raw").getBoolean("enabled"))
        assertFalse(servers.getJSONObject("project_server").getBoolean("enabled"))
        assertFalse(servers.getJSONObject("raw").has("command"))
        assertEquals("prompt", servers.getJSONObject("agentdeck_random").getString("default_tools_approval_mode"))
    }

    @Test
    fun `lab preserves raw servers and merge does not mutate source objects`() {
        val raw = JSONObject().put("command", "/usr/bin/server")
        val base = JSONObject().put("mcp_servers", JSONObject().put("raw", raw))
        val managed = JSONObject().put("url", "http://127.0.0.1:1234/capability")
        val overlay = JSONObject().put("mcp_servers", JSONObject().put("managed", managed))

        val merged = ExtensionConfigComposer.merge(base, overlay, false, emptySet())
        merged.getJSONObject("mcp_servers").getJSONObject("managed").put("enabled", false)

        assertEquals("/usr/bin/server", merged.getJSONObject("mcp_servers").getJSONObject("raw").getString("command"))
        assertTrue(!managed.has("enabled"))
    }

    @Test
    fun `managed server ids are stable and secure rejects inherited collisions`() {
        val first = ExtensionRepository.serverId("ext_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val second = ExtensionRepository.serverId("ext_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

        assertEquals(first, ExtensionRepository.serverId("ext_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(first.startsWith("agentdeck_ext_"))
        assertTrue(first != second)

        val overlay = JSONObject().put(
            "mcp_servers",
            JSONObject().put(first, JSONObject().put("url", "http://127.0.0.1:1234/capability")),
        )
        assertTrue(
            runCatching {
                ExtensionConfigComposer.merge(
                    base = JSONObject(),
                    overlay = overlay,
                    managedOnly = true,
                    inheritedServerIds = setOf(first),
                )
            }.isFailure,
        )
    }
}
