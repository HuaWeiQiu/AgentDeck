package com.agentdeck.app.data.chat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Bounded on-disk bubble history for pi native chat (UI-only).
 * Not pi's internal session files; survives process death for instant re-open paint.
 */
internal class PiChatHistoryStore(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "pi-chat-history")

    data class Bubble(
        val id: String,
        val role: String,
        val text: String,
    )

    fun load(cardId: String): List<Bubble> {
        val file = fileFor(cardId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("bubbles") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role")
                    val text = o.optString("text")
                    if (role.isBlank() || text.isBlank()) continue
                    add(Bubble(o.optString("id").ifBlank { "b-$i" }, role, text))
                }
            }
        }.onFailure { Log.w(TAG, "load $cardId: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun save(cardId: String, bubbles: List<Bubble>) {
        val retained = ArrayDeque<Bubble>()
        var chars = 0
        for (b in bubbles.asReversed()) {
            if (b.role == "system" && b.id == "sys-0") continue
            val c = b.text.length
            if (retained.size >= MAX_ITEMS || chars + c > MAX_CHARS) break
            retained.addFirst(b.copy(text = b.text.take(MAX_ITEM_CHARS)))
            chars += minOf(c, MAX_ITEM_CHARS)
        }
        if (retained.isEmpty()) {
            delete(cardId)
            return
        }
        runCatching {
            if (!root.isDirectory) root.mkdirs()
            val arr = JSONArray()
            retained.forEach { b ->
                arr.put(
                    JSONObject()
                        .put("id", b.id)
                        .put("role", b.role)
                        .put("text", b.text),
                )
            }
            val payload = JSONObject().put("v", 1).put("bubbles", arr).toString()
            val tmp = File(root, "${safeName(cardId)}.tmp")
            val out = fileFor(cardId)
            tmp.writeText(payload)
            if (!tmp.renameTo(out)) {
                out.writeText(payload)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "save $cardId: ${it.message}") }
    }

    fun delete(cardId: String) {
        fileFor(cardId).delete()
    }

    private fun fileFor(cardId: String): File = File(root, "${safeName(cardId)}.json")

    private fun safeName(cardId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cardId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return "p_$digest"
    }

    companion object {
        private const val TAG = "PiChatHistoryStore"
        private const val MAX_ITEMS = 80
        private const val MAX_CHARS = 128 * 1024
        private const val MAX_ITEM_CHARS = 16_000
    }
}
