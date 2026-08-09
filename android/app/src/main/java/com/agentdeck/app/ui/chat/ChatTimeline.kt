package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind

internal sealed interface ChatTimelineEntry {
    val key: String

    data class Message(val item: ChatItem) : ChatTimelineEntry {
        override val key: String = item.id
    }

    data class Activity(val items: List<ChatItem>) : ChatTimelineEntry {
        override val key: String = "activity-${items.first().id}"
    }
}

internal fun groupChatTimeline(items: List<ChatItem>): List<ChatTimelineEntry> = buildList {
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
