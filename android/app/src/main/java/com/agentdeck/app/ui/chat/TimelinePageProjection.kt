package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind

internal data class TimelineProjectionSnapshot(
    val entries: List<ChatTimelineEntry>,
    val rebuiltPageCount: Int,
    val rebuiltItemCount: Int,
)

/**
 * Rebuilds timeline entries one history page at a time.
 *
 * Item projections are cached by id + content/markdown revision so a new token,
 * tool update or Markdown parse only rebuilds the affected page and the live tail.
 */
internal class TimelinePageProjection {
    private val itemCache = HashMap<String, CachedItemProjection>()
    private val pageCache = HashMap<String, CachedPageProjection>()
    private var tailCache: CachedPageProjection? = null

    fun project(
        itemsById: Map<String, ChatItem>,
        pages: List<TranscriptHistoryPage>,
        tailIds: List<String>,
        markdownDocuments: Map<String, ChatMarkdownDocument>,
        streamingItemId: String?,
        refetchingPageKeys: Set<String> = emptySet(),
    ): TimelineProjectionSnapshot {
        var rebuiltPages = 0
        var rebuiltItems = 0
        val liveIds = HashSet<String>(itemsById.size)
        val entries = ArrayList<ChatTimelineEntry>()
        pages.forEach { page ->
            if (page.evicted) {
                liveIds += page.itemIds
                entries += ChatTimelineEntry.Gap(
                    pageKey = page.key,
                    cursor = page.cursor,
                    itemCount = page.itemIds.size,
                    loading = page.key in refetchingPageKeys,
                )
                return@forEach
            }
            val projected = projectGroup(
                cacheKey = pageCacheKey(page),
                ids = page.itemIds,
                itemsById = itemsById,
                markdownDocuments = markdownDocuments,
                streamingItemId = streamingItemId,
                cache = pageCache,
            )
            liveIds += page.itemIds
            rebuiltPages += if (projected.rebuilt) 1 else 0
            rebuiltItems += projected.rebuiltItems
            entries += projected.entries
        }
        if (tailIds.isNotEmpty()) {
            val projected = projectGroup(
                cacheKey = TAIL_CACHE_KEY,
                ids = tailIds,
                itemsById = itemsById,
                markdownDocuments = markdownDocuments,
                streamingItemId = streamingItemId,
                cacheHolder = { tailCache },
                store = { tailCache = it },
            )
            liveIds += tailIds
            rebuiltPages += if (projected.rebuilt) 1 else 0
            rebuiltItems += projected.rebuiltItems
            entries += projected.entries
        } else {
            tailCache = null
        }
        pageCache.keys.retainAll { key -> pages.any { pageCacheKey(it) == key } }
        itemCache.keys.retainAll(liveIds)
        return TimelineProjectionSnapshot(entries, rebuiltPages, rebuiltItems)
    }

    fun project(
        state: ChatTranscriptStoreState,
        markdownDocuments: Map<String, ChatMarkdownDocument>,
    ): TimelineProjectionSnapshot {
        val pageIds = state.pages.flatMap { it.itemIds }
        val known = LinkedHashSet<String>(pageIds.size + state.tailIds.size)
        known += pageIds
        known += state.tailIds
        val itemsById = LinkedHashMap<String, ChatItem>(state.items.size)
        val leftover = ArrayList<String>()
        state.items.forEach { item ->
            itemsById[item.id] = item
            if (item.id !in known) leftover += item.id
        }
        return project(
            itemsById = itemsById,
            pages = state.pages,
            tailIds = leftover + state.tailIds,
            markdownDocuments = markdownDocuments,
            streamingItemId = state.streamingItemId,
            refetchingPageKeys = state.refetchingPageKeys,
        )
    }

    private fun projectGroup(
        cacheKey: String,
        ids: List<String>,
        itemsById: Map<String, ChatItem>,
        markdownDocuments: Map<String, ChatMarkdownDocument>,
        streamingItemId: String?,
        cache: MutableMap<String, CachedPageProjection>? = null,
        cacheHolder: (() -> CachedPageProjection?)? = null,
        store: ((CachedPageProjection) -> Unit)? = null,
    ): ProjectedGroup {
        val items = ids.mapNotNull(itemsById::get)
        var rebuiltItems = 0
        val signatures = ArrayList<Long>(items.size)
        val itemEntries = ArrayList<List<ChatTimelineEntry>>(items.size)
        items.forEach { item ->
            val document = markdownDocuments[item.id]
            val signature = itemSignature(item, document, streamingItemId)
            signatures += signature
            val cached = itemCache[item.id]
            if (cached != null && cached.signature == signature) {
                itemEntries += cached.entries
            } else {
                val entries = projectItem(item, document, streamingItemId)
                itemCache[item.id] = CachedItemProjection(signature, entries)
                itemEntries += entries
                rebuiltItems += 1
            }
        }
        val existing = cache?.get(cacheKey) ?: cacheHolder?.invoke()
        if (existing != null && existing.signatures == signatures) {
            return ProjectedGroup(existing.entries, rebuilt = false, rebuiltItems = 0)
        }
        val entries = mergeActivities(itemEntries.flatten())
        val projected = CachedPageProjection(signatures, entries)
        cache?.set(cacheKey, projected)
        store?.invoke(projected)
        return ProjectedGroup(entries, rebuilt = true, rebuiltItems = rebuiltItems)
    }

