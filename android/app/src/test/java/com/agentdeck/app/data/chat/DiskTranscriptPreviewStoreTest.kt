package com.agentdeck.app.data.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskTranscriptPreviewStoreTest {
    @Test
    fun `encode drops tools and round trips user assistant`() {
        val items = listOf(
            ChatItem("1", ChatItemKind.USER, "你好"),
            ChatItem("2", ChatItemKind.ASSISTANT, "世界"),
            ChatItem("3", ChatItemKind.TOOL, "skip me"),
        )
        val payload = DiskTranscriptPreviewStore.encodePreview(
            cardId = "card-a",
            profileId = "prof",
            modelId = "model-x",
            items = items,
        )
        requireNotNull(payload)
        val loaded = DiskTranscriptPreviewStore.decodePreview(payload, "prof", "model-x")
        assertEquals(2, loaded.size)
        assertEquals("你好", loaded[0].text)
        assertEquals("世界", loaded[1].text)
        assertTrue(
            DiskTranscriptPreviewStore.decodePreview(payload, "other", "model-x").isEmpty(),
        )
    }
}
