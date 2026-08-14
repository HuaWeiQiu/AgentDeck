package com.agentdeck.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.agentdeck.app.domain.chat.ChatAttachment
import com.agentdeck.app.domain.chat.ChatAttachmentKind
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
import com.agentdeck.app.data.voice.VoskDictationEngine
import kotlinx.coroutines.launch
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownAnimations
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.addAttachments(uris) }
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
    var approvalSheetVisible by rememberSaveable { mutableStateOf(false) }
    var hostWriteSheetVisible by rememberSaveable { mutableStateOf(false) }
    var inputSheetVisible by rememberSaveable { mutableStateOf(false) }
    var chatSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var longPressedItem by remember { mutableStateOf<ChatItem?>(null) }
    val onMessageLongPress = remember<(ChatItem) -> Unit> {
        { item -> longPressedItem = item }
    }
    val clipboard = LocalClipboard.current

    LaunchedEffect(state.approval?.requestId?.toString()) {
        approvalSheetVisible = state.approval != null
    }

    LaunchedEffect(state.hostWriteApproval?.id) {
        hostWriteSheetVisible = state.hostWriteApproval != null
    }

    LaunchedEffect(state.userInputRequest?.requestId?.toString()) {
        inputSheetVisible = state.userInputRequest != null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val settingsAvailable = state.availableModels.isNotEmpty() || showTechnicalDetails
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    val currentModel = state.selectedModel ?: state.runtimeModel
                    val currentModelLabel = state.availableModels
                        .firstOrNull { it.id == currentModel }
                        ?.displayName
                        ?: currentModel
                        ?: "Codex"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.card?.name ?: "Codex",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (settingsAvailable) {
                            Surface(
                                onClick = { chatSettingsVisible = true },
                                modifier = Modifier.widthIn(max = 230.dp),
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        currentModelLabel,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Icon(
                                        Icons.Filled.ExpandMore,
                                        contentDescription = "选择对话模型和权限",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        } else {
                            Text(
                                currentModelLabel,
                                modifier = Modifier.widthIn(max = 230.dp),
                                style = MaterialTheme.typography.bodyLarge,
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
                onAddAttachments = { attachmentLauncher.launch(arrayOf("*/*")) },
                onRemoveAttachment = vm::removeAttachment,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.hostWorkspaceBanner?.let { banner ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                ) {
                    Text(
                        banner,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            ChatTranscript(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transcriptState = vm.transcriptState,
                markdownDocuments = vm.markdownDocuments,
                streamingText = vm.streamingText,
                showTechnicalDetails = showTechnicalDetails,
                onLoadOlder = vm::loadOlderHistory,
                onMarkdownNeeded = vm::requestMarkdown,
                onVisibleItems = vm::reportVisibleItems,
                onMarkdownTouched = vm::touchMarkdown,
                onLoadGap = vm::refetchPage,
                onRetry = vm::connect,
                onLongPress = onMessageLongPress,
            )
        }
    }

    state.approval?.takeIf { approvalSheetVisible }?.let { approval ->
        val approvalPatches: List<FilePatch> = approval.itemId
            ?.let(vm::approvalPatches)
            .orEmpty()
        ModalBottomSheet(
            onDismissRequest = {
                approvalSheetVisible = false
                vm.decideApproval("cancel")
            },
        ) {
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

    state.hostWriteApproval?.takeIf { hostWriteSheetVisible }?.let { hostWrite ->
        ModalBottomSheet(
            onDismissRequest = {
                hostWriteSheetVisible = false
                vm.decideHostWrite(false)
            },
        ) {
            HostWriteApprovalSheetContent(
                summary = hostWrite.summary,
                onDecision = { allow, forSession ->
                    hostWriteSheetVisible = false
                    vm.decideHostWrite(allow, forSession = forSession)
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

    if (chatSettingsVisible) {
        ModalBottomSheet(onDismissRequest = { chatSettingsVisible = false }) {
            ChatSettingsSheetContent(
                state = state,
                showPermissionOverride = showTechnicalDetails,
                onModelOverride = vm::setModelOverride,
                onPermissionOverride = vm::setPermissionOverride,
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

@Composable
internal fun ChatTranscript(
    transcriptState: StateFlow<ChatTranscriptUiState>,
    markdownDocuments: StateFlow<Map<String, ChatMarkdownDocument>>,
    streamingText: StateFlow<String?>,
    showTechnicalDetails: Boolean,
    onLoadOlder: () -> Unit,
    onMarkdownNeeded: (String, String) -> Unit,
    onVisibleItems: (Set<String>) -> Unit,
    onMarkdownTouched: (String) -> Unit,
    onLoadGap: (String) -> Unit,
    onRetry: () -> Unit,
    onLongPress: (ChatItem) -> Unit,
    modifier: Modifier = Modifier,
    listTestTag: String? = null,
) {
    val transcript by transcriptState.collectAsStateWithLifecycle()
    val documents by markdownDocuments.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val projection = remember { TimelinePageProjection() }
    val timeline = remember(
        transcript.items,
        transcript.pages,
        transcript.tailIds,
        transcript.refetchingPageKeys,
        documents,
        transcript.streamingItemId,
    ) {
        projection.project(
            ChatTranscriptStoreState(
                items = transcript.items,
                pages = transcript.pages,
                tailIds = transcript.tailIds,
                streamingItemId = transcript.streamingItemId,
                refetchingPageKeys = transcript.refetchingPageKeys,
            ),
            documents,
        ).entries
    }
    var expandedActivities by remember { mutableStateOf(setOf<String>()) }
    val displayEntries = remember(timeline, expandedActivities) {
        expandTimeline(timeline, expandedActivities)
    }
    var timelineWasEmpty by remember { mutableStateOf(true) }
    var followLatest by remember { mutableStateOf(true) }
    val showScrollToBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount > 0 && !listState.isNearBottom()
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            followLatest = false
        } else {
            followLatest = listState.isNearBottom()
        }
    }

    LaunchedEffect(listState, displayEntries, onVisibleItems, onLoadGap) {
        snapshotFlow {
            val headerOffset = if (transcript.isLoadingOlder) 1 else 0
            val ids = LinkedHashSet<String>()
            val gaps = ArrayList<String>()
            listState.layoutInfo.visibleItemsInfo.forEach { info ->
                when (val entry = displayEntries.getOrNull(info.index - headerOffset)) {
                    is ChatTimelineEntry.Gap -> entry.cursor?.let(gaps::add)
                    is ChatTimelineEntry.Message -> ids += entry.item.id
                    is ChatTimelineEntry.AssistantBlock -> ids += entry.item.id
                    is ChatTimelineEntry.Activity -> entry.items.forEach { ids += it.id }
                    is ChatTimelineEntry.ActivityHeader -> Unit
                    is ChatTimelineEntry.ActivityChild -> ids += entry.item.id
                    null -> Unit
                }
            }
            ids to gaps
        }.distinctUntilChanged().collect { (ids, gaps) ->
            onVisibleItems(ids)
            gaps.forEach(onLoadGap)
        }
    }

    LaunchedEffect(listState, transcript.hasOlderHistory) {
        if (!transcript.hasOlderHistory) return@LaunchedEffect
        snapshotFlow {
            listState.isScrollInProgress && listState.firstVisibleItemIndex == 0
        }.distinctUntilChanged().filter { it }.collect {
            onLoadOlder()
        }
    }

    LaunchedEffect(
        transcript.items.size,
        transcript.items.lastOrNull()?.id,
        transcript.items.lastOrNull()?.text?.length,
        transcript.isStreaming,
        timeline.size,
        timeline.lastOrNull()?.key,
    ) {
        val initialTimeline = timelineWasEmpty && transcript.items.isNotEmpty()
        val shouldFollow = shouldFollowLatest(
            wasNearBottom = followLatest,
            initialTimeline = initialTimeline,
            lastItem = transcript.items.lastOrNull(),
        )
        timelineWasEmpty = transcript.items.isEmpty()
        if (shouldFollow && transcript.items.isNotEmpty()) {
            withFrameNanos { }
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                listState.scrollToItem(lastIndex)
                followLatest = true
            }
        }
    }

    // Only this effect and the active message collect per-token updates.
    LaunchedEffect(transcript.streamingItemId) {
        if (transcript.streamingItemId == null) return@LaunchedEffect
        streamingText.collect {
            if (followLatest) {
                withFrameNanos { }
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            }
        }
    }

    ChatMarkdownEnvironment {
        Box(modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (listTestTag == null) {
                            Modifier
                        } else {
                            Modifier.testTag(listTestTag)
                        },
                    ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.Top,
            ) {
            if (transcript.isLoadingOlder) {
                item(key = "loading-older", contentType = "history-loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
            itemsIndexed(
                displayEntries,
                key = { _, entry -> entry.key },
                contentType = { _, entry -> entry.contentType },
            ) { index, entry ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = timelineTopPadding(index, entry)),
                ) {
                    when (entry) {
                        is ChatTimelineEntry.Message -> {
                            if (entry.item.kind == ChatItemKind.ASSISTANT &&
                                entry.item.id == transcript.streamingItemId
                            ) {
                                StreamingAssistantMessage(
                                    item = entry.item,
                                    streamingText = streamingText,
                                )
                            } else {
                                ChatMessage(
                                    item = entry.item,
                                    showTechnicalDetails = showTechnicalDetails,
                                    onMarkdownNeeded = onMarkdownNeeded,
                                    onLongPress = { onLongPress(entry.item) },
                                )
                            }
                        }
                        is ChatTimelineEntry.Activity -> ActivityDisclosureHeader(
                            items = entry.items,
                            expanded = false,
                            showTechnicalDetails = showTechnicalDetails,
                            onToggle = { expandedActivities = expandedActivities + entry.key },
                        )
                        is ChatTimelineEntry.ActivityHeader -> ActivityDisclosureHeader(
                            items = entry.group.items,
                            expanded = true,
                            showTechnicalDetails = showTechnicalDetails,
                            onToggle = { expandedActivities = expandedActivities - entry.key },
                        )
                        is ChatTimelineEntry.ActivityChild -> Column(
                            Modifier.padding(start = 38.dp),
                        ) {
                            ActivityDetailRow(entry.item, showTechnicalDetails)
                        }
                        is ChatTimelineEntry.Gap -> HistoryGapRow(entry)
                        is ChatTimelineEntry.AssistantBlock -> AssistantMarkdownBlock(
                            entry = entry,
                            onTouched = onMarkdownTouched,
                            onLongPress = { onLongPress(entry.item) },
                        )
                    }
                }
            }
            if (transcript.isReconnecting) {
                item(key = "reconnecting", contentType = "banner") {
                    Box(Modifier.padding(top = 14.dp)) {
                        ReconnectingBanner(onRetry = onRetry)
                    }
                }
            }
            transcript.error?.let { error ->
                item(key = "error", contentType = "banner") {
                    Box(Modifier.padding(top = 14.dp)) {
                        ErrorBanner(
                            error = customerFacingChatError(error, showTechnicalDetails),
                            onRetry = onRetry,
                        )
                    }
                }
            }
            if (transcript.isStreaming &&
                transcript.items.lastOrNull()?.kind != ChatItemKind.ASSISTANT
            ) {
                item(key = "responding") {
                    Row(
                        modifier = Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
            if (transcript.isConnecting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChatMarkdownEnvironment(content: @Composable () -> Unit) {
    val components = remember {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(content = model.content, node = model.node) { code, _, _ ->
                    CodeBlockWithCopy(code = code)
                }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(content = model.content, node = model.node) { code, _, _ ->
                    CodeBlockWithCopy(code = code)
                }
            },
        )
    }
    val imageTransformer = remember { NoOpImageTransformerImpl() }
    CompositionLocalProvider(
        LocalMarkdownColors provides markdownColor(),
        LocalMarkdownTypography provides markdownTypography(),
        LocalMarkdownPadding provides markdownPadding(),
        LocalMarkdownDimens provides markdownDimens(),
        LocalImageTransformer provides imageTransformer,
        LocalMarkdownComponents provides components,
        LocalMarkdownAnimations provides markdownAnimations(animateTextSize = { this }),
        content = content,
    )
}

private fun androidx.compose.foundation.lazy.LazyListState.isNearBottom(): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return true
    val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisible >= layout.totalItemsCount - AUTO_FOLLOW_THRESHOLD
}

private const val AUTO_FOLLOW_THRESHOLD = 3

private fun timelineTopPadding(index: Int, entry: ChatTimelineEntry): androidx.compose.ui.unit.Dp = when {
    index == 0 -> 0.dp
    entry is ChatTimelineEntry.AssistantBlock && !entry.isFirst -> 0.dp
    entry is ChatTimelineEntry.ActivityChild -> 0.dp
    entry is ChatTimelineEntry.ActivityHeader -> 0.dp
    else -> 14.dp
}

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
        is ChatError.Auth -> "未登录模型服务，请到设置完成登录"
        is ChatError.Model -> "模型连不上，请检查设置后重试"
        is ChatError.Network -> "连接已断开，请重试"
        is ChatError.Attachment -> error.raw
        is ChatError.Unknown -> "回复失败，请重试"
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
                "连接已断开，正在自动重连…",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) { Text("立即重连") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessage(
    item: ChatItem,
    showTechnicalDetails: Boolean,
    onMarkdownNeeded: (String, String) -> Unit,
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

        ChatItemKind.ASSISTANT -> {
            LaunchedEffect(item.id, item.text) {
                onMarkdownNeeded(item.id, item.text)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在排版回复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    customerFacingChatError(ChatError.from(item.text), showTechnicalDetails),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantMarkdownBlock(
    entry: ChatTimelineEntry.AssistantBlock,
    onTouched: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    // Touch-only: visibility is reported solely by ChatTranscript's viewport
    // snapshotFlow. A singleton replace here shrinks the markdown window and
    // evicts already-parsed neighbours, ping-ponging them back to the spinner.
    LaunchedEffect(entry.document.messageId) {
        onTouched(entry.document.messageId)
    }
    CompositionLocalProvider(
        LocalReferenceLinkHandler provides entry.document.referenceLinkHandler,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
        ) {
            MarkdownElement(
                node = entry.block.node,
                components = LocalMarkdownComponents.current,
                content = entry.document.content,
                includeSpacer = !entry.isFirst,
                skipLinkDefinition = true,
            )
        }
    }
}

@Composable
private fun ActivityDisclosureHeader(
    items: List<ChatItem>,
    expanded: Boolean,
    showTechnicalDetails: Boolean,
    onToggle: () -> Unit,
) {
    val reasoningOnly = items.all { it.kind == ChatItemKind.REASONING }
    val title = if (reasoningOnly) "已完成思考" else "处理过程"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
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
                activitySummary(items, showTechnicalDetails),
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
}

@Composable
private fun HistoryGapRow(entry: ChatTimelineEntry.Gap) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (entry.loading) "正在恢复更早的对话…" else "向上恢复 ${entry.itemCount} 条更早的对话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun ChatSettingsSheetContent(
    state: ChatUiState,
    showPermissionOverride: Boolean,
    onModelOverride: (String?) -> Unit,
    onPermissionOverride: (CodexPermissionLevel?) -> Unit,
) {
    val currentModel = state.selectedModel ?: state.runtimeModel
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "settings-title") {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("对话设置", style = MaterialTheme.typography.titleLarge)
                    state.runtimeProvider?.let { provider ->
                        Text(
                            provider,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item(key = "model-section") {
            SettingsSectionLabel("模型")
        }
        if (state.availableModels.isNotEmpty()) {
            item(key = "model-default") {
                SettingsChoiceRow(
                    title = "跟随卡片默认",
                    detail = state.runtimeModel ?: "Codex 默认模型",
                    selected = state.selectedModel == null,
                    onClick = { onModelOverride(null) },
                )
            }
            items(
                items = state.availableModels.take(MAX_CHAT_MODEL_OPTIONS),
                key = { "model-${it.id}" },
            ) { model ->
                SettingsChoiceRow(
                    title = model.displayName.ifBlank { model.id },
                    detail = model.id.takeIf { it != model.displayName },
                    selected = currentModel == model.id && state.selectedModel != null,
                    onClick = { onModelOverride(model.id) },
                )
            }
        } else {
            item(key = "model-current") {
                SettingsChoiceRow(
                    title = state.runtimeModel ?: "Codex 默认模型",
                    detail = "当前模型服务未提供可选模型列表",
                    selected = true,
                    onClick = {},
                    enabled = false,
                )
            }
        }
        if (showPermissionOverride) {
            item(key = "permission-divider") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SettingsSectionLabel("权限")
            }
            item(key = "permission-default") {
                SettingsChoiceRow(
                    title = "跟随默认权限",
                    detail = null,
                    selected = state.selectedPermission == null,
                    onClick = { onPermissionOverride(null) },
                )
            }
            items(
                items = CodexPermissionLevel.entries,
                key = { "permission-${it.name}" },
            ) { level ->
                val presentation = codexPermissionPresentation(level)
                SettingsChoiceRow(
                    title = presentation.title,
                    detail = presentation.description,
                    selected = state.selectedPermission == level,
                    onClick = { onPermissionOverride(level) },
                )
            }
        }
    }
}

private const val MAX_CHAT_MODEL_OPTIONS = 100

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
    onAddAttachments: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
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
                            queued.text.takeIf(String::isNotBlank)?.let { "已排队：$it" }
                                ?: "已排队 ${queued.attachments.size} 个附件",
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
            if (state.attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.attachments.forEach { attachment ->
                        AttachmentChip(attachment, onRemoveAttachment)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                FilledTonalIconButton(
                    onClick = onAddAttachments,
                    enabled = !state.isConnecting && !state.isImportingAttachment &&
                        state.queued == null && state.attachments.size < 4,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (state.isImportingAttachment) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.AttachFile, contentDescription = "添加图片或文件")
                    }
                }
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = state.composer,
                    onValueChange = onComposerChange,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isConnecting && state.queued == null,
                    placeholder = {
                        Text(
                            when {
                                state.isConnecting -> "正在连接"
                                state.queued != null -> "请先取消排队消息"
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
                    disabled = state.isConnecting || state.queued != null,
                    composer = state.composer,
                    onComposerChange = onComposerChange,
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = if (state.isStreaming) {
                        if (state.composer.isNotBlank() || state.attachments.isNotEmpty()) onSend else onStop
                    } else {
                        onSend
                    },
                    enabled = state.isStreaming || state.canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        when {
                            state.isStreaming &&
                                (state.composer.isNotBlank() || state.attachments.isNotEmpty()) ->
                                Icons.AutoMirrored.Filled.Send
                            state.isStreaming -> Icons.Filled.StopCircle
                            else -> Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = when {
                            state.isStreaming &&
                                (state.composer.isNotBlank() || state.attachments.isNotEmpty()) -> "补充指令"
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
private fun AttachmentChip(
    attachment: ChatAttachment,
    onRemove: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (attachment.kind == ChatAttachmentKind.IMAGE) {
                    Icons.Filled.Image
                } else {
                    Icons.Filled.Description
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.widthIn(max = 160.dp)) {
                Text(
                    attachment.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    buildString {
                        append(formatAttachmentSize(attachment.sizeBytes))
                        if (attachment.kind == ChatAttachmentKind.FILE) {
                            append(" · 已解析")
                            if (attachment.wasTruncated) append("（已截断）")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                )
            }
            IconButton(
                onClick = { onRemove(attachment.id) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = "移除 ${attachment.name}",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun VoiceInputButton(
    disabled: Boolean,
    composer: String,
    onComposerChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val composerState = rememberUpdatedState(composer)
    val onComposerChangeState = rememberUpdatedState(onComposerChange)
    var listening by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    val baseBeforeListen = remember { mutableStateOf("") }
    val committedSpoken = remember { mutableStateOf("") }
    val lastPartial = remember { mutableStateOf("") }
    val sessionId = remember { mutableStateOf(0) }
    val engine = remember(context) { VoskDictationEngine(context.applicationContext) }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    fun joinVoice(base: String, committed: String, partial: String = ""): String =
        listOf(base, committed, partial).filter { it.isNotBlank() }.joinToString(" ")

    fun publish(committed: String, partial: String = "") {
        // Always push into the chat composer; this is the wire to the input box.
        val text = joinVoice(baseBeforeListen.value, committed, partial)
        android.util.Log.i("AgentDeckVoice", "publish composer='$text'")
        onComposerChangeState.value(text)
    }

    fun finishSession(extra: String, sid: Int) {
        if (sessionId.value != sid || !listening) return
        val piece = extra.ifBlank { lastPartial.value }
        if (piece.isNotBlank()) {
            committedSpoken.value = listOf(committedSpoken.value, piece)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        lastPartial.value = ""
        publish(committedSpoken.value)
        listening = false
    }

    fun stopVoice() {
        if (!listening) return
        val sid = sessionId.value
        // Do NOT clear listening before final callback — otherwise last partial is dropped.
        engine.stop()
        // Safety: if Vosk never delivers onFinal, still commit last partial shortly after.
        scope.launch {
            kotlinx.coroutines.delay(600)
            finishSession(extra = "", sid = sid)
        }
    }

    fun startVosk() {
        if (preparing || listening) return
        baseBeforeListen.value = composerState.value
        committedSpoken.value = ""
        lastPartial.value = ""
        val sid = sessionId.value + 1
        sessionId.value = sid
        scope.launch {
            preparing = true
            if (!engine.isModelReady()) {
                Toast.makeText(
                    context,
                    "首次语音输入需下载离线中文包（约 42MB，仅一次）",
                    Toast.LENGTH_LONG,
                ).show()
            }
            val ready = engine.ensureReady()
            preparing = false
            ready.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message?.take(80) ?: "语音包准备失败，请检查网络后重试",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            if (sessionId.value != sid) return@launch
            listening = true
            engine.start(
                onPartial = { partial ->
                    if (sessionId.value == sid) {
                        lastPartial.value = partial
                        // Always write through to the input field while the session is alive.
                        publish(committedSpoken.value, partial)
                    }
                },
                onUtterance = { utterance ->
                    if (sessionId.value == sid && listening) {
                        // A finished phrase mid-session (silence endpoint).
                        committedSpoken.value = listOf(committedSpoken.value, utterance)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        lastPartial.value = ""
                        publish(committedSpoken.value)
                    }
                },
                onFinal = { text ->
                    finishSession(extra = text, sid = sid)
                },
                onError = { message ->
                    finishSession(extra = "", sid = sid)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        startVosk()
    }

    FilledTonalIconButton(
        onClick = {
            when {
                listening -> stopVoice()
                preparing -> Unit
                else -> {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) startVosk() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        },
        enabled = !disabled && !preparing,
        modifier = Modifier.size(48.dp),
    ) {
        if (listening || preparing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Mic, contentDescription = "语音输入")
        }
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
            .verticalScroll(rememberScrollState())
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
private fun HostWriteApprovalSheetContent(
    summary: String,
    onDecision: (allow: Boolean, forSession: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        Text("允许写入文件？", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "将修改你授权的本机文件夹。拒绝不影响继续聊天。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Text(
                summary,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onDecision(true, false) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("允许一次")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onDecision(true, true) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("本会话允许")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onDecision(false, false) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("拒绝")
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
        ApprovalKind.MCP_TOOL -> Icons.Filled.Extension
    }
    val summary = when (approval.kind) {
        ApprovalKind.COMMAND -> "Codex 想在本地环境中运行下面的命令。"
        ApprovalKind.FILE_CHANGE -> "Codex 想修改当前项目中的文件。"
        ApprovalKind.PERMISSIONS -> "Codex 需要本轮额外的文件或网络权限。"
        ApprovalKind.MCP_TOOL -> "Codex 想调用当前对话已启用的 MCP 工具。"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
        val showSessionApproval = approval.supportsSessionApproval &&
            (approval.kind == ApprovalKind.MCP_TOOL || showTechnicalDetails)
        if (showSessionApproval) {
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
