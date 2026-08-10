package com.agentdeck.app.ui.chat

import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatTimelineTest {
    @Test
    fun `consecutive reasoning and tools collapse into one activity entry`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatItem("user", ChatItemKind.USER, "question"),
                ChatItem("reason", ChatItemKind.REASONING, "checking"),
                ChatItem("search-1", ChatItemKind.TOOL, "query", status = "webSearch"),
                ChatItem("search-2", ChatItemKind.TOOL, "url", status = "webSearch"),
                ChatItem("answer", ChatItemKind.ASSISTANT, "done"),
            ),
        )

        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is ChatTimelineEntry.Message)
        val activity = timeline[1] as ChatTimelineEntry.Activity
        assertEquals(3, activity.items.size)
        assertEquals("思考 1 · 网页搜索 2", activitySummary(activity.items))
        assertTrue(timeline[2] is ChatTimelineEntry.Message)
    }

    @Test
    fun `errors break activity groups`() {
        val timeline = groupChatTimeline(
            listOf(
                ChatItem("command", ChatItemKind.COMMAND, "pwd"),
                ChatItem("error", ChatItemKind.ERROR, "failed"),
                ChatItem("file", ChatItemKind.FILE_CHANGE, "README.md"),
            ),
        )

        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is ChatTimelineEntry.Activity)
        assertTrue(timeline[1] is ChatTimelineEntry.Message)
        assertTrue(timeline[2] is ChatTimelineEntry.Activity)
    }

    @Test
    fun `standard activity summary hides technical event counts`() {
        val items = listOf(
            ChatItem("reason", ChatItemKind.REASONING, "checking"),
            ChatItem("command", ChatItemKind.COMMAND, "pwd"),
        )

        assertEquals("已完成 2 项操作，需要时可展开查看", activitySummary(items, false))
        assertEquals("思考 1 · 命令 1", activitySummary(items, true))
    }

    @Test
    fun `standard activity details hide raw reasoning but preserve commands`() {
        val reasoning = ChatItem("reason", ChatItemKind.REASONING, "raw internal reasoning")
        val command = ChatItem("command", ChatItemKind.COMMAND, "pwd")

        assertEquals("已完成必要的分析", activityDetailText(reasoning, false))
        assertEquals("raw internal reasoning", activityDetailText(reasoning, true))
        assertEquals("pwd", activityDetailText(command, false))
    }

    @Test
    fun `parsed assistant message expands into parent timeline blocks`() {
        val assistant = ChatItem(
            "answer",
            ChatItemKind.ASSISTANT,
            "# Title\n\nParagraph\n\n```\ncode\n```",
        )
        val document = runBlocking { ChatMarkdownParser().parse(assistant.id, assistant.text) }

        val timeline = groupChatTimeline(
            items = listOf(assistant),
            markdownDocuments = mapOf(assistant.id to document),
        )

        assertEquals(document.blocks.size, timeline.size)
        assertTrue(timeline.all { it is ChatTimelineEntry.AssistantBlock })
        assertEquals(1, timeline.count { (it as ChatTimelineEntry.AssistantBlock).isFirst })
    }

    @Test
    fun `streaming assistant stays one plain message item`() {
        val assistant = ChatItem("answer", ChatItemKind.ASSISTANT, "partial")
        val document = runBlocking { ChatMarkdownParser().parse(assistant.id, assistant.text) }

        val timeline = groupChatTimeline(
            items = listOf(assistant),
            markdownDocuments = mapOf(assistant.id to document),
            streamingItemId = assistant.id,
        )

        assertEquals(1, timeline.size)
        assertTrue(timeline.single() is ChatTimelineEntry.Message)
    }

    @Test
    fun `empty completed assistant message does not leave a loading row`() {
        val timeline = groupChatTimeline(
            items = listOf(ChatItem("empty", ChatItemKind.ASSISTANT, "")),
        )

        assertTrue(timeline.isEmpty())
    }

}
