package com.agentdeck.app.data.chat

import com.agentdeck.app.domain.chat.ChatAttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAttachmentStoreTest {
    @Test
    fun `only documented common image formats use native image input`() {
        assertEquals(ChatAttachmentKind.IMAGE, attachmentKind("image/png", "shot.bin"))
        assertEquals(ChatAttachmentKind.IMAGE, attachmentKind("application/octet-stream", "photo.JPEG"))
        assertEquals(ChatAttachmentKind.FILE, attachmentKind("image/svg+xml", "diagram.svg"))
        assertEquals(ChatAttachmentKind.FILE, attachmentKind("application/pdf", "report.pdf"))
    }

    @Test
    fun `display name strips paths controls and bounds length`() {
        assertEquals("passwd", safeDisplayName("../etc/passwd"))
        assertEquals("notes.txt", safeDisplayName("folder\\notes\u0000.txt"))
        assertEquals(120, safeDisplayName("a".repeat(200)).length)
    }
}
