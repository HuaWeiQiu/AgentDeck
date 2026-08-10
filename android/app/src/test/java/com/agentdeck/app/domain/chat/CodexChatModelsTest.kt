package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId
import org.junit.Assert.assertEquals
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

    @Test
    fun `canSend is computed at construction and follows copied fields`() {
        val base = ChatUiState(isConnecting = false, isConnected = true, composer = "hello")
        assertTrue(base.canSend)
        // Streaming no longer blocks sending: the composer steers the active turn.
        assertTrue(base.copy(isStreaming = true).canSend)
        assertFalse(base.copy(composer = " ").canSend)
        assertFalse(base.copy(approval = ChatApproval(
            requestId = RpcRequestId.Number(1),
            kind = ApprovalKind.COMMAND,
            title = "t",
            detail = "d",
        )).canSend)
    }

    @Test
    fun `raw errors map to classified chat errors`() {
        assertTrue(ChatError.from("authentication failed: unauthorized") is ChatError.Auth)
        assertTrue(ChatError.from("provider response 401") is ChatError.Model)
        assertTrue(ChatError.from("Codex 连接已断开") is ChatError.Network)
        assertEquals("文件无法解析", ChatError.Attachment("文件无法解析").raw)
        assertTrue(ChatError.from("unexpected EOF") is ChatError.Unknown)
    }
}
