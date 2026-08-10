package com.agentdeck.app.ui.chat

import androidx.compose.runtime.Immutable
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.CodexHistoryPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
internal data class ChatTranscriptStoreState(
    val items: List<ChatItem> = emptyList(),
    val streamingItemId: String? = null,
    val nextCursor: String? = null,
    val isLoadingOlder: Boolean = false,
    internal val loadingRequestId: Long? = null,
) {
    val hasOlderHistory: Boolean = nextCursor != null
}

internal data class ChatHistoryPageRequest(
    val id: Long,
    val cursor: String,
)

/**
 * Keeps one bounded, non-authoritative preview so reopening the last chat does not
 * flash an empty loading screen. The app-server page replaces it after reconnect.
 */
internal object ChatTranscriptPreviewCache {
    private var cardId: String? = null
    private var profileId: String? = null
    private var modelId: String? = null
    private var items: List<ChatItem> = emptyList()

    @Synchronized
    fun get(cardId: String, profileId: String?, modelId: String?): List<ChatItem> =
        if (this.cardId == cardId && this.profileId == profileId && this.modelId == modelId) {
            items
        } else {
            emptyList()
        }

    @Synchronized
    fun put(cardId: String, profileId: String?, modelId: String?, state: ChatTranscriptStoreState) {
        val completedItems = state.items.filterNot { it.id == state.streamingItemId }
        val retained = ArrayDeque<ChatItem>()
        var retainedChars = 0
        for (item in completedItems.asReversed()) {
            val itemChars = item.estimatedCharacterCount()
            if (retained.size >= MAX_PREVIEW_ITEMS || retainedChars + itemChars > MAX_PREVIEW_CHARS) break
            retained.addFirst(item)
            retainedChars += itemChars
        }
        this.cardId = cardId
        this.profileId = profileId
        this.modelId = modelId
        items = retained.toList()
    }

    @Synchronized
    internal fun clear() {
        cardId = null
        profileId = null
        modelId = null
        items = emptyList()
    }

    private fun ChatItem.estimatedCharacterCount(): Int =
        text.length + (detail?.length ?: 0) + patches.sumOf { it.path.length + it.diff.length }

    private const val MAX_PREVIEW_ITEMS = 120
    private const val MAX_PREVIEW_CHARS = 256 * 1024
}

/**
 * The in-memory projection of Codex's rollout. The app-server remains the only
 * persistent source; this store owns just loaded cursor pages and the live tail.
 */
internal class ChatTranscriptRepository(initialItems: List<ChatItem> = emptyList()) {
    private val mutableState = MutableStateFlow(ChatTranscriptStoreState(items = initialItems))
    val state: StateFlow<ChatTranscriptStoreState> = mutableState.asStateFlow()
    private var nextRequestId = 1L

    fun reset(page: CodexHistoryPage) {
        mutableState.value = ChatTranscriptStoreState(
            items = page.items,
            nextCursor = page.nextCursor,
        )
    }

    fun showPreview(items: List<ChatItem>) {
        if (mutableState.value.items.isEmpty() && items.isNotEmpty()) {
            mutableState.value = ChatTranscriptStoreState(items = items)
        }
    }

    /** Returns the cursor only for the caller that acquired the page-load slot. */
    fun beginLoadOlder(): ChatHistoryPageRequest? {
        val current = mutableState.value
        val cursor = current.nextCursor ?: return null
        if (current.isLoadingOlder) return null
        val request = ChatHistoryPageRequest(nextRequestId++, cursor)
        mutableState.value = current.copy(
            isLoadingOlder = true,
            loadingRequestId = request.id,
        )
        return request
    }

    fun finishLoadOlder(request: ChatHistoryPageRequest, page: CodexHistoryPage) {
        mutableState.update { current ->
            if (current.loadingRequestId != request.id) return@update current
            val currentIds = current.items.mapTo(HashSet(current.items.size)) { it.id }
            current.copy(
                items = page.items.filterNot { it.id in currentIds } + current.items,
                nextCursor = page.nextCursor,
                isLoadingOlder = false,
                loadingRequestId = null,
            )
        }
    }

    fun failLoadOlder(request: ChatHistoryPageRequest) {
        mutableState.update { current ->
            if (current.loadingRequestId == request.id) {
                current.copy(isLoadingOlder = false, loadingRequestId = null)
            } else {
                current
            }
        }
    }

    fun updateItems(transform: (List<ChatItem>) -> List<ChatItem>) {
        mutableState.update { current -> current.copy(items = transform(current.items)) }
    }

    fun update(transform: (ChatTranscriptStoreState) -> ChatTranscriptStoreState) {
        mutableState.update(transform)
    }

    fun setStreamingItemId(itemId: String?) {
        mutableState.update { it.copy(streamingItemId = itemId) }
    }

    fun patchesFor(itemId: String): List<com.agentdeck.app.domain.chat.FilePatch> =
        mutableState.value.items.firstOrNull { it.id == itemId }?.patches.orEmpty()
}
