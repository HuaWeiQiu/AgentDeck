package com.agentdeck.app.ui.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatPerformanceFixtures
import com.agentdeck.app.ui.theme.AgentDeckTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Isolated Secure-channel surface for chat-performance work.
 *
 * It renders the production [ChatTranscript] against synthetic Codex pages and
 * never starts Runtime, app-server, MCP, Host Toolkit, or Lab executors.
 */
class ChatPerformanceBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val turnCount = intent.getIntExtra(EXTRA_TURN_COUNT, DEFAULT_TURN_COUNT)
            .takeIf { it in ChatPerformanceFixtures.turnCounts }
            ?: DEFAULT_TURN_COUNT
        enableEdgeToEdge()
        setContent {
            AgentDeckTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                ) {
                    ChatPerformanceBenchmarkRoute(turnCount = turnCount)
                }
            }
        }
    }

    companion object {
        const val EXTRA_TURN_COUNT = "turn_count"
        const val DEFAULT_TURN_COUNT = 300
        const val LIST_TEST_TAG = "chat_performance_transcript"
    }
}

@Composable
private fun ChatPerformanceBenchmarkRoute(turnCount: Int) {
    // Benchmarks materialize the full synthetic dataset; the production bounded
    // window is exercised by ChatPerformancePhase2Test instead of on-device gaps.
    val repository = remember(turnCount) {
        ChatTranscriptRepository(windowMaxPages = Int.MAX_VALUE)
    }
    val transcriptState = remember(turnCount) {
        MutableStateFlow(
            ChatTranscriptUiState(
                isConnecting = true,
                hasOlderHistory = false,
            ),
        )
    }
    val markdownDocuments = remember(turnCount) {
        MutableStateFlow<Map<String, ChatMarkdownDocument>>(emptyMap())
    }
    val streamingText = remember(turnCount) { MutableStateFlow<String?>(null) }
    val parser = remember { ChatMarkdownParser() }

    LaunchedEffect(turnCount) {
        val documents = withContext(Dispatchers.Default) {
            val conversation = ChatPerformanceFixtures.conversation(turnCount)
            repository.loadConversation(conversation)
            val cache = ChatMarkdownCache()
            conversation.items
                .asReversed()
                .asSequence()
                .filter { item -> item.kind == ChatItemKind.ASSISTANT && item.text.isNotEmpty() }
                .take(VISIBLE_MARKDOWN_PREPARSE)
                .toList()
                .asReversed()
                .forEach { item -> cache.put(parser.parse(item.id, item.text)) }
            cache.snapshot()
        }
        markdownDocuments.value = documents
        val loaded = repository.state.value
        transcriptState.value = ChatTranscriptUiState(
            items = loaded.items,
            pages = loaded.pages,
            tailIds = loaded.tailIds,
            isConnecting = false,
            hasOlderHistory = loaded.hasOlderHistory,
        )
    }

    ChatTranscript(
        transcriptState = transcriptState,
        markdownDocuments = markdownDocuments,
        streamingText = streamingText,
        showTechnicalDetails = false,
        onLoadOlder = {},
        onMarkdownNeeded = { _, _ -> },
        onVisibleItems = { _ -> },
        onMarkdownTouched = { _ -> },
        onLoadGap = { _ -> },
        onRetry = {},
        onLongPress = {},
        listTestTag = ChatPerformanceBenchmarkActivity.LIST_TEST_TAG,
    )
}

private const val VISIBLE_MARKDOWN_PREPARSE = 8
