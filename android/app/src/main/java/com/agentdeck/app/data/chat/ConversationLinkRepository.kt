package com.agentdeck.app.data.chat

import android.content.Context
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ConversationLinkRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun threadId(cardId: String, runtimeKey: String = CURRENT_RUNTIME_KEY): String? {
        preferences.getString(threadKey(cardId, runtimeKey), null)?.let { return it }
        return if (runtimeKey == CURRENT_RUNTIME_KEY) {
            preferences.getString(legacyThreadKey(cardId), null)
        } else {
            null
        }
    }

    fun saveThreadId(
        cardId: String,
        threadId: String,
        runtimeKey: String = CURRENT_RUNTIME_KEY,
    ) {
        validate(cardId, threadId, runtimeKey)
        preferences.edit {
            putString(threadKey(cardId, runtimeKey), threadId)
            if (runtimeKey == CURRENT_RUNTIME_KEY) remove(legacyThreadKey(cardId))
        }
    }

    fun clearThreadId(cardId: String, runtimeKey: String? = null) {
        require(cardId.isNotBlank()) { "对话 ID 不能为空" }
        preferences.edit {
            if (runtimeKey != null) {
                remove(threadKey(cardId, runtimeKey))
                if (runtimeKey == CURRENT_RUNTIME_KEY) remove(legacyThreadKey(cardId))
            } else {
                val prefix = "thread_v2_${cardId}_"
                preferences.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
                remove(legacyThreadKey(cardId))
            }
        }
    }

    private fun threadKey(cardId: String, runtimeKey: String): String {
        validate(cardId, "placeholder", runtimeKey)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(runtimeKey.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "thread_v2_${cardId}_$digest"
    }

    private fun legacyThreadKey(cardId: String) = "thread_$cardId"

    private fun validate(cardId: String, threadId: String, runtimeKey: String) {
        require(cardId.isNotBlank() && threadId.isNotBlank() && runtimeKey.isNotBlank())
    }

    companion object {
        const val CURRENT_RUNTIME_KEY = "current-codex-configuration"
        private const val PREFERENCES_NAME = "agentdeck_conversation_links"
    }
}
