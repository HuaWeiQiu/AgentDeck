package com.agentdeck.app.ui.chat

import androidx.compose.runtime.Immutable
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatTranscriptIntegrity
import com.agentdeck.app.domain.chat.CodexHistoryPage
import com.agentdeck.app.domain.chat.FilePatch
import com.agentdeck.app.domain.chat.IndexedChatItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val INITIAL_PAGE_KEY = "initial"
/** Fewer materialized history pages → less RSS while scrolling is still smooth. */
internal const val DEFAULT_WINDOW_MAX_PAGES = 5
internal const val DEFAULT_WINDOW_MAX_CHARS = 2 * 1024 * 1024

/** Descriptor for one cursor page. Evicted pages keep only ids + cursors. */
@Immutable
internal data class TranscriptHistoryPage(
    val cursor: String?,
    val nextCursor: String?,
    val itemIds: List<String>,
    val estimatedChars: Int = 0,
    val evicted: Boolean = false,
) {
    val key: String = cursor ?: INITIAL_PAGE_KEY
}

@Immutable
internal data class ChatTranscriptStoreState(
    val items: List<ChatItem> = emptyList(),
    val pages: List<TranscriptHistoryPage> = emptyList(),
    val tailIds: List<String> = emptyList(),
    val streamingItemId: String? = null,
    val nextCursor: String? = null,
    val isLoadingOlder: Boolean = false,
    internal val loadingRequestId: Long? = null,
    internal val refetchingPageKeys: Set<String> = emptySet(),
    internal val evictedPageCount: Int = 0,
) {
    val hasOlderHistory: Boolean = nextCursor != null
}

internal data class ChatHistoryPageRequest(
    val id: Long,
    val cursor: String,
)

internal data class ChatPageRefetchRequest(
    val id: Long,
    val pageKey: String,
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
            val itemChars = ChatTranscriptIntegrity.estimatedCharacterCount(item)
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

    private const val MAX_PREVIEW_ITEMS = 60
    private const val MAX_PREVIEW_CHARS = 128 * 1024
}

/**
 * The in-memory projection of Codex's rollout. The app-server remains the only
 * persistent source; this store owns just loaded cursor pages and the live tail.
 *
 * P2 bounded window: at most [windowMaxPages] history pages or [windowMaxChars]
 * estimated body characters stay materialized. Evicted pages keep lightweight
 * descriptors and are re-fetched with their original request cursor. Eviction
 * only releases Android memory; it never deletes or rewrites the Codex rollout.
 */
