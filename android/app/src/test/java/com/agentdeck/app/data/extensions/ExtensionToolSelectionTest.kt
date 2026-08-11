package com.agentdeck.app.data.extensions

import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionToolSelectionTest {
    @Test
    fun `rediscovery preserves existing tool switches by stable name`() {
        val existing = listOf(
            ExtensionTool("ext", "read", enabled = false),
            ExtensionTool("ext", "removed", enabled = false),
        )
        val discovered = listOf(
            ExtensionTool("", "read", enabled = true),
            ExtensionTool("", "new", enabled = true),
        )

        val merged = preserveToolSelections(discovered, existing).associateBy { it.name }

        assertFalse(merged.getValue("read").enabled)
        assertTrue(merged.getValue("new").enabled)
    }

    @Test
    fun `local MCP without discovered tools keeps compatibility mode`() {
        val policy = extensionToolAllowlist(ExtensionKind.LOCAL_MCP, emptyList())

        assertFalse(policy.enforce)
        assertEquals(emptyList<String>(), policy.enabledToolNames)
    }

    @Test
    fun `local MCP with every discovered tool disabled enforces an empty allowlist`() {
        val policy = extensionToolAllowlist(
            ExtensionKind.LOCAL_MCP,
            listOf(ExtensionTool("ext", "dangerous", enabled = false)),
        )

        assertTrue(policy.enforce)
        assertEquals(emptyList<String>(), policy.enabledToolNames)
    }

    @Test
    fun `local MCP with discovered tools injects only enabled names`() {
        val policy = extensionToolAllowlist(
            ExtensionKind.LOCAL_MCP,
            listOf(
                ExtensionTool("ext", "read", enabled = true),
                ExtensionTool("ext", "write", enabled = false),
            ),
        )

        assertTrue(policy.enforce)
        assertEquals(listOf("read"), policy.enabledToolNames)
    }
}
