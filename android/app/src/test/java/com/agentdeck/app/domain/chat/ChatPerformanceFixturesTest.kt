package com.agentdeck.app.domain.chat

import com.agentdeck.app.ui.chat.ChatTranscriptRepository
import com.agentdeck.app.ui.chat.groupChatTimeline
import com.agentdeck.app.ui.chat.loadConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPerformanceFixturesTest {
    @Test
    fun `50 300 and 1000 turn fixtures stay deterministic and page like Codex`() {
        ChatPerformanceFixtures.turnCounts.forEach { turnCount ->
            val first = ChatPerformanceFixtures.conversation(turnCount)
            val second = ChatPerformanceFixtures.conversation(turnCount)

            assertEquals(turnCount, first.turns.size)
            assertEquals(first.fingerprint, second.fingerprint)
            assertEquals(64, first.fingerprint.length)
            assertEquals(
                ChatPerformanceFixtures.INITIAL_TURNS.coerceAtMost(turnCount),
                first.pagesNewestFirst.first().items.map { it.turnId }.distinct().size,
            )
            if (turnCount > ChatPerformanceFixtures.INITIAL_TURNS) {
                first.pagesNewestFirst.drop(1).forEach { page ->
                    assertEquals(
                        ChatPerformanceFixtures.PAGE_TURNS,
                        page.items.map { it.turnId }.distinct().size,
                    )
                }
            }
            assertNull(first.pagesNewestFirst.last().nextCursor)
            assertTrue(first.pagesNewestFirst.dropLast(1).all { it.nextCursor != null })
            val large = first.items.first {
                it.id == "item-assistant-${ChatPerformanceFixtures.largeMessageTurn(turnCount)}"
            }
            assertEquals(ChatPerformanceFixtures.LARGE_MESSAGE_CHARS, large.text.length)
            assertEquals(
                first.items.map { it.id },
                first.pagesNewestFirst.asReversed().flatMap { page -> page.items.map { it.id } },
            )
        }
    }

    @Test
    fun `repository replay of newest-first pages preserves item order and fingerprint`() {
        val conversation = ChatPerformanceFixtures.conversation(300)
        // Order/replay fidelity is verified without the P2 bounded window.
        val repository = ChatTranscriptRepository(windowMaxPages = Int.MAX_VALUE)

        repository.loadConversation(conversation)

        assertEquals(conversation.items.map { it.id }, repository.state.value.items.map { it.id })
        assertEquals(
            conversation.fingerprint,
            ChatTranscriptIntegrity.fingerprint(repository.state.value.items),
        )
        assertFalse(repository.state.value.hasOlderHistory)
        assertEquals(
            conversation.items.count { it.kind == ChatItemKind.TOOL && it.id.contains("tool-storm") },
            ChatPerformanceFixtures.TOOL_STORM_COUNT,
        )
    }

    @Test
    fun `timeline grouping keeps user assistant and activity entries for every turn size`() {
        ChatPerformanceFixtures.turnCounts.forEach { turnCount ->
            val conversation = ChatPerformanceFixtures.conversation(turnCount)
            val timeline = groupChatTimeline(conversation.items)
            assertTrue(timeline.isNotEmpty())
            assertEquals(
                conversation.items.count { it.kind == ChatItemKind.USER },
                timeline.count { entry ->
                    entry is com.agentdeck.app.ui.chat.ChatTimelineEntry.Message &&
                        entry.item.kind == ChatItemKind.USER
                },
            )
        }
    }

    @Test
    fun `integrity fingerprint changes when body or patch content changes`() {
        val conversation = ChatPerformanceFixtures.conversation(50)
        val original = conversation.items
        val mutatedText = original.toMutableList().also { items ->
            val index = items.indexOfFirst { it.kind == ChatItemKind.ASSISTANT }
            items[index] = items[index].copy(text = items[index].text + " mutated")
        }
        val mutatedPatch = original.toMutableList().also { items ->
            val index = items.indexOfFirst { it.patches.isNotEmpty() }
            val item = items[index]
            items[index] = item.copy(
                patches = item.patches.map { patch -> patch.copy(diff = patch.diff + " extra") },
            )
        }

        assertNotEquals(
            ChatTranscriptIntegrity.fingerprint(original),
            ChatTranscriptIntegrity.fingerprint(mutatedText),
        )
        assertNotEquals(
            ChatTranscriptIntegrity.fingerprint(original),
            ChatTranscriptIntegrity.fingerprint(mutatedPatch),
        )
    }
}
