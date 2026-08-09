package com.agentdeck.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
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
import com.agentdeck.app.di.ServiceLocator
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    cardId: String,
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(cardId)),
) {
    val state by vm.state.collectAsState()
    val experienceLevel by ServiceLocator.experienceSettings.level.collectAsState()
    val showTechnicalDetails = experienceLevel.advancedEnabled
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val timeline = remember(state.items) { groupChatTimeline(state.items) }
    var timelineWasEmpty by remember { mutableStateOf(true) }
    var followLatest by remember { mutableStateOf(true) }
    var approvalSheetVisible by rememberSaveable { mutableStateOf(false) }
    val showScrollToBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 && !listState.isNearBottom()
        }
    }

    LaunchedEffect(state.approval?.requestId?.toString()) {
        approvalSheetVisible = state.approval != null
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            followLatest = listState.isNearBottom()
        }
    }

    LaunchedEffect(
        state.items.size,
        state.items.lastOrNull()?.id,
        state.items.lastOrNull()?.text?.length,
        state.isStreaming,
    ) {
        val initialTimeline = timelineWasEmpty && state.items.isNotEmpty()
        val shouldFollow = shouldFollowLatest(
            wasNearBottom = followLatest,
            initialTimeline = initialTimeline,
            lastItem = state.items.lastOrNull(),
        )
        timelineWasEmpty = state.items.isEmpty()
        if (shouldFollow && state.items.isNotEmpty()) {
            withFrameNanos { }
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                listState.scrollToItem(lastIndex)
                followLatest = true
            }
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
                        chatRuntimeLabel(state.runtimeProvider, state.runtimeModel)?.let { detail ->
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
                    if (showTechnicalDetails) {
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
                            Icon(Icons.Filled.Terminal, contentDescription = "在终端中打开")
                        }
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
                onShowApproval = { approvalSheetVisible = true },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
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
                        is ChatTimelineEntry.Message -> ChatMessage(
                            item = entry.item,
                            showTechnicalDetails = showTechnicalDetails,
                        )
                        is ChatTimelineEntry.Activity -> ActivityDisclosure(
                            entry = entry,
                            showTechnicalDetails = showTechnicalDetails,
                        )
                    }
                }
                state.error?.let { error ->
                    item(key = "error") {
                        ErrorBanner(
                            error = customerFacingChatError(error, showTechnicalDetails),
                            onRetry = vm::connect,
                        )
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
            AnimatedVisibility(
                visible = showScrollToBottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            val lastIndex = listState.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
                        }
                    },
                ) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = "回到底部")
                }
            }
        }
    }

    state.approval?.takeIf { approvalSheetVisible }?.let { approval ->
        ModalBottomSheet(onDismissRequest = { approvalSheetVisible = false }) {
            ApprovalSheetContent(
                approval = approval,
                showTechnicalDetails = showTechnicalDetails,
                onDecision = { decision ->
                    approvalSheetVisible = false
                    vm.decideApproval(decision)
                },
            )
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

internal fun shouldFollowLatest(
    wasNearBottom: Boolean,
    initialTimeline: Boolean,
    lastItem: ChatItem?,
): Boolean = initialTimeline || wasNearBottom || lastItem?.id?.startsWith("local-user-") == true

internal fun chatRuntimeLabel(provider: String?, model: String?): String? = listOfNotNull(
    provider?.trim()?.takeIf(String::isNotEmpty),
    model?.trim()?.takeIf(String::isNotEmpty),
)
    .distinctBy(String::lowercase)
    .joinToString(" · ")
    .takeIf(String::isNotEmpty)

internal fun customerFacingChatError(error: String, showTechnicalDetails: Boolean): String {
    if (showTechnicalDetails) return error
    val normalized = error.lowercase()
    return when {
        "登录" in error || "auth" in normalized || "unauthorized" in normalized ->
            "Codex 尚未登录，请先完成设置。"
        "模型" in error || "provider" in normalized || "model" in normalized ->
            "模型服务暂时不可用，请检查设置后重试。"
        "连接" in error || "connect" in normalized || "timeout" in normalized ->
            "Codex 连接中断，请重试。"
        else -> "Codex 暂时无法继续，请重试。"
    }
}

@Composable
private fun ChatMessage(item: ChatItem, showTechnicalDetails: Boolean) {
    when (item.kind) {
        ChatItemKind.USER -> BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = maxWidth * 0.86f),
            ) {
                Text(
                    item.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        ChatItemKind.ASSISTANT -> Markdown(
            content = item.text,
            modifier = Modifier.fillMaxWidth(),
        )

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
                    customerFacingChatError(item.text, showTechnicalDetails),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun ActivityDisclosure(
    entry: ChatTimelineEntry.Activity,
    showTechnicalDetails: Boolean,
) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    val reasoningOnly = entry.items.all { it.kind == ChatItemKind.REASONING }
    val title = if (reasoningOnly) "已完成思考" else "处理过程"
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (reasoningOnly) Icons.Filled.Psychology else Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    activitySummary(entry.items, showTechnicalDetails),
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
            Column(Modifier.padding(start = 38.dp)) {
                entry.items.forEachIndexed { index, item ->
                    ActivityDetailRow(item, showTechnicalDetails)
                    if (index != entry.items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityDetailRow(item: ChatItem, showTechnicalDetails: Boolean) {
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
        modifier = Modifier.padding(vertical = 9.dp),
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
                    ?.takeIf { showTechnicalDetails }
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
                activityDetailText(item, showTechnicalDetails),
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
            item.detail
                ?.takeIf { showTechnicalDetails }
                ?.takeIf(String::isNotBlank)
                ?.let { detail ->
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
    onShowApproval: () -> Unit,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            state.approval?.let {
                ApprovalWaitingNotice(onClick = onShowApproval)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = state.composer,
                    onValueChange = onComposerChange,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isConnecting && state.approval == null,
                    placeholder = {
                        Text(
                            when {
                                state.isConnecting -> "正在连接"
                                state.approval != null -> "请先确认 Codex 的操作"
                                else -> "发消息给 Codex"
                            },
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        disabledIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
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
private fun ApprovalWaitingNotice(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Security,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Codex 正在等待你的确认",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onClick) { Text("查看") }
    }
}

@Composable
private fun ApprovalSheetContent(
    approval: ChatApproval,
    showTechnicalDetails: Boolean,
    onDecision: (String) -> Unit,
) {
    val icon = when (approval.kind) {
        ApprovalKind.COMMAND -> Icons.Filled.Code
        ApprovalKind.FILE_CHANGE -> Icons.Filled.Description
        ApprovalKind.PERMISSIONS -> Icons.Filled.LockOpen
    }
    val summary = when (approval.kind) {
        ApprovalKind.COMMAND -> "Codex 想在本地环境中运行下面的命令。"
        ApprovalKind.FILE_CHANGE -> "Codex 想修改当前项目中的文件。"
        ApprovalKind.PERMISSIONS -> "Codex 需要本轮额外的文件或网络权限。"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(approval.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showTechnicalDetails) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "请求类型：${approval.kind.name.lowercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Text(
                approval.detail,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (approval.kind == ApprovalKind.COMMAND) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onDecision("accept") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("仅允许这次") }
        Spacer(Modifier.height(8.dp))
        if (showTechnicalDetails) {
            OutlinedButton(
                onClick = { onDecision("acceptForSession") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("本次会话始终允许") }
        }
        TextButton(
            onClick = { onDecision("decline") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("拒绝") }
    }
}
