package com.agentdeck.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    val timeline = remember(state.items) { groupChatTimeline(state.items) }
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
        containerColor = MaterialTheme.colorScheme.background,
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
                        val runtime = listOfNotNull(
                            state.runtimeProvider,
                            state.runtimeModel,
                        ).joinToString(" · ").ifBlank { state.card?.workspacePath.orEmpty() }
                        runtime.takeIf(String::isNotBlank)?.let { detail ->
                            Text(
                                detail,
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
                modifier = Modifier.imePadding(),
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
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
            items(timeline, key = { it.key }) { entry ->
                when (entry) {
                    is ChatTimelineEntry.Message -> ChatMessage(entry.item)
                    is ChatTimelineEntry.Activity -> ActivityDisclosure(entry)
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    ErrorBanner(error = error, onRetry = vm::connect)
                }
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
private fun ChatMessage(item: ChatItem) {
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

        ChatItemKind.ASSISTANT -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Codex",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Markdown(
                    content = item.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ChatItemKind.REASONING,
        ChatItemKind.COMMAND,
        ChatItemKind.FILE_CHANGE,
        ChatItemKind.TOOL,
        -> Unit

        ChatItemKind.ERROR -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun ActivityDisclosure(entry: ChatTimelineEntry.Activity) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    val title = if (entry.items.all { it.kind == ChatItemKind.REASONING }) {
        "思考过程"
    } else {
        "执行过程"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (entry.items.all { it.kind == ChatItemKind.REASONING }) {
                        Icons.Filled.Psychology
                    } else {
                        Icons.Filled.Tune
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(
                        activitySummary(entry.items),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "收起$title" else "展开$title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    entry.items.forEachIndexed { index, item ->
                        ActivityDetailRow(item)
                        if (index != entry.items.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 40.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDetailRow(item: ChatItem) {
    val icon: ImageVector = when (item.kind) {
        ChatItemKind.REASONING -> Icons.Filled.Psychology
        ChatItemKind.COMMAND -> Icons.Filled.Code
        ChatItemKind.FILE_CHANGE -> Icons.Filled.Description
        else -> Icons.Filled.Tune
    }
    val label = when {
        item.kind == ChatItemKind.REASONING -> "思考"
        item.kind == ChatItemKind.COMMAND -> "命令"
        item.kind == ChatItemKind.FILE_CHANGE -> "文件"
        item.status == "webSearch" -> "网页搜索"
        else -> "工具"
    }
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.status
                    ?.takeIf { it.isNotBlank() && it != "webSearch" }
                    ?.let { status ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (item.kind == ChatItemKind.COMMAND) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (item.status == "webSearch") 3 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            item.detail?.takeIf(String::isNotBlank)?.let { detail ->
                Spacer(Modifier.height(5.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        detail,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    modifier: Modifier = Modifier,
    state: ChatUiState,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onDecision: (String) -> Unit,
) {
    Surface(
        modifier = modifier,
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
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
