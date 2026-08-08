package com.agentdeck.app.ui.chat

import com.agentdeck.app.data.chat.CodexRpcException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecoveryTest {
    @Test
    fun `only explicit active writer errors trigger thread replacement`() {
        assertTrue(
            CodexRpcException(-1, "thread abc already has an active writer").hasActiveWriter(),
        )
        assertFalse(CodexRpcException(-1, "authentication failed").hasActiveWriter())
        assertFalse(CodexRpcException(-1, "request timed out").hasActiveWriter())
    }
}