internal class ChatTranscriptRepository(
    initialItems: List<ChatItem> = emptyList(),
    private val windowMaxPages: Int = DEFAULT_WINDOW_MAX_PAGES,
    private val windowMaxChars: Int = DEFAULT_WINDOW_MAX_CHARS,
) {
    private val indexed = IndexedChatItems(initialItems)
    private var pages: List<TranscriptHistoryPage> = initialItems.toSinglePage()
    private var tailIds: List<String> = emptyList()
    private var nextCursor: String? = null
    private var visiblePageKeys: Set<String> = emptySet()
    private var refetching = mutableMapOf<String, Long>()
    private val mutableState = MutableStateFlow(
        ChatTranscriptStoreState(items = initialItems, pages = pages),
    )
    val state: StateFlow<ChatTranscriptStoreState> = mutableState.asStateFlow()
    private var nextRequestId = 1L

    fun reset(page: CodexHistoryPage) {
        indexed.replaceAll(page.items)
        pages = listOf(
            TranscriptHistoryPage(
                cursor = null,
                nextCursor = page.nextCursor,
                itemIds = page.items.map { it.id },
                estimatedChars = page.items.sumOf(ChatTranscriptIntegrity::estimatedCharacterCount),
            ),
        )
        tailIds = emptyList()
        nextCursor = page.nextCursor
        visiblePageKeys = emptySet()
        refetching.clear()
        publish(ChatTranscriptStoreState(nextCursor = nextCursor))
    }

    fun showPreview(items: List<ChatItem>) {
        if (mutableState.value.items.isEmpty() && items.isNotEmpty()) {
            indexed.replaceAll(items)
            pages = items.toSinglePage()
            tailIds = emptyList()
            nextCursor = null
            refetching.clear()
            publish(ChatTranscriptStoreState())
        }
    }

    /** Pages containing any of these ids are never eviction candidates. */
    fun reportVisibleItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        val keys = pages.filter { page ->
            !page.evicted && page.itemIds.any(ids::contains)
        }.mapTo(LinkedHashSet()) { it.key }
        if (keys != visiblePageKeys) visiblePageKeys = keys
    }

    fun beginLoadOlder(): ChatHistoryPageRequest? {
        val current = mutableState.value
        val cursor = nextCursor ?: return null
        if (current.isLoadingOlder) return null
        val request = ChatHistoryPageRequest(nextRequestId++, cursor)
        mutableState.value = current.copy(isLoadingOlder = true, loadingRequestId = request.id)
        return request
    }

    fun finishLoadOlder(request: ChatHistoryPageRequest, page: CodexHistoryPage) {
        val current = mutableState.value
        if (current.loadingRequestId != request.id) return
        val newIds = page.items.mapNotNull { item ->
            if (indexed.contains(item.id)) null else item.id
        }
        indexed.prependMissing(page.items)
        pages = listOf(
            TranscriptHistoryPage(
                cursor = request.cursor,
                nextCursor = page.nextCursor,
                itemIds = newIds,
                estimatedChars = page.items.sumOf(ChatTranscriptIntegrity::estimatedCharacterCount),
            ),
        ) + pages
        nextCursor = page.nextCursor
        // The freshly loaded page is what the user is scrolling into; never
        // evict it before the UI reports the new visible region.
        enforceWindow(exceptPageKey = request.cursor)
        publish(
            current.copy(
                isLoadingOlder = false,
                loadingRequestId = null,
            ),
        )
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

    /** Re-fetch an evicted page by its original request cursor. */
    fun beginRefetchPage(pageKey: String): ChatPageRefetchRequest? {
        val page = pages.firstOrNull { it.key == pageKey } ?: return null
        if (!page.evicted) return null
        val cursor = page.cursor ?: return null
        if (refetching.containsKey(pageKey)) return null
        val request = ChatPageRefetchRequest(nextRequestId++, pageKey, cursor)
        refetching[pageKey] = request.id
        publish(mutableState.value)
        return request
    }

    fun finishRefetchPage(request: ChatPageRefetchRequest, page: CodexHistoryPage) {
        if (refetching[request.pageKey] != request.id) return
        refetching.remove(request.pageKey)
        val index = pages.indexOfFirst { it.key == request.pageKey }
        if (index < 0) return
        val referencedElsewhere = referencedIds(exceptPageKey = request.pageKey)
        val freshIds = ArrayList<String>(page.items.size)
        page.items.forEach { item ->
            if (item.id in referencedElsewhere) return@forEach
            freshIds += item.id
            indexed.upsert(item)
        }
        val descriptor = pages[index]
        pages = pages.toMutableList().apply {
            set(
                index,
                descriptor.copy(
                    itemIds = freshIds,
                    estimatedChars = page.items.sumOf(ChatTranscriptIntegrity::estimatedCharacterCount),
                    evicted = false,
                ),
            )
        }
        enforceWindow(exceptPageKey = request.pageKey)
        publish(mutableState.value)
    }

    fun failRefetchPage(request: ChatPageRefetchRequest) {
        if (refetching.remove(request.pageKey) != null) publish(mutableState.value)
    }

    fun append(item: ChatItem) {
        upsert(item)
    }

    fun upsert(item: ChatItem) {
        val outcome = indexed.upsert(item)
        applyUpsertOutcome(item, outcome)
        publish(mutableState.value)
    }

    fun upsertAll(items: List<ChatItem>) {
        if (items.isEmpty()) return
        items.forEach { item -> applyUpsertOutcome(item, indexed.upsert(item)) }
        publish(mutableState.value)
    }

    private fun applyUpsertOutcome(item: ChatItem, outcome: IndexedChatItems.UpsertOutcome) {
        val replaced = outcome.replacedOptimisticId
        if (replaced != null) {
            tailIds = tailIds.map { if (it == replaced) item.id else it }
            pages = pages.map { page ->
                if (replaced in page.itemIds) {
                    page.copy(itemIds = page.itemIds.map { if (it == replaced) item.id else it })
                } else {
                    page
                }
            }
            return
        }
        // Never tail-append an id that a page descriptor already owns — even an
        // evicted one. Appending would re-order that item to the bottom once the
        // page is re-fetched; leaving it out keeps the descriptor authoritative.
        if (outcome.isNew && item.id !in tailIds && pages.none { page -> item.id in page.itemIds }) {
            tailIds = tailIds + item.id
        }
    }

    fun updateItem(id: String, transform: (ChatItem) -> ChatItem) {
        if (indexed.update(id, transform) == null) return
        publish(mutableState.value)
    }

    fun removeItem(id: String) {
        val wasIndexed = indexed.remove(id)
        // Descriptors must drop the id even when the item was evicted from the
        // store; otherwise a later re-fetch would resurrect a removed item.
        val wasReferenced = id in tailIds || pages.any { page -> id in page.itemIds }
        if (!wasIndexed && !wasReferenced) return
        tailIds = tailIds.filterNot { it == id }
        pages = pages.map { page ->
            if (id in page.itemIds) {
                page.copy(itemIds = page.itemIds.filterNot { it == id })
            } else {
                page
            }
        }
        publish(mutableState.value)
    }

    fun mergePatches(itemId: String, patches: List<FilePatch>) {
        if (indexed.mergePatches(itemId, patches) == null) return
        publish(mutableState.value)
    }

    fun ensureAssistantItem(itemId: String, turnId: String?) {
        if (indexed.contains(itemId)) return
        indexed.ensureAssistant(itemId, turnId)
        tailIds = tailIds + itemId
        publish(mutableState.value)
    }

    fun appendAgentDelta(itemId: String, delta: String) {
        val existed = indexed.contains(itemId)
        indexed.appendAgentDelta(itemId, delta)
        if (!existed) tailIds = tailIds + itemId
        publish(mutableState.value)
    }

    fun foldTailIntoLatestPage() {
        if (tailIds.isEmpty()) return
        if (pages.isEmpty()) {
            pages = listOf(
                TranscriptHistoryPage(
                    cursor = null,
                    nextCursor = nextCursor,
                    itemIds = tailIds,
                    estimatedChars = tailIds.sumOf { id ->
                        indexed.get(id)?.let(ChatTranscriptIntegrity::estimatedCharacterCount) ?: 0
                    },
                ),
            )
        } else {
            val last = pages.last()
            pages = pages.dropLast(1) + last.copy(
                itemIds = last.itemIds + tailIds,
                estimatedChars = last.estimatedChars + tailIds.sumOf { id ->
                    indexed.get(id)?.let(ChatTranscriptIntegrity::estimatedCharacterCount) ?: 0
                },
            )
        }
        tailIds = emptyList()
        enforceWindow()
        publish(mutableState.value)
    }

    fun updateItems(transform: (List<ChatItem>) -> List<ChatItem>) {
        val next = transform(indexed.toList())
        indexed.replaceAll(next)
        pages = next.toSinglePage()
        tailIds = emptyList()
        publish(mutableState.value)
    }

    fun update(transform: (ChatTranscriptStoreState) -> ChatTranscriptStoreState) {
        val current = mutableState.value
        val next = transform(current)
        if (next.items !== current.items) {
            indexed.replaceAll(next.items)
            pages = next.items.toSinglePage()
            tailIds = emptyList()
            publish(
                next.copy(
                    nextCursor = nextCursor,
                    refetchingPageKeys = refetching.keys.toSet(),
                ),
            )
        } else {
            mutableState.value = next
        }
    }

    fun setStreamingItemId(itemId: String?) {
        mutableState.update { it.copy(streamingItemId = itemId) }
    }

    fun patchesFor(itemId: String): List<FilePatch> = indexed.get(itemId)?.patches.orEmpty()

    /**
     * Evicts pages farthest from the visible region once the window exceeds
     * [windowMaxPages] or [windowMaxChars]. The newest page (adjacent to the live
     * tail) and visible pages are never evicted; the Codex rollout is untouched.
     */
    internal fun enforceWindow(exceptPageKey: String? = null) {
        if (windowMaxPages == Int.MAX_VALUE) return
        while (true) {
            val presentIndexes = pages.indices.filter { index -> !pages[index].evicted }
            if (presentIndexes.size <= windowMaxPages &&
                presentIndexes.sumOf { pages[it].estimatedChars } <= windowMaxChars
            ) {
                return
            }
            val candidate = chooseEvictionCandidate(presentIndexes, exceptPageKey) ?: return
            evictPage(candidate)
        }
    }

    private fun chooseEvictionCandidate(presentIndexes: List<Int>, exceptPageKey: String?): Int? {
        val lastIndex = pages.lastIndex
        val visibleIndexes = presentIndexes.filter { pages[it].key in visiblePageKeys }
        return presentIndexes
            .filter { index ->
                val page = pages[index]
                index != lastIndex &&
                    page.key != exceptPageKey &&
                    page.key !in visiblePageKeys
            }
            .maxByOrNull { index ->
                val distance = if (visibleIndexes.isEmpty()) {
                    lastIndex - index
                } else {
                    visibleIndexes.minOf { kotlin.math.abs(it - index) }
                }
                distance * 1_000_000 - index
            }
    }

    private fun evictPage(index: Int) {
        val page = pages[index]
        val referencedElsewhere = referencedIds(exceptPageKey = page.key)
        page.itemIds.forEach { id ->
            if (id !in referencedElsewhere) indexed.remove(id)
        }
        pages = pages.toMutableList().apply { set(index, page.copy(evicted = true)) }
    }

    private fun referencedIds(exceptPageKey: String): Set<String> {
        val referenced = HashSet<String>(tailIds)
        pages.forEach { page ->
            if (page.key != exceptPageKey && !page.evicted) referenced += page.itemIds
        }
        return referenced
    }

    private fun publish(state: ChatTranscriptStoreState) {
        val flattened = ArrayList<ChatItem>()
        pages.forEach { page ->
            if (!page.evicted) page.itemIds.mapNotNullTo(flattened, indexed::get)
        }
        tailIds.mapNotNullTo(flattened, indexed::get)
        mutableState.value = state.copy(
            items = flattened,
            pages = pages,
            tailIds = tailIds,
            nextCursor = nextCursor,
            refetchingPageKeys = refetching.keys.toSet(),
            evictedPageCount = pages.count { it.evicted },
        )
    }

    private fun List<ChatItem>.toSinglePage(): List<TranscriptHistoryPage> =
        if (isEmpty()) {
            emptyList()
        } else {
            listOf(
                TranscriptHistoryPage(
                    cursor = null,
                    nextCursor = null,
                    itemIds = map { it.id },
                    estimatedChars = sumOf(ChatTranscriptIntegrity::estimatedCharacterCount),
                ),
            )
        }
}