    private fun projectItem(
        item: ChatItem,
        document: ChatMarkdownDocument?,
        streamingItemId: String?,
    ): List<ChatTimelineEntry> = when {
        item.kind.isActivity() -> listOf(ChatTimelineEntry.Activity(listOf(item)))
        item.kind == ChatItemKind.ASSISTANT && item.id != streamingItemId -> {
            if (item.text.isEmpty()) {
                emptyList()
            } else if (document == null || document.content != item.text || document.blocks.isEmpty()) {
                listOf(ChatTimelineEntry.Message(item))
            } else {
                document.blocks.mapIndexed { index, block ->
                    ChatTimelineEntry.AssistantBlock(
                        item = item,
                        document = document,
                        block = block,
                        isFirst = index == 0,
                    )
                }
            }
        }
        else -> listOf(ChatTimelineEntry.Message(item))
    }
}

/**
 * Markdown AST cache budget. Tuned for phones that also host PRoot + Node:
 * keep the visible window crisp without retaining multi-page ASTs.
 */
internal fun markdownBudgetBytes(memoryClassMb: Int): Int = when {
    memoryClassMb <= 128 -> 4 * 1024 * 1024
    memoryClassMb >= 384 -> 12 * 1024 * 1024
    else -> 8 * 1024 * 1024
}

internal fun markdownMemoryClassMb(): Int = try {
    val context = com.agentdeck.app.di.ServiceLocator.appContext
    val manager = context.getSystemService(android.app.ActivityManager::class.java)
    manager?.memoryClass ?: 256
} catch (_: Exception) {
    256
}

internal fun visibleMarkdownWindow(
    items: List<ChatItem>,
    visibleIds: Set<String>,
    streamingItemId: String?,
    prefetch: Int = VISIBLE_MARKDOWN_PREFETCH,
): List<ChatItem> {
    val eligible = items.filter { item ->
        item.kind == ChatItemKind.ASSISTANT &&
            item.id != streamingItemId &&
            item.text.isNotEmpty()
    }
    if (eligible.isEmpty()) return emptyList()
    val visibleIndexes = eligible.mapIndexedNotNull { index, item ->
        index.takeIf { item.id in visibleIds }
    }
    if (visibleIndexes.isEmpty()) {
        return eligible.takeLast(prefetch.coerceAtLeast(1))
    }
    val start = (visibleIndexes.min() - prefetch).coerceAtLeast(0)
    val end = (visibleIndexes.max() + prefetch).coerceAtMost(eligible.lastIndex)
    return eligible.subList(start, end + 1)
}

internal fun mergeActivities(entries: List<ChatTimelineEntry>): List<ChatTimelineEntry> {
    if (entries.isEmpty()) return emptyList()
    val merged = ArrayList<ChatTimelineEntry>(entries.size)
    val pending = ArrayList<ChatItem>()
    fun flush() {
        if (pending.isEmpty()) return
        merged += ChatTimelineEntry.Activity(pending.toList())
        pending.clear()
    }
    entries.forEach { entry ->
        if (entry is ChatTimelineEntry.Activity) {
            pending += entry.items
        } else {
            flush()
            merged += entry
        }
    }
    flush()
    return merged
}

private fun itemSignature(
    item: ChatItem,
    document: ChatMarkdownDocument?,
    streamingItemId: String?,
): Long {
    var result = item.id.hashCode().toLong()
    result = result * 31 + item.kind.hashCode()
    result = result * 31 + item.text.hashCode()
    result = result * 31 + (item.detail?.hashCode() ?: 0)
    result = result * 31 + (item.status?.hashCode() ?: 0)
    result = result * 31 + item.patches.hashCode()
    result = result * 31 + if (item.id == streamingItemId) 1 else 0
    result = result * 31 + (document?.content?.hashCode() ?: 0)
    result = result * 31 + (document?.blocks?.size ?: 0)
    return result
}

private fun pageCacheKey(page: TranscriptHistoryPage): String =
    page.cursor ?: INITIAL_PAGE_CACHE_KEY

private data class CachedItemProjection(
    val signature: Long,
    val entries: List<ChatTimelineEntry>,
)

private data class CachedPageProjection(
    val signatures: List<Long>,
    val entries: List<ChatTimelineEntry>,
)

private data class ProjectedGroup(
    val entries: List<ChatTimelineEntry>,
    val rebuilt: Boolean,
    val rebuiltItems: Int,
)

private const val INITIAL_PAGE_CACHE_KEY = "initial"
private const val TAIL_CACHE_KEY = "tail"
internal const val VISIBLE_MARKDOWN_PREFETCH = 4
