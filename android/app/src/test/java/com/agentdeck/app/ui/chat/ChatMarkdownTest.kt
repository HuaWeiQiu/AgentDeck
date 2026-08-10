package com.agentdeck.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatMarkdownTest {
    private val parser = ChatMarkdownParser()
    private fun parse(messageId: String, content: String) = runBlocking {
        parser.parse(messageId, content)
    }

    @Test
    fun `parser exposes stable top level markdown blocks`() {
        val document = parse(
            "assistant-1",
            "# Title\n\nParagraph\n\n```kotlin\nval value = 1\n```",
        )

        assertEquals(3, document.blocks.size)
        assertEquals(3, document.blocks.map { it.key }.distinct().size)
        assertTrue(document.blocks.first().key.startsWith("assistant-1:markdown:"))
        assertTrue(document.estimatedBytes > document.content.length * 2)
    }

    @Test
    fun `cache evicts least recently used document by entry limit`() {
        val cache = ChatMarkdownCache(maxEntries = 2, maxBytes = Int.MAX_VALUE)
        val first = parse("first", "one")
        val second = parse("second", "two")
        val third = parse("third", "three")

        cache.put(first)
        cache.put(second)
        assertEquals(first, cache.get("first", "one"))
        val snapshot = cache.put(third)

        assertTrue("first" in snapshot)
        assertTrue("third" in snapshot)
        assertFalse("second" in snapshot)
    }

    @Test
    fun `cache rejects stale content and accounts its removal`() {
        val cache = ChatMarkdownCache()
        val document = parse("assistant", "old")
        cache.put(document)
        val before = cache.sizeBytes()

        assertNull(cache.get("assistant", "new"))
        assertTrue(before > 0)
        assertEquals(0, cache.sizeBytes())
    }

    @Test
    fun `cache retains a sole oversized document`() {
        val document = parse("assistant", "# heading\n\nparagraph")
        val cache = ChatMarkdownCache(maxEntries = 10, maxBytes = document.estimatedBytes - 1)

        assertEquals(document, cache.put(document)[document.messageId])
        assertEquals(document.estimatedBytes, cache.sizeBytes())
    }
}
