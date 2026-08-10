package com.agentdeck.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.chat.ApprovalKind
import com.agentdeck.app.domain.chat.ChatApproval
import com.agentdeck.app.domain.chat.ChatError
import com.agentdeck.app.domain.chat.ChatItem
import com.agentdeck.app.domain.chat.ChatItemKind
import com.agentdeck.app.domain.chat.ChatUiState
import com.agentdeck.app.domain.chat.ChatUserInputRequest
import com.agentdeck.app.domain.chat.FilePatch
import com.agentdeck.app.domain.chat.ToolUserInputQuestion
import com.agentdeck.app.ui.permissions.codexPermissionPresentation
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.di.ServiceLocator
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    cardId: String,
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(cardId)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val experienceLevel by ServiceLocator.experienceSettings.level.collectAsStateWithLifecycle()
    val showTechnicalDetails = experienceLevel.advancedEnabled
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Background Codex turns raise system notifications; ask once on Android 13+.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝只影响后台通知，不影响对话本身 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val listState = rememberLazyListState()
    val timeline = remember(state.items) { groupChatTimeline(state.items) }
    var timelineWasEmpty by remember { mutableStateOf(true) }
    var followLatest by remember { mutableStateOf(true) }
    var approvalSheetVisible by rememberSaveable { mutableStateOf(false) }
    var inputSheetVisible by rememberSaveable { mutableStateOf(false) }
    var longPressedItem by remember { mutableStateOf<ChatItem?>(null) }
    val clipboard = LocalClipboard.current
    val showScrollToBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 && !listState.isNearBottom()
        }
    }

    LaunchedEffect(state.approval?.requestId?.toString()) {
        approvalSheetVisible = state.approval != null
    }

    LaunchedEffect(state.userInputRequest?.requestId?.toString()) {
        inputSheetVisible = state.userInputRequest != null
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

    // Follow streamed tokens without collecting them into composition; only the
    // streaming message composable subscribes to streamingText for rendering.
    LaunchedEffect(state.streamingItemId) {
        if (state.streamingItemId == null) return@LaunchedEffect
        vm.streamingText.collect {
            if (followLatest) {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
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
            )
        },
        bottomBar = {
            ChatBottomBar(
                modifier = Modifier.imePadding(),
                state = state,
                showTechnicalDetails = showTechnicalDetails,
                onComposerChange = vm::updateComposer,
                onSend = vm::send,
                onStop = vm::stop,
                onCancelQueued = vm::cancelQueued,
                onShowApproval = { approvalSheetVisible = true },
                onShowUserInput = { inputSheetVisible = true },
                onModelOverride = vm::setModelOverride,
                onPermissionOverride = vm::setPermissionOverride,
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
                items(
                    timeline,
                    key = { it.key },
                    contentType = {
                        when (it) {
                            is ChatTimelineEntry.Message -> "message"
                            is ChatTimelineEntry.Activity -> "activity"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is ChatTimelineEntry.Message -> {
                            if (entry.item.kind == ChatItemKind.ASSISTANT &&
                                entry.item.id == state.streamingItemId
                            ) {
                                StreamingAssistantMessage(
                                    item = entry.item,
                                    streamingText = vm.streamingText,
                                )
                            } else {
                                ChatMessage(
                                    item = entry.item,
                                    showTechnicalDetails = showTechnicalDetails,
                                    onLongPress = { longPressedItem = entry.item },
                                )
                            }
                        }
                        is ChatTimelineEntry.Activity -> ActivityDisclosure(
                            entry = entry,
                            showTechnicalDetails = showTechnicalDetails,
                        )
                    }
                }
                if (state.isReconnecting) {
                    item(key = "reconnecting", contentType = "banner") {
                        ReconnectingBanner(onRetry = vm::connect)
                    }
                }
                state.error?.let { error ->
                    item(key = "error", contentType = "banner") {
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
        val approvalPatches: List<FilePatch> = approval.itemId
            ?.let { itemId -> state.items.firstOrNull { it.id == itemId }?.patches }
            .orEmpty()
        ModalBottomSheet(onDismissRequest = { approvalSheetVisible = false }) {
            ApprovalSheetContent(
                approval = approval,
                patches = approvalPatches,
                showTechnicalDetails = showTechnicalDetails,
                onDecision = { decision ->
                    approvalSheetVisible = false
                    vm.decideApproval(decision)
                },
            )
        }
    }

    state.userInputRequest?.takeIf { inputSheetVisible }?.let { request ->
        ModalBottomSheet(onDismissRequest = { inputSheetVisible = false }) {
            UserInputSheetContent(
                request = request,
                onSubmit = { answers ->
                    inputSheetVisible = false
                    vm.respondUserInput(answers)
                },
            )
        }
    }

    longPressedItem?.let { item ->
        MessageActionsDialog(
            item = item,
            onDismiss = { longPressedItem = null },
            onCopy = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AgentDeck message", item.text)))
                }
                longPressedItem = null
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
            onEditResend = {
                vm.updateComposer(item.text)
                longPressedItem = null
            },
        )
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

internal fun customerFacingChatError(error: ChatError, showTechnicalDetails: Boolean): String {
    if (showTechnicalDetails) return error.raw
    return when (error) {
        is ChatError.Auth -> "Codex 尚未登录，请先完成设置。"
        is ChatError.Model -> "模型服务暂时不可用，请检查设置后重试。"
        is ChatError.Network -> "Codex 连接中断，请重试。"
        is ChatError.Unknown -> "Codex 暂时无法继续，请重试。"
    }
}

@Composable
private fun StreamingAssistantMessage(
    item: ChatItem,
    streamingText: StateFlow<String?>,
) {
    // Only this composable recomposes per flush; plain Text avoids re-running the
    // Markdown parser per token. It swaps to Markdown once the turn completes.
    val streamed by streamingText.collectAsStateWithLifecycle()
    Text(
        streamed ?: item.text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ReconnectingBanner(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "Codex 连接已断开，正在自动重连",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("立即重试") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessage(
    item: ChatItem,
    showTechnicalDetails: Boolean,
    onLongPress: () -> Unit,
) {
    when (item.kind) {
        ChatItemKind.USER -> BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = maxWidth * 0.86f)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
            ) {
                Text(
                    item.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        ChatItemKind.ASSISTANT -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Markdown(
                content = item.text,
                modifier = Modifier.fillMaxWidth(),
                components = markdownComponents(
                    codeBlock = { model ->
                        MarkdownCodeFence(content = model.content, node = model.node) { _, code ->
                            CodeBlockWithCopy(code = code.orEmpty())
                        }
                    },
                ),
            )
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
                    customerFacingChatError(ChatError.from(item.text), showTechnicalDetails),
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
    var diffExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
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
            if (item.kind == ChatItemKind.FILE_CHANGE && item.patches.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (diffExpanded) "收起修改内容" else "查看修改内容",
                    modifier = Modifier.clickable { diffExpanded = !diffExpanded },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                AnimatedVisibility(visible = diffExpanded) {
                    Column(Modifier.padding(top = 6.dp)) {
                        DiffView(item.patches)
                    }
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
private fun ChatOverrideChips(
    state: ChatUiState,
    showPermissionOverride: Boolean,
    onModelOverride: (String?) -> Unit,
    onPermissionOverride: (CodexPermissionLevel?) -> Unit,
) {
    val currentModel = state.selectedModel ?: state.runtimeModel
    val currentModelLabel = state.availableModels.firstOrNull { it.id == currentModel }
        ?.displayName
        ?: currentModel
        ?: "默认模型"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.availableModels.isNotEmpty()) {
            OverrideChip(
                label = currentModelLabel,
                highlighted = state.selectedModel != null,
                options = listOf<String?>(null) + state.availableModels
                    .take(MAX_CHAT_MODEL_OPTIONS)
                    .map { it.id },
                optionLabel = { modelId ->
                    if (modelId == null) {
                        "对话模型 · ${state.runtimeModel ?: "Codex"}"
                    } else {
                        state.availableModels.firstOrNull { it.id == modelId }
                            ?.displayName
                            ?: modelId
                    }
                },
                onSelect = onModelOverride,
            )
        }
        if (showPermissionOverride) {
            OverrideChip(
                label = state.selectedPermission?.let { codexPermissionPresentation(it).title }
                    ?: "默认权限",
                highlighted = state.selectedPermission != null,
                options = listOf(null) + CodexPermissionLevel.entries,
                optionLabel = { level ->
                    level?.let { codexPermissionPresentation(it).title } ?: "默认权限"
                },
                onSelect = onPermissionOverride,
            )
        }
    }
}

private const val MAX_CHAT_MODEL_OPTIONS = 100

@Composable
private fun <T> OverrideChip(
    label: String,
    highlighted: Boolean,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = if (highlighted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChatBottomBar(
    modifier: Modifier = Modifier,
    state: ChatUiState,
    showTechnicalDetails: Boolean,
    onComposerChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelQueued: () -> Unit,
    onShowApproval: () -> Unit,
    onShowUserInput: () -> Unit,
    onModelOverride: (String?) -> Unit,
    onPermissionOverride: (CodexPermissionLevel?) -> Unit,
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
                PendingNotice(
                    icon = Icons.Filled.Security,
                    text = "Codex 正在等待你的确认",
                    onClick = onShowApproval,
                )
            }
            state.userInputRequest?.let {
                PendingNotice(
                    icon = Icons.Filled.QuestionAnswer,
                    text = "Codex 有问题需要你回答",
                    onClick = onShowUserInput,
                )
            }
            state.queued?.let { queued ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "已排队：${queued.text}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = onCancelQueued) {
                            Icon(
                                Icons.Filled.Cancel,
                                contentDescription = "取消排队消息",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            if (state.lastTurnTokens != null && showTechnicalDetails) {
                Text(
                    state.lastTurnTokens,
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.availableModels.isNotEmpty() || showTechnicalDetails) {
                ChatOverrideChips(
                    state = state,
                    showPermissionOverride = showTechnicalDetails,
                    onModelOverride = onModelOverride,
                    onPermissionOverride = onPermissionOverride,
                )
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
                    enabled = !state.isConnecting,
                    placeholder = {
                        Text(
                            when {
                                state.isConnecting -> "正在连接"
                                state.userInputRequest != null -> "请先回答 Codex 的问题"
                                state.isStreaming -> "给 Codex 补充指令"
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
                VoiceInputButton(
                    disabled = state.isConnecting,
                    onResult = { recognized ->
                        val current = state.composer
                        onComposerChange(
                            if (current.isBlank()) recognized else "$current $recognized",
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = if (state.isStreaming) {
                        if (state.composer.isNotBlank()) onSend else onStop
                    } else {
                        onSend
                    },
                    enabled = state.isStreaming || state.canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        when {
                            state.isStreaming && state.composer.isNotBlank() -> Icons.AutoMirrored.Filled.Send
                            state.isStreaming -> Icons.Filled.StopCircle
                            else -> Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = when {
                            state.isStreaming && state.composer.isNotBlank() -> "补充指令"
                            state.isStreaming -> "停止"
                            else -> "发送"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceInputButton(
    disabled: Boolean,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        listening = false
        val recognized = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!recognized.isNullOrBlank()) {
            onResult(recognized)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        launchRecognizer(speechLauncher, context) { listening = it }
    }
    FilledTonalIconButton(
        onClick = {
            if (listening) return@FilledTonalIconButton
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                launchRecognizer(speechLauncher, context) { listening = it }
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        enabled = !disabled,
        modifier = Modifier.size(48.dp),
    ) {
        if (listening) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Mic, contentDescription = "语音输入")
        }
    }
}

private fun launchRecognizer(
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    context: android.content.Context,
    setListening: (Boolean) -> Unit,
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    setListening(true)
    try {
        launcher.launch(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        setListening(false)
        Toast.makeText(context, "当前设备不支持语音输入", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PendingNotice(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onClick) { Text("查看") }
    }
}

@Composable
private fun MessageActionsDialog(
    item: ChatItem,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEditResend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                item.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = null,
        confirmButton = {
            TextButton(onClick = onCopy) { Text("复制全文") }
        },
        dismissButton = {
            if (item.kind == ChatItemKind.USER) {
                TextButton(onClick = onEditResend) { Text("编辑并重发") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserInputSheetContent(
    request: ChatUserInputRequest,
    onSubmit: (Map<String, List<String>>) -> Unit,
) {
    val answers = remember(request.requestId) {
        mutableStateOf(request.questions.associate { it.id to emptyList<String>() })
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.QuestionAnswer,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Codex 需要你的回答", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "回答后 Codex 会继续当前任务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        request.questions.forEach { question ->
            QuestionEditor(
                question = question,
                selected = answers.value[question.id].orEmpty(),
                onChange = { next ->
                    answers.value = answers.value + (question.id to next)
                },
            )
            Spacer(Modifier.height(14.dp))
        }
        Button(
            onClick = { onSubmit(answers.value) },
            enabled = request.questions.all { answers.value[it.id].orEmpty().isNotEmpty() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("提交回答") }
    }
}

@Composable
private fun QuestionEditor(
    question: ToolUserInputQuestion,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (question.header.isNotBlank()) {
            Text(
                question.header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(question.question, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (question.options.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.options.forEach { option ->
                    val isSelected = option.label in selected
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onChange(if (isSelected) emptyList() else listOf(option.label))
                        },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        if (question.isOther || question.options.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            val freeText = selected.firstOrNull().orEmpty()
            OutlinedTextField(
                value = freeText,
                onValueChange = { onChange(if (it.isBlank()) emptyList() else listOf(it)) },
                placeholder = { Text(if (question.options.isEmpty()) "输入回答" else "或输入自定义回答") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                visualTransformation = if (question.isSecret) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (question.isSecret) KeyboardType.Password else KeyboardType.Text,
                ),
            )
        }
    }
}

@Composable
private fun CodeBlockWithCopy(code: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("AgentDeck code", code.trimEnd())),
                            )
                        }
                        Toast.makeText(context, "已复制代码", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制代码",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                code.trimEnd(),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DiffView(patches: List<FilePatch>, maxHeight: androidx.compose.ui.unit.Dp = 280.dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        patches.forEach { patch ->
            Text(
                patch.path.ifBlank { "(未命名文件)" } + when (patch.kind) {
                    "add" -> " · 新增"
                    "delete" -> " · 删除"
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            patch.diff.lineSequence().forEach { line ->
                val color = when {
                    line.startsWith("+++") || line.startsWith("---") ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("+") -> MaterialTheme.colorScheme.primary
                    line.startsWith("-") -> MaterialTheme.colorScheme.error
                    line.startsWith("@@") -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    line.ifBlank { " " },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ApprovalSheetContent(
    approval: ChatApproval,
    patches: List<FilePatch>,
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
        if (approval.kind == ApprovalKind.FILE_CHANGE && patches.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "修改预览",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DiffView(patches)
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
