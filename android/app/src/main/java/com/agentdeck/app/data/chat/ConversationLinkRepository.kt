package com.agentdeck.app.data.chat

import android.content.Context
import androidx.core.content.edit

class ConversationLinkRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun threadId(cardId: String): String? = preferences.getString(threadKey(cardId), null)

    fun saveThreadId(cardId: String, threadId: String) {
        require(cardId.isNotBlank() && threadId.isNotBlank())
        preferences.edit { putString(threadKey(cardId), threadId) }
    }

    fun clearThreadId(cardId: String) {
        preferences.edit { remove(threadKey(cardId)) }
    }

    private fun threadKey(cardId: String) = "thread_$cardId"

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_conversation_links"
    }
}
