package com.agentdeck.app.ui.extensions

import com.agentdeck.app.domain.extensions.ExtensionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionsPresentationTest {
    @Test
    fun `extension groups have stable customer labels`() {
        assertEquals("Skills", extensionKindTitle(ExtensionKind.SKILL))
        assertEquals("远程 MCP", extensionKindTitle(ExtensionKind.REMOTE_MCP))
        assertEquals("本地 MCP", extensionKindTitle(ExtensionKind.LOCAL_MCP))
    }
}
