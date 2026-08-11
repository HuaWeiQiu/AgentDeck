package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatError
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
            "模型暂时连不上。请检查网络和「设置 → 模型服务」，然后点重试。",
            customerFacingChatError(ChatError.from(raw), false),
        )
        assertEquals(raw, customerFacingChatError(ChatError.from(raw), true))
        assertEquals(
            "还没登录模型服务。请到「设置 → 模型服务」完成登录或填写密钥。",
            customerFacingChatError(ChatError.from("not logged in / unauthorized"), false),
        )
        assertEquals(
            "和本机 Codex 的连接断了。可点重试；若多次失败，到「设置 → 运行环境」检查是否可用。",
            customerFacingChatError(ChatError.from("connection reset by peer"), false),
        )
        assertEquals(
            "这次回复没能完成。请点重试；仍不行可返回会话列表再进入。",
            customerFacingChatError(ChatError.from("unexpected internal failure"), false),
        )
    }

    @Test
    fun `attachment failures stay actionable and bounded`() {
        val parsing = attachmentFailureMessage("文件解析失败：agentdeck: PDF extraction failed")
        val unsupported = attachmentFailureMessage("不支持此文件类型")
        val summary = attachmentFailureSummary(listOf(parsing, unsupported, parsing))

        assertEquals("文件无法解析；请确认文件未损坏、未加密且包含可读取内容", parsing)
        assertTrue(summary.startsWith("3 个文件未添加："))
        assertEquals(
            summary,
            customerFacingChatError(ChatError.Attachment(summary), false),
        )
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

    @Test
    fun `token counts are formatted compactly`() {
        assertEquals("850 tokens", formatTokenCount(850))
        assertEquals("12.3k tokens", formatTokenCount(12_345))
        assertEquals("1.5M tokens", formatTokenCount(1_500_000))
    }
}
