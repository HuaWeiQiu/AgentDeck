package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatPerformanceFixtures
import com.agentdeck.app.domain.chat.ChatTranscriptIntegrity
import com.agentdeck.app.domain.chat.FilePatch
import com.agentdeck.app.domain.chat.IndexedChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatPerformancePhase1Test {
    @Test
    fun `indexed upsert replaces optimistic user and keeps live patches`() {
        val items = IndexedChatItems(
            listOf(
                ChatItem("local-user-1", ChatItemKind.USER, "hello"),
                ChatItem(
                    "file-1",
                    ChatItemKind.FILE_CHANGE,
                    "README.md",
                    patches = listOf(FilePatch("README.md", "update", "@@\n-old\n+new\n")),
                ),
            ),
        )

        items.upsert(ChatItem("user-1", ChatItemKind.USER, "hello"))
        items.upsert(ChatItem("file-1", ChatItemKind.FILE_CHANGE, "README.md"))

        assertEquals(listOf("user-1", "file-1"), items.toList().map { it.id })
        assertEquals("README.md", items.get("file-1")?.patches?.single()?.path)
    }

    @Test
    fun `paged incremental projection rebuilds only the changed page`() {
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository(windowMaxPages = Int.MAX_VALUE)
        repository.loadConversation(conversation)
        val projection = TimelinePageProjection()
        val first = projection.project(repository.state.value, emptyMap())
        assertEquals(conversation.items.map { it.id }.count { true }, repository.state.value.items.size)
        assertEquals(
            conversation.fingerprint,
            ChatTranscriptIntegrity.fingerprint(repository.state.value.items),
        )
        assertTrue(first.entries.isNotEmpty())

        val lastAssistant = repository.state.value.items.last { it.kind == ChatItemKind.ASSISTANT }
        repository.updateItem(lastAssistant.id) { it.copy(text = it.text + " extra") }
        val second = projection.project(repository.state.value, emptyMap())

        assertEquals(1, second.rebuiltPageCount)
        assertEquals(1, second.rebuiltItemCount)
        assertEquals(first.entries.size, second.entries.size)
        assertNotEquals(first.entries.last().key, "")
    }

    @Test
    fun `50 300 and 1000 turn incremental projection keeps user order`() {
        ChatPerformanceFixtures.turnCounts.forEach { turnCount ->
            val conversation = ChatPerformanceFixtures.conversation(turnCount)
            val repository = ChatTranscriptRepository(windowMaxPages = Int.MAX_VALUE)
            repository.loadConversation(conversation)
            val timeline = TimelinePageProjection().project(repository.state.value, emptyMap()).entries
            assertEquals(
                conversation.items.count { it.kind == ChatItemKind.USER },
                timeline.count { entry ->
                    entry is ChatTimelineEntry.Message && entry.item.kind == ChatItemKind.USER
                },
            )
            assertEquals(
                conversation.items.first { it.kind == ChatItemKind.USER }.id,
                (timeline.first { it is ChatTimelineEntry.Message } as ChatTimelineEntry.Message).item.id,
            )
        }
    }

    @Test
    fun `visible markdown window prefetches around reported ids and falls back to tail`() {
        val items = (1..12).map { index ->
            ChatItem("a-$index", ChatItemKind.ASSISTANT, "text-$index")
        }
        val around = visibleMarkdownWindow(items, setOf("a-6"), streamingItemId = null, prefetch = 2)
        assertEquals((4..8).map { "a-$it" }, around.map { it.id })

        val fallback = visibleMarkdownWindow(items, emptySet(), streamingItemId = null, prefetch = 3)
        assertEquals(listOf("a-10", "a-11", "a-12"), fallback.map { it.id })
    }

    @Test
    fun `markdown budget uses 8 12 and 24 MiB memory class tiers`() {
        assertEquals(8 * 1024 * 1024, markdownBudgetBytes(96))
        assertEquals(12 * 1024 * 1024, markdownBudgetBytes(256))
        assertEquals(24 * 1024 * 1024, markdownBudgetBytes(512))
    }

    @Test
    fun `parsed assistant still expands after incremental projection`() {
        val assistant = ChatItem(
            "answer",
            ChatItemKind.ASSISTANT,
            "# Title\n\nParagraph\n\n```\ncode\n```",
        )
        val document = runBlocking { ChatMarkdownParser().parse(assistant.id, assistant.text) }
        val timeline = groupChatTimeline(
            items = listOf(assistant),
            markdownDocuments = mapOf(assistant.id to document),
        )
        assertEquals(document.blocks.size, timeline.size)
        assertTrue(timeline.all { it is ChatTimelineEntry.AssistantBlock })
    }
}
