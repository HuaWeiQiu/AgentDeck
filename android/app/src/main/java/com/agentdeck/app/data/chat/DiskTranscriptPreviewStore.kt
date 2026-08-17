package com.agentdeck.app.data.chat

import android.content.Context
import android.util.Log
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatTranscriptIntegrity
import com.agentdeck.app.ui.chat.ChatTranscriptStoreState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Bounded **non-authoritative** transcript preview on disk so force-stop /
 * process death still shows the last messages until app-server pages replace them.
 * Never dual-writes full history; never replaces Codex rollout as source of truth.
 */
internal class DiskTranscriptPreviewStore(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "chat-previews")

    fun get(cardId: String, profileId: String?, modelId: String?): List<ChatItem> {
        val file = fileFor(cardId)
        if (!file.isFile) return emptyList()
        return runCatching {
            decodePreview(file.readText(), profileId, modelId)
        }.onFailure { Log.w(TAG, "read preview ${cardId}: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun put(cardId: String, profileId: String?, modelId: String?, state: ChatTranscriptStoreState) {
        val completed = state.items.filterNot { it.id == state.streamingItemId }
        val payload = encodePreview(cardId, profileId, modelId, completed) ?: run {
            delete(cardId)
            return
        }
        runCatching {
            if (!root.isDirectory) root.mkdirs()
            val tmp = File(root, "${safeName(cardId)}.tmp")
            val out = fileFor(cardId)
            tmp.writeText(payload)
            if (!tmp.renameTo(out)) {
                out.writeText(payload)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "write preview ${cardId}: ${it.message}") }
    }

    fun delete(cardId: String) {
        fileFor(cardId).delete()
    }

    fun clearAll() {
        if (!root.isDirectory) return
        root.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(cardId: String): File = File(root, "${safeName(cardId)}.json")

    private fun safeName(cardId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cardId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return "c_$digest"
    }

    companion object {
        private const val TAG = "DiskTranscriptPreview"
        private const val MAX_ITEMS = 40
        private const val MAX_CHARS = 96 * 1024
        private const val MAX_ITEM_CHARS = 12_000

        /** Pure encode path for unit tests (no Android context). */
        internal fun encodePreview(
            cardId: String,
            profileId: String?,
            modelId: String?,
            items: List<ChatItem>,
        ): String? {
            val retained = ArrayDeque<ChatItem>()
            var chars = 0
            for (item in items.asReversed()) {
                if (item.kind != ChatItemKind.USER && item.kind != ChatItemKind.ASSISTANT) continue
                val c = ChatTranscriptIntegrity.estimatedCharacterCount(item)
                if (retained.size >= MAX_ITEMS || chars + c > MAX_CHARS) break
                retained.addFirst(item)
                chars += c
            }
            if (retained.isEmpty()) return null
            val arr = JSONArray()
            retained.forEach { item ->
                arr.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("kind", item.kind.name)
                        .put("text", item.text.take(MAX_ITEM_CHARS))
                        .put("detail", item.detail)
                        .put("status", item.status)
                        .put("turnId", item.turnId),
                )
            }
            return JSONObject()
                .put("v", 1)
                .put("cardId", cardId)
                .put("profileId", profileId)
                .put("modelId", modelId)
                .put("items", arr)
                .toString()
        }

        internal fun decodePreview(
            payload: String,
            profileId: String?,
            modelId: String?,
        ): List<ChatItem> {
            val rootJson = JSONObject(payload)
            if (rootJson.optString("profileId").ifBlank { null } != profileId) return emptyList()
            if (rootJson.optString("modelId").ifBlank { null } != modelId) return emptyList()
            val arr = rootJson.optJSONArray("items") ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = runCatching {
                        ChatItemKind.valueOf(o.optString("kind"))
                    }.getOrNull() ?: continue
                    add(
                        ChatItem(
                            id = o.optString("id").ifBlank { "disk-$i" },
                            kind = kind,
                            text = o.optString("text"),
                            detail = o.optString("detail").takeIf { it.isNotBlank() },
                            status = o.optString("status").takeIf { it.isNotBlank() },
                            turnId = o.optString("turnId").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
    }
}
