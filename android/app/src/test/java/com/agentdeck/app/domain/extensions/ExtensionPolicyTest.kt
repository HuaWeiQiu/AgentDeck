package com.agentdeck.app.domain.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPolicyTest {
    @Test
    fun `secure accepts public https remote mcp and rejects local process`() {
        val policy = ExtensionPolicy(ExtensionLevel.REMOTE_WRITE.value)

        assertEquals("mcp.example.com", policy.validateRemoteUrl("https://mcp.example.com/mcp").host)
        assertTrue(runCatching { policy.validateRemoteUrl("http://mcp.example.com/mcp") }.isFailure)
        assertTrue(runCatching { policy.validateRemoteUrl("https://127.0.0.1/mcp") }.isFailure)
        assertTrue(runCatching { policy.validateRemoteUrl("https://user:pass@mcp.example.com/mcp") }.isFailure)
        assertTrue(runCatching { policy.validateRemoteUrl("https://mcp.example.com/mcp?token=secret") }.isFailure)
        assertTrue(runCatching { policy.validateLocalCommand("/usr/bin/python3", emptyList()) }.isFailure)
    }

    @Test
    fun `lab allows constrained absolute local commands only`() {
        val policy = ExtensionPolicy(ExtensionLevel.HOST_CONTROL.value)

        policy.validateLocalCommand("/usr/bin/python3", listOf("-m", "server"))
        policy.validateLocalCommand("/opt/agentdeck/bin/server", emptyList())
        assertTrue(runCatching { policy.validateLocalCommand("python3", emptyList()) }.isFailure)
        assertTrue(runCatching { policy.validateLocalCommand("/system/bin/sh", emptyList()) }.isFailure)
        assertTrue(runCatching { policy.validateLocalCommand("/usr/bin/python3", listOf("bad\narg")) }.isFailure)
    }

    @Test
    fun `remote risk is derived from enabled tools`() {
        val policy = ExtensionPolicy(ExtensionLevel.REMOTE_WRITE.value)
        val read = ExtensionTool("ext", "read", access = ExtensionToolAccess.READ)
        val write = ExtensionTool("ext", "write", access = ExtensionToolAccess.WRITE)

        assertEquals(ExtensionLevel.REMOTE_READ, policy.levelFor(ExtensionKind.REMOTE_MCP, listOf(read)))
        assertEquals(ExtensionLevel.REMOTE_WRITE, policy.levelFor(ExtensionKind.REMOTE_MCP, listOf(read, write)))
        assertEquals(
            ExtensionLevel.REMOTE_READ,
            policy.levelFor(ExtensionKind.REMOTE_MCP, listOf(write.copy(enabled = false))),
        )
    }

    @Test
    fun `mcp tool metadata is bounded and duplicate names are rejected`() {
        val policy = ExtensionPolicy(ExtensionLevel.REMOTE_WRITE.value)
        val valid = ExtensionTool("ext", " read ", title = " Read ", description = " docs ")

        assertEquals("read", policy.normalizeTools(listOf(valid)).single().name)
        assertTrue(runCatching { policy.normalizeTools(listOf(valid, valid.copy(title = "Other"))) }.isFailure)
        assertTrue(runCatching { policy.normalizeTools(listOf(valid.copy(name = "bad name"))) }.isFailure)
        assertTrue(runCatching { policy.normalizeTools(listOf(valid.copy(title = "x".repeat(241)))) }.isFailure)
        assertTrue(runCatching { policy.normalizeTools(listOf(valid.copy(description = "bad\u0000value"))) }.isFailure)
    }
}
