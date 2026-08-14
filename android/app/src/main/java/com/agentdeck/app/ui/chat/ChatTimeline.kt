package com.agentdeck.app.ui.chat

import androidx.compose.runtime.Immutable
import com.agentdeck.app.domain.chat.ChatError
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind

@Immutable
internal sealed interface ChatTimelineEntry {
    val key: String
    val contentType: String

    @Immutable
    data class Message(val item: ChatItem) : ChatTimelineEntry {
        override val key: String = item.id
        override val contentType: String = "message:${item.kind}"
    }

    @Immutable
    data class Activity(val items: List<ChatItem>) : ChatTimelineEntry {
        override val key: String = "activity-${items.first().id}"
        override val contentType: String = "activity"
    }

    @Immutable
    data class AssistantBlock(
        val item: ChatItem,
        val document: ChatMarkdownDocument,
        val block: ChatMarkdownBlock,
        val isFirst: Boolean,
    ) : ChatTimelineEntry {
        override val key: String = block.key
        override val contentType: String = block.contentType
    }

    /** Placeholder for an evicted history page; re-fetched when it nears the viewport. */
    @Immutable
    data class Gap(
        val pageKey: String,
        val cursor: String?,
        val itemCount: Int,
        val loading: Boolean,
    ) : ChatTimelineEntry {
        override val key: String = "gap-${pageKey}"
        override val contentType: String = "history-gap"
    }

    /** Expanded activity group header; children render as separate parent-list entries. */
    @Immutable
    data class ActivityHeader(val group: Activity) : ChatTimelineEntry {
        override val key: String = group.key
        override val contentType: String = "activity-header"
    }

    /** One child of an expanded activity group, virtualized into the parent list. */
    @Immutable
    data class ActivityChild(val groupKey: String, val item: ChatItem) : ChatTimelineEntry {
        override val key: String = "${groupKey}:${item.id}"
        override val contentType: String = "activity-child:${item.kind}"
    }
}

@Immutable
internal data class ChatTranscriptUiState(
    val items: List<ChatItem> = emptyList(),
    val pages: List<TranscriptHistoryPage> = emptyList(),
    val tailIds: List<String> = emptyList(),
    val streamingItemId: String? = null,
    val isConnecting: Boolean = true,
    val isReconnecting: Boolean = false,
    val isStreaming: Boolean = false,
    val error: ChatError? = null,
    val hasOlderHistory: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val refetchingPageKeys: Set<String> = emptySet(),
)

/**
 * Splits expanded activity groups into header + per-item child entries so a long
 * processing trace never lays out inside one parent LazyColumn item.
 */
internal fun expandTimeline(
    entries: List<ChatTimelineEntry>,
    expandedActivityKeys: Set<String>,
): List<ChatTimelineEntry> {
    if (expandedActivityKeys.isEmpty() || entries.none { it is ChatTimelineEntry.Activity }) {
        return entries
    }
    val expanded = ArrayList<ChatTimelineEntry>(entries.size)
    entries.forEach { entry ->
        if (entry is ChatTimelineEntry.Activity && entry.key in expandedActivityKeys) {
            expanded += ChatTimelineEntry.ActivityHeader(entry)
            entry.items.forEach { item ->
                expanded += ChatTimelineEntry.ActivityChild(entry.key, item)
            }
        } else {
            expanded += entry
        }
    }
    return expanded
}

internal fun groupChatTimeline(
    items: List<ChatItem>,
    markdownDocuments: Map<String, ChatMarkdownDocument> = emptyMap(),
    streamingItemId: String? = null,
    pages: List<TranscriptHistoryPage> = emptyList(),
    tailIds: List<String> = emptyList(),
): List<ChatTimelineEntry> = TimelinePageProjection().project(
    ChatTranscriptStoreState(
        items = items,
        pages = pages,
        tailIds = tailIds,
        streamingItemId = streamingItemId,
    ),
    markdownDocuments,
).entries

internal fun activitySummary(
    items: List<ChatItem>,
    showTechnicalDetails: Boolean = true,
): String {
    if (!showTechnicalDetails) {
        val webSearches = items.count { it.kind == ChatItemKind.TOOL && it.status == "webSearch" }
        return when {
            items.all { it.kind == ChatItemKind.REASONING } -> "需要时可展开查看"
            webSearches > 0 && items.size == webSearches -> "查看了 $webSearches 项网页内容"
            else -> "已完成 ${items.size} 项操作，需要时可展开查看"
        }
    }
    val parts = buildList {
        items.count { it.kind == ChatItemKind.REASONING }
            .takeIf { it > 0 }
            ?.let { add("思考 $it") }
        items.count { it.kind == ChatItemKind.TOOL && it.status == "webSearch" }
            .takeIf { it > 0 }
            ?.let { add("网页搜索 $it") }
        items.count { it.kind == ChatItemKind.COMMAND }
            .takeIf { it > 0 }
            ?.let { add("命令 $it") }
        items.count { it.kind == ChatItemKind.FILE_CHANGE }
            .takeIf { it > 0 }
            ?.let { add("文件 $it") }
        items.count { item ->
            item.kind == ChatItemKind.TOOL && item.status != "webSearch"
        }.takeIf { it > 0 }?.let { add("工具 $it") }
    }
    return parts.joinToString(" · ").ifBlank { "${items.size} 项活动" }
}

internal fun activityDetailText(item: ChatItem, showTechnicalDetails: Boolean): String =
    if (!showTechnicalDetails && item.kind == ChatItemKind.REASONING) {
        "已完成必要的分析"
    } else {
        item.text
    }

internal fun ChatItemKind.isActivity(): Boolean = when (this) {
    ChatItemKind.REASONING,
    ChatItemKind.COMMAND,
    ChatItemKind.FILE_CHANGE,
    ChatItemKind.TOOL,
    -> true

    ChatItemKind.USER,
    ChatItemKind.ASSISTANT,
    ChatItemKind.ERROR,
    -> false
}
