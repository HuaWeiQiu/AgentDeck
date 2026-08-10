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
}

@Immutable
internal data class ChatTranscriptUiState(
    val items: List<ChatItem> = emptyList(),
    val streamingItemId: String? = null,
    val isConnecting: Boolean = true,
    val isReconnecting: Boolean = false,
    val isStreaming: Boolean = false,
    val error: ChatError? = null,
    val hasOlderHistory: Boolean = false,
    val isLoadingOlder: Boolean = false,
)

internal fun groupChatTimeline(
    items: List<ChatItem>,
    markdownDocuments: Map<String, ChatMarkdownDocument> = emptyMap(),
    streamingItemId: String? = null,
): List<ChatTimelineEntry> = buildList {
    val activity = mutableListOf<ChatItem>()

    fun flushActivity() {
        if (activity.isNotEmpty()) {
            add(ChatTimelineEntry.Activity(activity.toList()))
            activity.clear()
        }
    }

    items.forEach { item ->
        if (item.kind.isActivity()) {
            activity += item
        } else if (item.kind == ChatItemKind.ASSISTANT && item.id != streamingItemId) {
            flushActivity()
            if (item.text.isEmpty()) return@forEach
            val document = markdownDocuments[item.id]
            if (document == null || document.content != item.text || document.blocks.isEmpty()) {
                add(ChatTimelineEntry.Message(item))
            } else {
                document.blocks.forEachIndexed { index, block ->
                    add(
                        ChatTimelineEntry.AssistantBlock(
                            item = item,
                            document = document,
                            block = block,
                            isFirst = index == 0,
                        ),
                    )
                }
            }
        } else {
            flushActivity()
            add(ChatTimelineEntry.Message(item))
        }
    }
    flushActivity()
}

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

private fun ChatItemKind.isActivity(): Boolean = when (this) {
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
