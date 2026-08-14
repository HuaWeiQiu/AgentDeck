package com.agentdeck.app.domain.chat

/**
 * Deterministic synthetic transcripts for chat-performance work.
 *
 * These fixtures are the shared 50 / 300 / 1000-turn datasets used by JVM
 * correctness tests, the isolated Secure Beta benchmark activity, and later
 * P1/P2 paging work. They are not user data and must never be written to Room
 * or mixed with a real Codex rollout.
 *
 * Pagination matches Codex 0.147.0: the newest 50 turns are the initial page,
 * and each older page contains 25 turns. Items inside a page stay oldest-first.
 */
object ChatPerformanceFixtures {
    const val INITIAL_TURNS = CodexProtocol.INITIAL_HISTORY_TURNS
    const val PAGE_TURNS = CodexProtocol.HISTORY_TURN_PAGE_SIZE
    const val LARGE_MESSAGE_CHARS = 100 * 1024
    const val TOOL_STORM_TURN = 13
    const val TOOL_STORM_COUNT = 100

    val turnCounts: List<Int> = listOf(50, 300, 1000)

    fun conversation(turnCount: Int): ChatPerformanceConversation {
        require(turnCount in turnCounts) { "unsupported turn count: $turnCount" }
        val turns = (1..turnCount).map { index -> turn(index, turnCount) }
        val items = turns.flatMap(ChatPerformanceTurn::items)
        return ChatPerformanceConversation(
            turnCount = turnCount,
            turns = turns,
            items = items,
            pagesNewestFirst = pagesNewestFirst(turns),
        )
    }

    private fun turn(index: Int, turnCount: Int): ChatPerformanceTurn {
        val turnId = turnId(index)
        val items = buildList {
            add(userItem(index, turnId))
            addAll(activityItems(index, turnId))
            add(assistantItem(index, turnCount, turnId))
        }
        return ChatPerformanceTurn(id = turnId, index = index, items = items)
    }

    private fun userItem(index: Int, turnId: String): ChatItem = ChatItem(
        id = "item-user-$index",
        kind = ChatItemKind.USER,
        text = "合成用户消息 $index：请继续分析当前会话的性能特征。",
        turnId = turnId,
    )

    private fun activityItems(index: Int, turnId: String): List<ChatItem> = buildList {
        add(
            ChatItem(
                id = "item-reason-$index",
                kind = ChatItemKind.REASONING,
                text = "检查第 $index 轮的时间线投影与分页边界。",
                turnId = turnId,
            ),
        )
        if (index % 11 == 0) {
            add(
                ChatItem(
                    id = "item-search-a-$index",
                    kind = ChatItemKind.TOOL,
                    text = "query-$index",
                    status = "webSearch",
                    turnId = turnId,
                ),
            )
            add(
                ChatItem(
                    id = "item-search-b-$index",
                    kind = ChatItemKind.TOOL,
                    text = "url-$index",
                    status = "webSearch",
                    turnId = turnId,
                ),
            )
        }
        if (index % 7 == 0) {
            add(
                ChatItem(
                    id = "item-command-$index",
                    kind = ChatItemKind.COMMAND,
                    text = "pwd",
                    status = "completed",
                    turnId = turnId,
                ),
            )
            add(
                ChatItem(
                    id = "item-file-$index",
                    kind = ChatItemKind.FILE_CHANGE,
                    text = "docs/perf-$index.md",
                    patches = listOf(
                        FilePatch(
                            path = "docs/perf-$index.md",
                            kind = "update",
                            diff = "@@\n-old $index\n+new $index\n",
                        ),
                    ),
                    turnId = turnId,
                ),
            )
        }
        if (index == TOOL_STORM_TURN) {
            repeat(TOOL_STORM_COUNT) { toolIndex ->
                add(
                    ChatItem(
                        id = "item-tool-storm-$index-$toolIndex",
                        kind = ChatItemKind.TOOL,
                        text = "tool-$toolIndex",
                        status = "completed",
                        detail = "synthetic-tool-$toolIndex",
                        turnId = turnId,
                    ),
                )
            }
        }
    }

