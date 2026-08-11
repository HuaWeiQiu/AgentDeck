package com.agentdeck.app.ui.extensions

import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionTool
import com.agentdeck.app.domain.extensions.ExtensionToolAccess
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionsPresentationTest {
    @Test
    fun `extension groups have stable customer labels`() {
        assertEquals("Skills", extensionKindTitle(ExtensionKind.SKILL))
        assertEquals("远程 MCP", extensionKindTitle(ExtensionKind.REMOTE_MCP))
        assertEquals("本地 MCP", extensionKindTitle(ExtensionKind.LOCAL_MCP))
    }

    @Test
    fun `discovered tool preview exposes its access level`() {
        assertEquals(
            "服务声明只读",
            extensionToolAccessLabel(
                ExtensionTool("ext", "read_docs", access = ExtensionToolAccess.READ),
            ),
        )
        assertEquals(
            "可能写入 · 调用时确认",
            extensionToolAccessLabel(
                ExtensionTool("ext", "publish", access = ExtensionToolAccess.WRITE),
            ),
        )
    }
}
