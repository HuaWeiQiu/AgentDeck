package com.agentdeck.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentdeck.app.data.runtime.NativeRuntimeBudget
import com.agentdeck.app.data.runtime.PiRpcEvent
import com.agentdeck.app.data.chat.PiChatHistoryStore
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PiBubble(
    val id: String,
    val role: String, // user | assistant | system | tool | error
    val text: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiChatScreen(
    cardId: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { ServiceLocator.piRpcSession }
    val bubbles = remember { mutableStateListOf<PiBubble>() }
    val historyStore = remember { ServiceLocator.piChatHistory }
    val markdownDocs = remember { mutableStateMapOf<String, ChatMarkdownDocument>() }
    var input by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var streamingId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("正在启动 pi…") }
    var bindingLabel by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    BackHandler(onBack = onBack)

    // Soft lifecycle: cancel grace-stop on enter; schedule stop on leave (handfeel + RAM).
    fun persistHistory() {
        val snapshot = bubbles.map { PiChatHistoryStore.Bubble(it.id, it.role, it.text) }
        // fire-and-forget on IO
        scope.launch(Dispatchers.IO) {
            historyStore.save(cardId, snapshot)
        }
    }
    DisposableEffect(session) {
        NativeRuntimeBudget.onPiForeground()
        onDispose {
            val snapshot = bubbles.map { com.agentdeck.app.data.chat.PiChatHistoryStore.Bubble(it.id, it.role, it.text) }
            // cannot use scope after dispose reliably — direct IO thread
            Thread {
                runCatching { ServiceLocator.piChatHistory.save(cardId, snapshot) }
            }.start()
            NativeRuntimeBudget.onPiBackground()
        }
    }

    LaunchedEffect(cardId) {
        if (bubbles.isEmpty()) {
            val restored = withContext(Dispatchers.IO) { historyStore.load(cardId) }
            restored.forEach { b ->
                bubbles.add(PiBubble(b.id, b.role, b.text))
            }
        }
        starting = true
        status = "正在连接…"
        NativeRuntimeBudget.onPiForeground()
        val result = withContext(Dispatchers.IO) {
            val card = ServiceLocator.cards.getCard(cardId)
                ?: return@withContext Result.failure(IllegalStateException("会话不存在"))
            val profileId = card.profileId
                ?: return@withContext Result.failure(
                    IllegalStateException("请编辑会话并选择 Chat Completions 模型服务"),
                )
            val profile = ServiceLocator.profiles.getProfile(profileId)
                ?: return@withContext Result.failure(IllegalStateException("模型服务不存在"))
            if (!profile.adapterId.isChatCompletionsCompatible()) {
                return@withContext Result.failure(
                    IllegalStateException("pi 只能使用 Chat Completions 模型服务"),
                )
            }
            val modelId = card.modelId?.takeIf { it.isNotBlank() } ?: profile.defaultModel
            session.ensureStarted(profile, modelId).map {
                "${profile.name} · $modelId"
            }
        }
        starting = false
        result.fold(
            onSuccess = { label ->
                bindingLabel = label
                status = "就绪"
                if (bubbles.none { it.role == "system" }) {
                    bubbles.add(
                        PiBubble(
                            id = "sys-0",
                            role = "system",
                            text = "pi · $label",
                        ),
                    )
                }
            },
            onFailure = { error ->
                status = error.message ?: "启动失败"
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            },
        )
    }

    LaunchedEffect(session) {
        session.eventFlow.collectLatest { event ->
            when (event) {
                is PiRpcEvent.TextDelta -> {
                    val id = streamingId ?: run {
                        val newId = "a-" + System.currentTimeMillis()
                        streamingId = newId
                        bubbles.add(PiBubble(newId, "assistant", ""))
                        newId
                    }
                    val idx = bubbles.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        bubbles[idx] = bubbles[idx].copy(text = bubbles[idx].text + event.delta)
                    }
                    busy = true
                }
                is PiRpcEvent.TextEnd -> {
                    val id = streamingId
                    if (id != null) {
                        val idx = bubbles.indexOfFirst { it.id == id }
                        if (idx >= 0 && event.content.isNotBlank()) {
                            bubbles[idx] = bubbles[idx].copy(text = event.content)
                            val content = event.content
                            scope.launch {
                                val doc = withContext(Dispatchers.Default) {
                                    SharedChatMarkdown.parse(id, content)
                                }
                                markdownDocs[id] = doc
                            }
                        }
                    }
                    streamingId = null
                }
                is PiRpcEvent.ToolStart -> {
                    bubbles.add(
                        PiBubble(
                            id = "t-" + System.currentTimeMillis(),
                            role = "tool",
                            text = "工具 ${event.name}" +
                                if (event.detail.isNotBlank()) " · ${event.detail.take(100)}" else "",
                        ),
                    )
                }
                is PiRpcEvent.ToolEnd -> {
                    bubbles.add(
                        PiBubble(
                            id = "te-" + System.currentTimeMillis(),
                            role = "tool",
                            text = "工具 ${event.name} " + if (event.ok) "完成" else "失败",
                        ),
                    )
                }
                is PiRpcEvent.TurnEnd, is PiRpcEvent.AgentEnd -> {
                    busy = false
                    streamingId = null
                    status = "就绪"
                    val snapshot = bubbles.map {
                        com.agentdeck.app.data.chat.PiChatHistoryStore.Bubble(it.id, it.role, it.text)
                    }
                    scope.launch(Dispatchers.IO) { historyStore.save(cardId, snapshot) }
                }
                is PiRpcEvent.Error -> {
                    busy = false
                    streamingId = null
                    status = event.message
                    bubbles.add(
                        PiBubble(
                            id = "e-" + System.currentTimeMillis(),
                            role = "error",
                            text = event.message,
                        ),
                    )
                }
                is PiRpcEvent.Raw -> Unit
                PiRpcEvent.ProcessEnded -> {
                    busy = false
                    streamingId = null
                    bindingLabel = ""
                    status = "进程已结束"
                }
            }
        }
    }

    LaunchedEffect(bubbles.size, bubbles.lastOrNull()?.text?.length) {
        if (bubbles.isNotEmpty()) {
            listState.animateScrollToItem(bubbles.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AgentDeckTopBar(
                title = {
                    Text(
                        title.ifBlank { "pi" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val chip = bindingLabel.ifBlank { if (starting) "连接中" else "未连接" }
                    Surface(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .widthIn(max = 168.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            chip,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            PiBottomBar(
                modifier = Modifier.imePadding(),
                input = input,
                onInputChange = { input = it },
                enabled = !starting && bindingLabel.isNotBlank(),
                busy = busy,
                onSend = {
                    val text = input.trim()
                    if (text.isEmpty()) return@PiBottomBar
                    input = ""
                    bubbles.add(
                        PiBubble(
                            id = "u-" + System.currentTimeMillis(),
                            role = "user",
                            text = text,
                        ),
                    )
                    busy = true
                    status = "思考中…"
                    scope.launch {
                        val send = withContext(Dispatchers.IO) { session.prompt(text) }
                        send.onFailure { error ->
                            busy = false
                            status = error.message ?: "发送失败"
                            bubbles.add(
                                PiBubble(
                                    id = "e-" + System.currentTimeMillis(),
                                    role = "error",
                                    text = status,
                                ),
                            )
                        }
                    }
                },
                onStop = {
                    scope.launch {
                        withContext(Dispatchers.IO) { session.abort() }
                        busy = false
                        status = "已中止"
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (status.isNotBlank() && status != "就绪") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (starting || busy) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (starting && bubbles.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    ChatMarkdownEnvironment {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(bubbles, key = { it.id }) { bubble ->
                                PiMessageRow(bubble, markdownDocs[bubble.id])
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PiBottomBar(
    modifier: Modifier = Modifier,
    input: String,
    onInputChange: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatComposerTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled && !busy,
                    placeholder = when {
                        !enabled -> "等待连接…"
                        busy -> "pi 正在回复…"
                        else -> "发消息给 pi"
                    },
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = if (busy) onStop else onSend,
                    enabled = if (busy) true else enabled && input.isNotBlank(),
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        if (busy) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (busy) "停止" else "发送",
                    )
                }
            }
        }
    }
}

@Composable
private fun PiMessageRow(bubble: PiBubble, document: ChatMarkdownDocument?) {
    when (bubble.role) {
        "user" -> BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp,
                ),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = maxWidth * 0.88f),
            ) {
                Text(
                    bubble.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        "assistant" -> {
            // Codex-style full-width body; shared Markdown parser/cache with Codex.
            Column(Modifier.fillMaxWidth()) {
                if (document != null && document.content == bubble.text) {
                    ChatMarkdownDocumentBody(document)
                } else {
                    Text(
                        bubble.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        "error" -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                bubble.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        else -> {
            // system / tool
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ) {
                Text(
                    bubble.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
