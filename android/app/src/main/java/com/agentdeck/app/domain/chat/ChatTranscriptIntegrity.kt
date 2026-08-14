package com.agentdeck.app.domain.chat

import java.security.MessageDigest

/**
 * Stable fingerprint of a loaded transcript window.
 *
 * Used to prove that paging, eviction, and resume only change Android memory,
 * not item identity, order, or body. The digest is a comparison token; callers
 * must not log [ChatItem] text, patch diffs, or tool details.
 */
object ChatTranscriptIntegrity {
    fun fingerprint(items: List<ChatItem>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        items.forEach { item ->
            digest.update(item.id.toUtf8())
            digest.update(FIELD_SEP)
            digest.update(item.kind.name.toUtf8())
            digest.update(FIELD_SEP)
            digest.update(item.text.toUtf8())
            digest.update(FIELD_SEP)
            digest.update((item.detail ?: "").toUtf8())
            digest.update(FIELD_SEP)
            digest.update((item.status ?: "").toUtf8())
            digest.update(FIELD_SEP)
            digest.update((item.turnId ?: "").toUtf8())
            digest.update(FIELD_SEP)
            item.patches.forEach { patch ->
                digest.update(patch.path.toUtf8())
                digest.update(FIELD_SEP)
                digest.update(patch.kind.toUtf8())
                digest.update(FIELD_SEP)
                digest.update(patch.diff.toUtf8())
                digest.update(ITEM_SEP)
            }
            digest.update(ITEM_SEP)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun estimatedCharacterCount(item: ChatItem): Int =
        item.text.length +
            (item.detail?.length ?: 0) +
            item.patches.sumOf { patch -> patch.path.length + patch.diff.length }

    private fun String.toUtf8(): ByteArray = toByteArray(Charsets.UTF_8)

    private val FIELD_SEP = byteArrayOf(0)
    private val ITEM_SEP = byteArrayOf(1)
}
