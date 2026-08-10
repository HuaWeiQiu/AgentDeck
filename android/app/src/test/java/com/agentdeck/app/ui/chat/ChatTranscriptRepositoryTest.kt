package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.CodexHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptRepositoryTest {
    @Test
    fun `preview cache keeps a bounded completed tail for the last chat only`() {
        ChatTranscriptPreviewCache.clear()
        val items = (1..125).map { index ->
            ChatItem("item-$index", ChatItemKind.ASSISTANT, "message-$index")
        }
        ChatTranscriptPreviewCache.put(
            "chat-a",
            "provider-a",
            "model-a",
            ChatTranscriptStoreState(items = items, streamingItemId = "item-125"),
        )

        val preview = ChatTranscriptPreviewCache.get("chat-a", "provider-a", "model-a")
        assertEquals(120, preview.size)
        assertEquals("item-5", preview.first().id)
        assertEquals("item-124", preview.last().id)
        assertTrue(ChatTranscriptPreviewCache.get("chat-b", "provider-a", "model-a").isEmpty())
        assertTrue(ChatTranscriptPreviewCache.get("chat-a", "provider-b", "model-a").isEmpty())
        assertTrue(ChatTranscriptPreviewCache.get("chat-a", "provider-a", "model-b").isEmpty())
        ChatTranscriptPreviewCache.clear()
    }

    @Test
    fun `initial page replaces transcript and exposes its cursor`() {
        val repository = ChatTranscriptRepository()

        repository.reset(
            CodexHistoryPage(
                items = listOf(ChatItem("new", ChatItemKind.ASSISTANT, "new")),
                nextCursor = "older",
            ),
        )

        assertEquals(listOf("new"), repository.state.value.items.map { it.id })
        assertEquals("older", repository.state.value.nextCursor)
        assertTrue(repository.state.value.hasOlderHistory)
    }

    @Test
    fun `older page prepends once and preserves the live version of overlaps`() {
        val repository = ChatTranscriptRepository()
        repository.reset(
            CodexHistoryPage(
                items = listOf(ChatItem("overlap", ChatItemKind.ASSISTANT, "live")),
                nextCursor = "page-2",
            ),
        )
        val request = requireNotNull(repository.beginLoadOlder())
        assertEquals("page-2", request.cursor)
        assertNull(repository.beginLoadOlder())

        repository.finishLoadOlder(
            request,
            CodexHistoryPage(
                items = listOf(
                    ChatItem("old", ChatItemKind.USER, "old"),
                    ChatItem("overlap", ChatItemKind.ASSISTANT, "persisted"),
                ),
                nextCursor = null,
            ),
        )

        assertEquals(listOf("old", "overlap"), repository.state.value.items.map { it.id })
        assertEquals("live", repository.state.value.items.last().text)
        assertFalse(repository.state.value.isLoadingOlder)
        assertFalse(repository.state.value.hasOlderHistory)
    }

    @Test
    fun `failed page load releases single flight without consuming cursor`() {
        val repository = ChatTranscriptRepository()
        repository.reset(CodexHistoryPage(emptyList(), "page-2"))

        val request = requireNotNull(repository.beginLoadOlder())
        assertEquals("page-2", request.cursor)
        repository.failLoadOlder(request)

        assertFalse(repository.state.value.isLoadingOlder)
        assertEquals("page-2", repository.beginLoadOlder()?.cursor)
    }

    @Test
    fun `stale failure cannot release a newer page request`() {
        val repository = ChatTranscriptRepository()
        repository.reset(CodexHistoryPage(emptyList(), "page-2"))
        val stale = requireNotNull(repository.beginLoadOlder())
        repository.reset(CodexHistoryPage(emptyList(), "page-2"))
        val current = requireNotNull(repository.beginLoadOlder())

        repository.failLoadOlder(stale)

        assertTrue(repository.state.value.isLoadingOlder)
        repository.finishLoadOlder(current, CodexHistoryPage(emptyList(), null))
        assertFalse(repository.state.value.isLoadingOlder)
    }
}
