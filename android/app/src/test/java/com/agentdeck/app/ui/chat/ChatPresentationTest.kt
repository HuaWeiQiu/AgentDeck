package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationTest {
    @Test
    fun `runtime label omits duplicates and blank values`() {
        assertEquals("openai · gpt-5", chatRuntimeLabel("openai", "gpt-5"))
        assertEquals("Codex", chatRuntimeLabel("Codex", "codex"))
        assertNull(chatRuntimeLabel(" ", null))
    }

    @Test
    fun `standard errors are actionable without exposing protocol details`() {
        val raw = "provider response 401: bearer token invalid"

        assertEquals(
            "模型服务暂时不可用，请检查设置后重试。",
            customerFacingChatError(raw, false),
        )
        assertEquals(raw, customerFacingChatError(raw, true))
    }

    @Test
    fun `outgoing messages always restore latest follow`() {
        val outgoing = ChatItem("local-user-1", ChatItemKind.USER, "hello")
        val history = ChatItem("agent-1", ChatItemKind.ASSISTANT, "old answer")

        assertTrue(shouldFollowLatest(false, false, outgoing))
        assertTrue(shouldFollowLatest(true, false, history))
        assertTrue(shouldFollowLatest(false, true, history))
        assertFalse(shouldFollowLatest(false, false, history))
    }
}
