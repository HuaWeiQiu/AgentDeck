package com.agentdeck.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.chat.ApprovalKind
import com.agentdeck.app.domain.chat.ChatApproval
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatUiState
import com.agentdeck.app.domain.model.LaunchResult
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    cardId: String,
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(cardId)),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var timelineWasEmpty by remember { mutableStateOf(true) }

    LaunchedEffect(
        state.items.size,
        state.items.lastOrNull()?.text?.length,
        state.isStreaming,
    ) {
        val initialTimeline = timelineWasEmpty && state.items.isNotEmpty()
        val shouldFollow = initialTimeline || listState.isNearBottom()
        timelineWasEmpty = state.items.isEmpty()
        if (shouldFollow && state.items.isNotEmpty()) {
            yield()
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            state.card?.name ?: "Codex",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.card?.workspacePath?.let { path ->
                            Text(
                                path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                when (val result = vm.openTerminal()) {
                                    LaunchResult.Success -> Unit
                                    is LaunchResult.Failed -> Toast.makeText(
                                        context,
                                        result.message,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = "在 Termux 中打开")
                    }
                },
            )
        },
        bottomBar = {
            ChatBottomBar(
                state = state,
                onComposerChange = vm::updateComposer,
                onSend = vm::send,
                onStop = vm::stop,
                onDecision = vm::decideApproval,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.error?.let { error ->
                item(key = "error") {
                    ErrorBanner(error = error, onRetry = vm::connect)
                }
            }
            if (state.isConnecting && state.items.isEmpty()) {
                item(key = "connecting") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "正在连接 Codex",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                ChatTimelineItem(item)
            }
            if (state.isStreaming && state.items.lastOrNull()?.kind != ChatItemKind.ASSISTANT) {
                item(key = "responding") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Codex 正在处理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return true
    val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= layout.totalItemsCount - AUTO_FOLLOW_THRESHOLD
}

private const val AUTO_FOLLOW_THRESHOLD = 3

@Composable
private fun ChatTimelineItem(item: ChatItem) {
    when (item.kind) {
        ChatItemKind.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                Text(
                    item.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        ChatItemKind.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            Text(
                "Codex",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Markdown(
                content = item.text,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ChatItemKind.REASONING -> ActivityRow(
            icon = Icons.Filled.Psychology,
            title = item.text,
            status = "思考",
        )

        ChatItemKind.COMMAND -> ActivityRow(
            icon = Icons.Filled.Code,
            title = item.text,
            detail = item.detail,
            status = item.status,
            monospace = true,
        )

        ChatItemKind.FILE_CHANGE -> ActivityRow(
            icon = Icons.Filled.Description,
            title = item.text,
            status = item.status,
        )

        ChatItemKind.TOOL -> ActivityRow(
            icon = Icons.Filled.Tune,
            title = item.text,
            detail = item.detail,
            status = item.status,
        )

        ChatItemKind.ERROR -> ActivityRow(
            icon = Icons.Filled.ErrorOutline,
            title = item.text,
            status = "错误",
        )
    }
}

@Composable
private fun ActivityRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    status: String? = null,
    monospace: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                )
                detail?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            status?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                error,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ChatBottomBar(
    state: ChatUiState,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDecision: (String) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            state.approval?.let { approval ->
                ApprovalPanel(approval = approval, onDecision = onDecision)
                Spacer(Modifier.height(10.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = state.composer,
                    onValueChange = onComposerChange,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isConnecting && state.approval == null,
                    placeholder = { Text(if (state.isConnecting) "正在连接" else "发消息给 Codex") },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = if (state.isStreaming) onStop else onSend,
                    enabled = state.isStreaming || state.canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (state.isStreaming) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (state.isStreaming) "停止" else "发送",
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalPanel(
    approval: ChatApproval,
    onDecision: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (approval.kind == ApprovalKind.COMMAND) Icons.Filled.Code else Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(approval.title, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            approval.detail,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (approval.kind == ApprovalKind.COMMAND) {
                    FontFamily.Monospace
                } else {
                    FontFamily.Default
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onDecision("decline") }) { Text("拒绝") }
            TextButton(onClick = { onDecision("acceptForSession") }) { Text("本次会话允许") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { onDecision("accept") }) { Text("允许") }
        }
    }
}
