package com.agentdeck.app.data.extensions

import com.agentdeck.app.domain.extensions.ExtensionTool
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
}
