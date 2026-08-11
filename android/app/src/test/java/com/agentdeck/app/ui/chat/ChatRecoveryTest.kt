package com.agentdeck.app.ui.chat

import com.agentdeck.app.data.chat.CodexRpcException
import com.agentdeck.app.domain.chat.CodexModelOption
import com.agentdeck.app.domain.chat.ChatUiState
import com.agentdeck.app.domain.chat.HostWriteApproval
import com.agentdeck.app.domain.chat.QueuedChatMessage
import org.junit.Assert.assertEquals
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

    @Test
    fun `reconnect backoff grows exponentially and caps at thirty seconds`() {
        assertEquals(1_000L, reconnectDelayMs(1))
        assertEquals(2_000L, reconnectDelayMs(2))
        assertEquals(5_000L, reconnectDelayMs(3))
        assertEquals(10_000L, reconnectDelayMs(4))
        assertEquals(30_000L, reconnectDelayMs(5))
        assertEquals(30_000L, reconnectDelayMs(8))
        assertEquals(1_000L, reconnectDelayMs(0))
    }

    @Test
    fun `current codex config uses app server models and keeps runtime model visible`() {
        val models = availableModels(
            managed = false,
            configured = emptyList(),
            discovered = listOf(
                CodexModelOption("gpt-5.6-terra", "GPT-5.6-Terra"),
                CodexModelOption("gpt-5.6-sol", "GPT-5.6-Sol", isDefault = true),
            ),
            runtimeModel = "gpt-5.6-terra",
        )

        assertEquals(listOf("gpt-5.6-terra", "gpt-5.6-sol"), models.map { it.id })
        assertEquals("GPT-5.6-Terra", models.first().displayName)
    }

    @Test
    fun `managed provider does not mix in unrelated app server models`() {
        val models = availableModels(
            managed = true,
            configured = listOf(CodexModelOption("private-a", "Private A")),
            discovered = listOf(CodexModelOption("gpt-public", "Public")),
            runtimeModel = "private-a",
        )

        assertEquals(listOf("private-a"), models.map { it.id })
    }

    @Test
    fun `explicit text only model blocks images while unknown third party stays selectable`() {
        val textOnly = CodexModelOption(
            id = "text-only",
            inputModalities = setOf("text"),
        )
        val unknown = CodexModelOption(id = "third-party", inputModalities = null)

        assertFalse(supportsImageInput(listOf(textOnly), "text-only"))
        assertTrue(supportsImageInput(listOf(unknown), "third-party"))
    }

    @Test
    fun `only active or waiting conversations stay alive in background`() {
        assertFalse(shouldKeepSessionInBackground(ChatUiState(isConnected = true)))
        assertTrue(
            shouldKeepSessionInBackground(
                ChatUiState(isConnected = true, isStreaming = true),
            ),
        )
        assertFalse(
            shouldKeepSessionInBackground(
                state = ChatUiState(isConnected = true, isStreaming = true),
                hasServerResponseInFlight = true,
            ),
        )
        assertFalse(
            shouldKeepSessionInBackground(
                ChatUiState(
                    isConnected = true,
                    isStreaming = true,
                    hostWriteApproval = HostWriteApproval("write-1", "写入宿主文件"),
                ),
            ),
        )
        assertTrue(
            shouldKeepSessionInBackground(
                ChatUiState(
                    isConnected = true,
                    queued = QueuedChatMessage("queued", "next"),
                ),
            ),
        )
        assertFalse(
            hasActiveSessionWork(
                ChatUiState(
                    isConnected = true,
                    queued = QueuedChatMessage("queued", "next"),
                ),
            ),
        )
    }

    @Test
    fun `queued message reserves the single pending composer slot`() {
        val state = ChatUiState(
            isConnected = true,
            composer = "another message",
            queued = QueuedChatMessage("queued", "first pending message"),
        )

        assertFalse(state.canSend)
    }
}
