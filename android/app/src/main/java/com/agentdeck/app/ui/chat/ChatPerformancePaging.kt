package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatPerformanceConversation
import com.agentdeck.app.domain.chat.CodexHistoryPage

/** Applies newest-first Codex pages to the current in-memory transcript store. */
internal fun ChatTranscriptRepository.loadNewestFirst(pages: List<CodexHistoryPage>) {
    require(pages.isNotEmpty()) { "history must include the initial page" }
    reset(pages.first())
    pages.drop(1).forEach { page ->
        val request = requireNotNull(beginLoadOlder()) {
            "missing cursor while loading synthetic older page"
        }
        finishLoadOlder(request, page)
    }
}

internal fun ChatTranscriptRepository.loadConversation(
    conversation: ChatPerformanceConversation,
) {
    loadNewestFirst(conversation.pagesNewestFirst)
}
