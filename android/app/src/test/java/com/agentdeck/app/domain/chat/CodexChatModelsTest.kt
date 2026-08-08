package com.agentdeck.app.domain.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexChatModelsTest {
    @Test
    fun `composer cannot send while bridge is disconnected`() {
        assertFalse(ChatUiState(isConnecting = false, composer = "hello").canSend)
        assertTrue(
            ChatUiState(
                isConnecting = false,
                isConnected = true,
                composer = "hello",
            ).canSend,
        )
    }
}
