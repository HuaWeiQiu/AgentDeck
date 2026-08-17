package com.agentdeck.app.ui.chat

import com.agentdeck.app.data.chat.IdleSessionSnapshot
import com.agentdeck.app.data.chat.idleEvictionVictims
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatPerformanceConversation
import com.agentdeck.app.domain.chat.ChatPerformanceFixtures
import com.agentdeck.app.domain.chat.ChatTranscriptIntegrity
import com.agentdeck.app.domain.chat.CodexHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPerformancePhase2Test {
    @Test
    fun `window keeps at most DEFAULT_WINDOW_MAX_PAGES materialized pages and evicts the oldest`() {
        val maxPages = DEFAULT_WINDOW_MAX_PAGES
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository()
        repository.loadConversation(conversation)

        val state = repository.state.value
        assertEquals(11, state.pages.size)
        assertEquals(11 - maxPages, state.evictedPageCount)
        assertEquals(maxPages, state.pages.count { !it.evicted })
        // The newest page (adjacent to the live tail) is never evicted.
        assertFalse(state.pages.last().evicted)
        // Evicted pages keep their descriptor and drop their items from the flat list.
        val presentIds = state.pages.filter { !it.evicted }.flatMap { it.itemIds }
        assertEquals(presentIds, state.items.map { it.id })
    }

    @Test
    fun `visible pages are never eviction candidates`() {
        val maxPages = DEFAULT_WINDOW_MAX_PAGES
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository()
        repository.loadConversation(conversation)

        // Bulk loading protects each just-loaded page, so the oldest fixture page
        // may stay materialized; pick a page the window actually evicted.
        val target = repository.state.value.pages.first { it.evicted }
        val request = repository.beginRefetchPage(target.key)
        assertNotNull(request)
        repository.finishRefetchPage(requireNotNull(request), fixturePage(conversation, target.cursor))
        repository.reportVisibleItems(setOf(target.itemIds.first()))
        repository.enforceWindow()

        val state = repository.state.value
        assertFalse(state.pages.first { it.key == target.key }.evicted)
        assertEquals(maxPages, state.pages.count { !it.evicted })
        assertTrue(state.evictedPageCount > 0)
    }

    @Test
    fun `20 page roundtrip re-fetches every evicted page with identical hashes`() {
        val maxPages = DEFAULT_WINDOW_MAX_PAGES
        val conversation = ChatPerformanceFixtures.conversation(1000)
        val repository = ChatTranscriptRepository()
        repository.loadConversation(conversation)

        assertEquals(39 - maxPages, repository.state.value.evictedPageCount)

        // Walk upward like a user scrolling: refetch the oldest gap, pin it as
        // visible, then continue. Every materialized page must hash identically
        // to the fixture page produced by the same cursor.
        val seenPageFingerprints = LinkedHashSet<String>()
        repository.state.value.pages.filter { !it.evicted }.forEach { page ->
            seenPageFingerprints += pageFingerprint(repository.state.value.items, page)
        }
        // The window always keeps (39 - maxPages) pages evicted, so iterate the
        // original gap set instead of looping until none remain.
        val gapKeys = repository.state.value.pages.filter { it.evicted }.map { it.key }
        assertEquals(39 - maxPages, gapKeys.size)
        gapKeys.forEach { key ->
            val gap = repository.state.value.pages.first { it.key == key }
            assertTrue("gap $key must still be evicted before its turn", gap.evicted)
            val request = repository.beginRefetchPage(gap.key)
            assertNotNull("gap $key must be refetchable", request)
            assertEquals(gap.cursor, requireNotNull(request).cursor)
            repository.finishRefetchPage(requireNotNull(request), fixturePage(conversation, gap.cursor))
            repository.reportVisibleItems(gap.itemIds.take(1).toSet())
            repository.enforceWindow()

            val state = repository.state.value
            val restored = state.pages.first { it.key == gap.key }
            assertFalse(restored.evicted)
            seenPageFingerprints += pageFingerprint(state.items, restored)
            assertTrue(state.pages.count { !it.evicted } <= maxPages)
            // Present pages stay contiguous in the fixture's global order.
            val expectedIds = state.pages.filter { !it.evicted }.flatMap { it.itemIds }
            assertEquals(expectedIds, state.items.map { it.id })
        }
        // All 39 pages were materialized with matching content at least once.
        assertEquals(39, seenPageFingerprints.size)
        assertEquals(
            conversation.fingerprint,
            ChatTranscriptIntegrity.fingerprint(conversation.items),
        )
    }

    @Test
    fun `character budget evicts heavy pages even under the page cap`() {
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository(windowMaxChars = 128 * 1024)
        repository.loadConversation(conversation)

        val state = repository.state.value
        assertTrue(state.evictedPageCount > 0)
        val materializedChars = state.pages.filter { !it.evicted }.sumOf { it.estimatedChars }
        // The newest page can never be evicted, so allow it as headroom.
        val newestPageChars = state.pages.last().estimatedChars
        assertTrue(materializedChars - newestPageChars <= 128 * 1024)
    }

    @Test
    fun `projection emits gap entries for evicted pages`() {
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository()
        repository.loadConversation(conversation)

        val timeline = groupChatTimeline(
            items = repository.state.value.items,
            pages = repository.state.value.pages,
            tailIds = repository.state.value.tailIds,
        )
        val gaps = timeline.filterIsInstance<ChatTimelineEntry.Gap>()
        assertEquals(11 - DEFAULT_WINDOW_MAX_PAGES, gaps.size)
        assertTrue(gaps.none { it.loading })
        assertEquals(repository.state.value.pages.filter { it.evicted }.map { it.key }, gaps.map { it.pageKey })

        val gap = gaps.first()
        val request = repository.beginRefetchPage(gap.pageKey)
        assertNotNull(request)
        val loadingTimeline = TimelinePageProjection().project(
            ChatTranscriptStoreState(
                items = repository.state.value.items,
                pages = repository.state.value.pages,
                tailIds = repository.state.value.tailIds,
                refetchingPageKeys = repository.state.value.refetchingPageKeys,
            ),
            emptyMap(),
        ).entries
        val loadingGap = loadingTimeline.filterIsInstance<ChatTimelineEntry.Gap>()
            .first { it.pageKey == gap.pageKey }
        assertTrue(loadingGap.loading)
    }

    @Test
    fun `expanded activity splits into header and child entries`() {
        val items = listOf(
            ChatItem("reason-1", ChatItemKind.REASONING, "r", turnId = "t1"),
            ChatItem("cmd-1", ChatItemKind.COMMAND, "ls", turnId = "t1"),
            ChatItem("answer-1", ChatItemKind.ASSISTANT, "done", turnId = "t1"),
        )
        val timeline = groupChatTimeline(items = items)
        val activity = timeline.filterIsInstance<ChatTimelineEntry.Activity>().single()

        val collapsed = expandTimeline(timeline, emptySet())
        assertEquals(timeline, collapsed)

        val expanded = expandTimeline(timeline, setOf(activity.key))
        assertEquals(4, expanded.size)
        assertTrue(expanded[0] is ChatTimelineEntry.ActivityHeader)
        assertEquals("activity-reason-1", expanded[0].key)
        assertEquals(
            listOf("activity-reason-1:reason-1", "activity-reason-1:cmd-1"),
            expanded.subList(1, 3).map { it.key },
        )
        assertTrue(expanded[3] is ChatTimelineEntry.Message)
    }

    @Test
    fun `idle session eviction keeps newest two and spares busy sessions`() {
        val sessions = listOf(
            IdleSessionSnapshot(cardId = "old", lastInteractionMs = 100, eligible = true),
            IdleSessionSnapshot(cardId = "mid", lastInteractionMs = 200, eligible = true),
            IdleSessionSnapshot(cardId = "busy", lastInteractionMs = 50, eligible = false),
            IdleSessionSnapshot(cardId = "new", lastInteractionMs = 300, eligible = true),
        )
        assertEquals(listOf("old"), idleEvictionVictims(sessions, maxIdle = 2))
        assertEquals(emptyList<String>(), idleEvictionVictims(sessions.take(2), maxIdle = 2))
        assertEquals(
            listOf("old", "mid", "new"),
            idleEvictionVictims(sessions.filter { it.eligible }, maxIdle = 0),
        )
    }

    @Test
    fun `optimistic user replacement keeps page descriptor ids in sync`() {
        val repository = ChatTranscriptRepository()
        val optimistic = ChatItem(
            id = "local-user-1",
            kind = ChatItemKind.USER,
            text = "你好",
            turnId = null,
        )
        repository.append(optimistic)
        repository.foldTailIntoLatestPage()

        val echoed = ChatItem(id = "user-1", kind = ChatItemKind.USER, text = "你好", turnId = "t1")
        repository.upsert(echoed)

        val state = repository.state.value
        assertEquals(listOf("user-1"), state.items.map { it.id })
        assertEquals(listOf("user-1"), state.pages.single().itemIds)
        assertNull(state.items.firstOrNull { it.id == "local-user-1" })
    }

    @Test
    fun `upsert of an evicted page item never re-orders it to the tail`() {
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository()
        repository.loadConversation(conversation)

        val evicted = repository.state.value.pages.first { it.evicted }
        val ghostId = evicted.itemIds.first()
        // A late event for an evicted item must not append it to the live tail.
        repository.upsert(ChatItem(ghostId, ChatItemKind.ASSISTANT, "late echo"))

        val state = repository.state.value
        assertFalse(state.tailIds.contains(ghostId))
        assertFalse(state.items.map { it.id }.contains(ghostId))
        assertEquals(
            state.pages.filter { !it.evicted }.flatMap { it.itemIds },
            state.items.map { it.id },
        )
    }

    @Test
    fun `removeItem drops ids from evicted page descriptors`() {
        val conversation = ChatPerformanceFixtures.conversation(300)
        val repository = ChatTranscriptRepository()
        repository.loadConversation(conversation)

        val evicted = repository.state.value.pages.first { it.evicted }
        val removedId = evicted.itemIds.first()
        repository.removeItem(removedId)

        val descriptor = repository.state.value.pages.first { it.key == evicted.key }
        assertFalse(descriptor.itemIds.contains(removedId))

        // Re-fetch restores server truth: the rollout still holds the item, so
        // it legitimately returns. Local removal only governs the live tail —
        // history is never deleted (HANDOFF hard constraint).
        val request = repository.beginRefetchPage(evicted.key)
        assertNotNull(request)
        repository.finishRefetchPage(requireNotNull(request), fixturePage(conversation, evicted.cursor))
        val restored = repository.state.value.pages.first { it.key == evicted.key }
        assertFalse(restored.evicted)
        assertTrue(
            "server-authoritative refetch restores history items",
            restored.itemIds.contains(removedId),
        )
    }

    private fun pageFingerprint(items: List<ChatItem>, page: TranscriptHistoryPage): String {
        val byId = items.associateBy { it.id }
        return ChatTranscriptIntegrity.fingerprint(page.itemIds.map { requireNotNull(byId[it]) })
    }

    private fun fixturePage(conversation: ChatPerformanceConversation, cursor: String?): CodexHistoryPage {
        val pageIndex = requireNotNull(cursor).removePrefix("cursor-").toInt()
        return conversation.pagesNewestFirst[pageIndex]
    }
}