    private fun assistantItem(index: Int, turnCount: Int, turnId: String): ChatItem = ChatItem(
        id = "item-assistant-$index",
        kind = ChatItemKind.ASSISTANT,
        text = assistantText(index, turnCount),
        turnId = turnId,
    )

    private fun assistantText(index: Int, turnCount: Int): String = when (index) {
        largeMessageTurn(turnCount) -> buildString(LARGE_MESSAGE_CHARS) {
            append("# 超长回复 $index\n\n")
            while (length < LARGE_MESSAGE_CHARS) {
                append("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789\n")
            }
        }.take(LARGE_MESSAGE_CHARS)
        tableTurn(turnCount) -> buildString {
            append("## 表格 $index\n\n")
            append("| Col A | Col B | Col C | Col D | Col E | Col F |\n")
            append("| --- | --- | --- | --- | --- | --- |\n")
            repeat(10) { row ->
                append("| r$row-a | r$row-b | r$row-c | r$row-d | r$row-e | r$row-f |\n")
            }
        }
        codeTurn(turnCount) -> buildString {
            append("## 代码 $index\n\n```kotlin\n")
            repeat(80) { line ->
                append("fun measureLine$line(): Int = $line + $index\n")
            }
            append("```\n")
        }
        else -> buildString {
            append("## 回复 $index\n\n")
            append("这是第 $index 轮的合成助手回复，包含段落、列表和链接，供 Markdown 顶层块投影使用。\n\n")
            append("- 稳定 item id\n")
            append("- 分页 cursor\n")
            append("- 正文哈希\n\n")
            append("参考 [性能计划](https://agentdeck.local/perf/$index)。")
        }
    }

    private fun pagesNewestFirst(turns: List<ChatPerformanceTurn>): List<CodexHistoryPage> {
        if (turns.isEmpty()) return emptyList()
        val newestFirstTurns = turns.chunkedPages()
        return newestFirstTurns.mapIndexed { pageIndex, pageTurns ->
            CodexHistoryPage(
                items = pageTurns.flatMap(ChatPerformanceTurn::items),
                nextCursor = if (pageIndex == newestFirstTurns.lastIndex) {
                    null
                } else {
                    cursor(pageIndex + 1)
                },
            )
        }
    }

    private fun List<ChatPerformanceTurn>.chunkedPages(): List<List<ChatPerformanceTurn>> {
        val newestFirst = asReversed()
        if (newestFirst.size <= INITIAL_TURNS) return listOf(this)
        val initial = newestFirst.take(INITIAL_TURNS).asReversed()
        val older = newestFirst.drop(INITIAL_TURNS).chunked(PAGE_TURNS).map { chunk ->
            chunk.asReversed()
        }
        return listOf(initial) + older
    }

    fun turnId(index: Int): String = "turn-$index"

    fun cursor(pageIndex: Int): String = "cursor-$pageIndex"

    fun largeMessageTurn(turnCount: Int): Int = (turnCount - 40).coerceAtLeast(1)

    fun tableTurn(turnCount: Int): Int = (turnCount - 5).coerceAtLeast(2)

    fun codeTurn(turnCount: Int): Int = (turnCount - 8).coerceAtLeast(3)
}

data class ChatPerformanceTurn(
    val id: String,
    val index: Int,
    val items: List<ChatItem>,
)

data class ChatPerformanceConversation(
    val turnCount: Int,
    val turns: List<ChatPerformanceTurn>,
    val items: List<ChatItem>,
    val pagesNewestFirst: List<CodexHistoryPage>,
) {
    val fingerprint: String = ChatTranscriptIntegrity.fingerprint(items)

    fun estimatedCharacterCount(): Int = items.sumOf(ChatTranscriptIntegrity::estimatedCharacterCount)
}
