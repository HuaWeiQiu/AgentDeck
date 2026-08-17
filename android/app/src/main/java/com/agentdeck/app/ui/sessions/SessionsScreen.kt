package com.agentdeck.app.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.cards.CardDraft
import com.agentdeck.app.domain.chat.ConversationIdentityPolicy
import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.launch.CliAdapterRegistry
import com.agentdeck.app.data.runtime.SessionAgentPrefetch
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.settings.ConversationModePolicy
import com.agentdeck.app.domain.settings.ConversationMode
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ConversationIdentity
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import com.agentdeck.app.domain.model.isCodexResponsesCompatible
import com.agentdeck.app.ui.common.DEFAULT_MAX_VISIBLE_MODELS
import com.agentdeck.app.ui.common.filterSelectableModels
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.ui.setup.customerSetupPresentation
import com.agentdeck.app.ui.permissions.codexPermissionPresentation
import com.agentdeck.app.ui.permissions.permissionSelectionLabel
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import com.agentdeck.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenSetup: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onOpenDshWeb: (url: String) -> Unit = {},
    onOpenPiChat: (cardId: String, title: String) -> Unit = { _, _ -> },
    onOpenLightChat: (cardId: String) -> Unit = {},
    onOpenRuntimes: () -> Unit = {},
    vm: SessionsViewModel = viewModel(),
) {
    val cardItems by vm.cardItems.collectAsStateWithLifecycle()
    val cardsHydrated by vm.cardsHydrated.collectAsStateWithLifecycle()
    val visibleItems by vm.visibleItems.collectAsStateWithLifecycle()
    val primaryVisibleItems by vm.primaryVisibleItems.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val allExtensions by vm.extensions.collectAsStateWithLifecycle()
    val selectableExtensions by vm.selectableExtensions.collectAsStateWithLifecycle()
    val setupState by vm.setupState.collectAsStateWithLifecycle()
    val conversationMode by vm.conversationMode.collectAsStateWithLifecycle()
    val availableAdapters by vm.availableAdapters.collectAsStateWithLifecycle()
    val experienceLevel by vm.experienceLevel.collectAsStateWithLifecycle()
    val defaultPermissionLevel by vm.defaultPermissionLevel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CardDraft?>(null) }
    var deleting by remember { mutableStateOf<AgentCard?>(null) }
    var renaming by remember { mutableStateOf<SessionCardUi?>(null) }
    var runtimeMissingDialog by remember { mutableStateOf(false) }
    var openingExternal by remember { mutableStateOf(false) }
    var modeMenuExpanded by remember { mutableStateOf(false) }
    val showLocalDataNotice by vm.showLocalDataNotice.collectAsStateWithLifecycle()
    val showSetupBanner = shouldShowSetupBanner(
        conversationMode = conversationMode,
        setupState = setupState,
    )
    val activeItems = remember(primaryVisibleItems) { primaryVisibleItems.filter { !it.card.archived } }
    val archivedItems = remember(primaryVisibleItems) { primaryVisibleItems.filter { it.card.archived } }
    val runtimeReady = setupState.canStartChat

    fun tryCreateSession() {
        // 轻聊不依赖 Codex runtime；开发 Agent 在保存/进入时再校验环境。
        editor = vm.newDraft()
    }

    fun enterSession(item: SessionCardUi) {
        if (openingExternal) return
        val recipeId = item.card.recipeId
        when {
            CliAdapterRegistry.usesLightChat(recipeId) -> {
                vm.touchActivity(item.card.id)
                onOpenLightChat(item.card.id)
            }
            !runtimeReady -> runtimeMissingDialog = true
            CliAdapterRegistry.requiresCodexNativeChat(recipeId) -> {
                if (!canStartConversation(setupState, item.profile)) {
                    onOpenSetup()
                } else {
                    onOpenChat(item.card.id)
                }
            }
            recipeId == "recipe_deepseek_harness" -> {
                if (!vm.isDshRuntimeReady()) {
                    Toast.makeText(
                        context,
                        "请先安装 dsh：设置 → 运行环境",
                        Toast.LENGTH_LONG,
                    ).show()
                    onOpenRuntimes()
                    return
                }
                openingExternal = true
                scope.launch {
                    val result = vm.openDshWebUrl()
                    openingExternal = false
                    result.fold(
                        onSuccess = { url ->
                            vm.touchActivity(item.card.id)
                            onOpenDshWeb(url)
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "无法打开 dsh",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            }
            recipeId == "recipe_pi" -> {
                if (!vm.isPiRuntimeReady()) {
                    Toast.makeText(
                        context,
                        "请先安装 pi：设置 → 运行环境",
                        Toast.LENGTH_LONG,
                    ).show()
                    onOpenRuntimes()
                    return
                }
                vm.touchActivity(item.card.id)
                onOpenPiChat(
                    item.card.id,
                    item.displayTitle.ifBlank { item.card.name },
                )
            }
            else -> {
                Toast.makeText(context, "该 Agent 尚未支持从对话打开", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            AgentDeckTopBar(
                title = "对话",
                centerTitle = true,
                // 左：模式；中：对话；右：新建
                navigationIcon = {
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
                        TextButton(onClick = { modeMenuExpanded = true }) {
                            Text(conversationMode.title)
                        }
                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false },
                            modifier = Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp),
                            ),
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            shadowElevation = 6.dp,
                        ) {
                            ConversationMode.entries.forEach { mode ->
                                val selected = mode == conversationMode
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            mode.title,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    },
                                    onClick = {
                                        modeMenuExpanded = false
                                        if (!selected) {
                                            vm.setConversationMode(mode)
                                        }
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { tryCreateSession() },
                        enabled = availableAdapters.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建会话")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = AppSpacing.page, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            if (showSetupBanner) {
                item {
                    SetupBanner(
                        state = setupState,
                        onClick = onOpenSetup,
                        titleOverride = "运行环境未就绪",
                    )
                }
            }
            if (cardItems.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = vm::setSearchQuery,
                        placeholder = { Text("搜索对话") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.setSearchQuery("") }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "清除搜索",
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (!cardsHydrated) {
                // Room has not emitted yet — keep the list area blank instead of flashing
                // the first-run checklist on every cold start.
            } else if (cardItems.isEmpty()) {
                item {
                    EmptySessionsChecklist(
                        mode = conversationMode,
                        runtimeReady = runtimeReady,
                        modelReady = when (conversationMode) {
                            ConversationMode.LIGHT -> profiles.any { profile ->
                                profile.adapterId.isChatCompletionsCompatible() &&
                                    profile.credentialRef != null &&
                                    (profile.connectionStatus == ProviderConnectionStatus.READY ||
                                        profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
                            }
                            ConversationMode.DEV -> setupState.isReady || profiles.any { profile ->
                                profile.credentialRef != null &&
                                    (profile.connectionStatus == ProviderConnectionStatus.READY ||
                                        profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
                            }
                        },
                        onOpenSetup = onOpenSetup,
                        onOpenModels = onOpenModels,
                        onCreate = { tryCreateSession() },
                        canCreate = availableAdapters.isNotEmpty(),
                    )
                }
            } else if (primaryVisibleItems.isEmpty()) {
                item {
                    Text(
                        if (searchQuery.isNotBlank()) {
                            "没有匹配的对话"
                        } else {
                            "当前模式还没有会话，点右上角新建"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            itemsIndexed(activeItems, key = { _, item -> item.card.id }) { _, item ->
                AgentCardItem(
                    item = item,
                    setupState = setupState,
                    onEnter = {
                        SessionAgentPrefetch.onCardOpened(item.card.id)
                        enterSession(item)
                    },
                    onPrefetch = { SessionAgentPrefetch.onCardPress(item.card) },
                    onPrefetchCancel = { SessionAgentPrefetch.onCardCancel(item.card.id) },
                    onEdit = { editor = vm.editDraft(item) },
                    onRename = { renaming = item },
                    onTogglePinned = {
                        scope.launch {
                            vm.setPinned(item.card.id, !item.card.pinned)
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "操作失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        }
                    },
                    onToggleArchived = {
                        scope.launch {
                            vm.setArchived(item.card.id, true).onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message ?: "归档失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    onDelete = { deleting = item.card },
                )
            }
            if (archivedItems.isNotEmpty()) {
                item {
                    Text(
                        "已归档",
                        modifier = Modifier.padding(top = AppSpacing.sm),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(archivedItems, key = { _, item -> item.card.id }) { _, item ->
                    AgentCardItem(
                        item = item,
                        setupState = setupState,
                        onEnter = {
                            SessionAgentPrefetch.onCardOpened(item.card.id)
                            enterSession(item)
                        },
                        onPrefetch = { SessionAgentPrefetch.onCardPress(item.card) },
                        onPrefetchCancel = { SessionAgentPrefetch.onCardCancel(item.card.id) },
                        onEdit = { editor = vm.editDraft(item) },
                        onRename = { renaming = item },
                        onTogglePinned = {
                            scope.launch {
                                vm.setPinned(item.card.id, !item.card.pinned)
                                    .onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            error.message ?: "操作失败",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                            }
                        },
                        onToggleArchived = {
                            scope.launch {
                                vm.setArchived(item.card.id, false).onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "取消归档失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onDelete = { deleting = item.card },
                    )
                }
            }
        }
    }

    editor?.let { draft ->
        CardEditorDialog(
            initial = draft,
            adapters = availableAdapters,
            profiles = profiles,
            models = models,
            extensions = extensionPickerOptions(
                selectableExtensions,
                allExtensions,
                draft.selectedExtensionIds,
            ),
            selectableExtensionIds = selectableExtensions.mapTo(hashSetOf(), ManagedExtension::id),
            defaultPermissionLevel = defaultPermissionLevel,
            showAdvanced = experienceLevel.advancedEnabled,
            onDismiss = { editor = null },
            onSave = { next ->
                scope.launch {
                    vm.save(next).fold(
                        onSuccess = {
                            editor = null
                            Toast.makeText(context, "对话已保存", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "保存失败",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            },
        )
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${card.customTitle ?: card.name}") },
            text = { Text("只会删除此对话入口，不会删除项目文件或模型配置。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            vm.delete(card.id).fold(
                                onSuccess = {
                                    deleting = null
                                    Toast.makeText(context, "卡片已删除", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        context,
                                        error.message ?: "删除失败",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    renaming?.let { item ->
        RenameDialog(
            currentTitle = item.displayTitle,
            onDismiss = { renaming = null },
            onConfirm = { next ->
                scope.launch {
                    vm.rename(item.card.id, next).fold(
                        onSuccess = { renaming = null },
                        onFailure = { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "重命名失败",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            },
        )
    }

    if (showLocalDataNotice) {
        AlertDialog(
            onDismissRequest = { vm.dismissLocalDataNotice() },
            title = { Text("聊天记录只在这台手机") },
            text = {
                Text("对话和人设保存在本机。卸载或清除数据会丢掉它们，可先到设置里导出「会话与角色」。")
            },
            confirmButton = {
                Button(onClick = { vm.dismissLocalDataNotice() }) { Text("知道了") }
            },
        )
    }

    if (runtimeMissingDialog) {
        AlertDialog(
            onDismissRequest = { runtimeMissingDialog = false },
            title = { Text("需要运行环境") },
            text = {
                Text("还没装好运行环境，现在去安装？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        runtimeMissingDialog = false
                        onOpenSetup()
                    },
                ) {
                    Text("去安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { runtimeMissingDialog = false }) {
                    Text("稍后")
                }
            },
        )
    }
}

@Composable
private fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(currentTitle) { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名对话") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

internal fun shouldShowSetupBanner(
    conversationMode: ConversationMode,
    setupState: SetupState,
): Boolean {
    // 轻聊不依赖嵌入式 Runtime，勿用 Codex 安装状态打扰。
    if (!ConversationModePolicy.requiresEmbeddedRuntime(conversationMode)) return false
    // Runtime already launchable → never show the "runtime not ready" strip.
    if (setupState.canStartChat) return false
    // Still checking / not settled → don't flash a premature failure.
    if (!setupState.checkSettled || setupState.isScanning) return false
    // Confirmed not ready after a real check.
    return true
}

internal fun canStartConversation(
    setupState: SetupState,
    profile: ProviderProfile?,
): Boolean {
    val hasManagedConnection = profile?.let {
        it.credentialRef != null &&
            (it.connectionStatus == ProviderConnectionStatus.READY ||
                it.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
    } == true
    return setupState.canStartChat && (setupState.isReady || hasManagedConnection)
}

internal fun conversationSummary(
    cardName: String,
    cliName: String,
    runtimeName: String,
): String = listOfNotNull(
    cliName.takeUnless { cardName.equals(it, ignoreCase = true) },
    runtimeName,
).joinToString(" · ")

@Composable
private fun EmptySessionsChecklist(
    mode: ConversationMode,
    runtimeReady: Boolean,
    modelReady: Boolean,
    onOpenSetup: () -> Unit,
    onOpenModels: () -> Unit,
    onCreate: () -> Unit,
    canCreate: Boolean,
) {
    val needsRuntime = ConversationModePolicy.requiresEmbeddedRuntime(mode)
    val gateReady = (!needsRuntime || runtimeReady) && modelReady
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xxl, horizontal = AppSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.md))
        Text(
            if (gateReady) "还没有会话" else "开始使用",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Text(
            if (gateReady) {
                "新建一个会话就可以开始聊天，也可以先给助手写个人设"
            } else {
                "准备好环境并连上模型后，就可以聊天"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AppSpacing.lg))
        if (gateReady) {
            Button(onClick = onCreate, enabled = canCreate) {
                Text("新建会话")
            }
            return@Column
        }
        if (needsRuntime) {
            ChecklistStep(
                index = 1,
                title = "准备聊天环境",
                done = runtimeReady,
                actionLabel = if (runtimeReady) "已完成" else "去准备",
                onAction = onOpenSetup,
                enabled = !runtimeReady,
            )
            Spacer(Modifier.height(AppSpacing.sm))
        }
        ChecklistStep(
            index = if (needsRuntime) 2 else 1,
            title = if (mode == ConversationMode.LIGHT) {
                "连接 Chat Completions 模型服务"
            } else {
                "连接模型服务"
            },
            done = modelReady,
            actionLabel = if (modelReady) "已完成" else "去设置",
            onAction = onOpenModels,
            enabled = !modelReady,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        ChecklistStep(
            index = if (needsRuntime) 3 else 2,
            title = "新建会话",
            done = false,
            actionLabel = "新建",
            onAction = onCreate,
            enabled = canCreate && modelReady && (!needsRuntime || runtimeReady),
        )
    }
}

@Composable
private fun ChecklistStep(
    index: Int,
    title: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (done) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (done) "✓" else "$index",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(28.dp),
            )
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (done) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                TextButton(onClick = onAction, enabled = enabled) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SetupBanner(
    state: SetupState,
    onClick: () -> Unit,
    titleOverride: String? = null,
) {
    val presentation = customerSetupPresentation(state)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    titleOverride ?: presentation.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (titleOverride != null) {
                        "点此安装或修复，装好后即可新建会话"
                    } else {
                        presentation.summary
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgentCardItem(
    item: SessionCardUi,
    setupState: SetupState,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit,
    onPrefetch: () -> Unit = {},
    onPrefetchCancel: () -> Unit = {},
) {
    val card = item.card
    val canEnter = item.recipeAvailable && card.enabled
    var menuExpanded by remember { mutableStateOf(false) }
    val statusDescription = when {
        !item.recipeAvailable -> "尚未开放"
        !card.enabled -> "已停用"
        else -> "运行中"
    }
    val statusColor = when {
        !item.recipeAvailable -> MaterialTheme.colorScheme.error
        !card.enabled -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(canEnter, item.card.id) {
                if (!canEnter) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPrefetch()
                    val up = waitForUpOrCancellation()
                    if (up == null) {
                        onPrefetchCancel()
                    }
                }
            }
            .clickable(enabled = canEnter, onClick = onEnter),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态圆点仅靠颜色传达语义，补 contentDescription 供无障碍读取
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = statusDescription
                    }
                    .background(color = statusColor, shape = CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        item.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        card.identity?.roleName?.let { "角色 · $it" },
                        item.lastActiveLabel,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(AppSpacing.xs))
            if (!item.recipeAvailable || !card.enabled) {
                Text(
                    if (!item.recipeAvailable) "尚未开放" else "已停用",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!item.recipeAvailable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "对话操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp),
                    ),
                    offset = DpOffset(0.dp, 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text(if (card.pinned) "取消置顶" else "置顶") },
                        leadingIcon = {
                            Icon(Icons.Filled.PushPin, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePinned()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text(if (card.archived) "取消归档" else "归档") },
                        leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onToggleArchived()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardEditorDialog(
    initial: CardDraft,
    adapters: List<CliAdapterDescriptor>,
    profiles: List<ProviderProfile>,
    models: List<ProviderModel>,
    extensions: List<ManagedExtension>,
    selectableExtensionIds: Set<String>,
    defaultPermissionLevel: CodexPermissionLevel,
    showAdvanced: Boolean,
    onDismiss: () -> Unit,
    onSave: (CardDraft) -> Unit,
) {
    var draft by remember(initial.id, initial.recipeId) { mutableStateOf(initial) }
    var cliExpanded by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var permissionExpanded by remember { mutableStateOf(false) }
    var extensionPickerVisible by remember { mutableStateOf(false) }
    val selectedAdapter = adapters.firstOrNull { it.recipeId == draft.recipeId }
    val externalAgent = CliAdapterRegistry.usesExternalAgentUi(draft.recipeId)
    val piAgent = CliAdapterRegistry.usesPiNativeChat(draft.recipeId)
    val lightAgent = CliAdapterRegistry.usesLightChat(draft.recipeId)
    val lightMode = lightAgent
    val modeAdapters = remember(adapters, lightMode) {
        adapters.filter {
            if (lightMode) CliAdapterRegistry.usesLightChat(it.recipeId)
            else CliAdapterRegistry.isDevMode(it.recipeId)
        }
    }
    val compatibleProfiles = when {
        externalAgent -> emptyList()
        piAgent || lightAgent -> profiles.filter {
            it.type == selectedAdapter?.providerType &&
                it.adapterId.isChatCompletionsCompatible() &&
                (it.connectionStatus == ProviderConnectionStatus.READY ||
                    it.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
        }
        else -> profiles.filter {
            it.type == selectedAdapter?.providerType &&
                it.adapterId.isCodexResponsesCompatible() &&
                (it.connectionStatus == ProviderConnectionStatus.READY ||
                    it.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
        }
    }
    val selectedProfile = compatibleProfiles.firstOrNull { it.id == draft.profileId }
    val availableModels = selectedProfile?.let { profile ->
        models.filter { it.providerId == profile.id }.ifEmpty {
            if (profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED) {
                listOf(
                    ProviderModel(
                        providerId = profile.id,
                        id = profile.defaultModel,
                        discoveredAtEpochMs = profile.lastCheckedAtEpochMs ?: 0,
                    ),
                )
            } else {
                emptyList()
            }
        }
    }.orEmpty()
    // 有模型列表时只展示可选 id，不允许手改；DISCOVERY_UNSUPPORTED 只有默认一项也走选择。
    val selectableModels = remember(availableModels) {
        filterSelectableModels(
            models = availableModels,
            query = "",
            selectedId = null,
            maxVisible = DEFAULT_MAX_VISIBLE_MODELS,
        )
    }
    val selectedModelLabel = availableModels.firstOrNull { it.id == draft.modelId }?.let { model ->
        if (model.displayName != model.id) "${model.displayName}（${model.id}）" else model.id
    } ?: draft.modelId.orEmpty()
    val effectivePermission = CodexPermissionLevel.effective(
        draft.permissionLevel,
        defaultPermissionLevel,
    )
    val permissionPresentation = codexPermissionPresentation(effectivePermission)

    if (extensionPickerVisible) {
        ExtensionPickerDialog(
            extensions = extensions,
            selectedIds = draft.selectedExtensionIds,
            selectableIds = selectableExtensionIds,
            onDismiss = { extensionPickerVisible = false },
            onConfirm = { selectedIds ->
                draft = draft.copy(selectedExtensionIds = selectedIds)
                extensionPickerVisible = false
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "新建会话" else "编辑会话") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(if (showAdvanced) "名称" else "会话名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (modeAdapters.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = cliExpanded,
                        onExpandedChange = { cliExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedAdapter?.displayName.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(if (lightMode) "轻聊引擎" else "开发引擎") },
                            supportingText = {
                                Text(
                                    when (draft.recipeId) {
                                        "recipe_light" ->
                                            "轻聊 · 无本地 runtime · 可写角色"
                                        "recipe_deepseek_harness" ->
                                            "本机网页；chat 网关（如 dots）在 dsh 内配置"
                                        "recipe_pi" ->
                                            "开发 · pi 原生聊天 · Chat Completions"
                                        else -> "开发 · Codex 原生 · Responses"
                                    },
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cliExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = cliExpanded,
                            onDismissRequest = { cliExpanded = false },
                        ) {
                            modeAdapters.forEach { adapter ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(adapter.displayName)
                                            Text(
                                                when (adapter.recipeId) {
                                                    "recipe_light" -> "轻聊 · 无 runtime"
                                                    "recipe_deepseek_harness" -> "网页 · chat 网关"
                                                    "recipe_pi" -> "开发 · Chat Completions"
                                                    else -> "开发 · Responses"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        draft = draft.copy(
                                            recipeId = adapter.recipeId,
                                            name = if (draft.id == null) {
                                                adapter.displayName
                                            } else {
                                                draft.name
                                            },
                                            profileId = null,
                                            modelId = null,
                                            permissionLevel = null,
                                            selectedExtensionIds = emptySet(),
                                            workspacePath = adapter.defaultWorkspacePath,
                                        )
                                        cliExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (externalAgent) {
                    Text(
                        "打开会话会进入 dsh 网页。未安装时会跳转到「运行环境」。密钥与模型在 dsh 内配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (piAgent || lightAgent) {
                        Text(
                            if (lightAgent) {
                                "轻聊：选 Chat Completions（如 dots）。不装 runtime，可写角色。"
                            } else {
                                "选择「模型服务」里添加的 Chat Completions（如小红书 dots）。不走 Codex Responses。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { providerExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedProfile?.name ?: if (piAgent || lightAgent) {
                                "请选择 Chat Completions 服务"
                            } else {
                                "当前 Codex 配置"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("模型服务") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            if (!piAgent && !lightAgent) {
                                DropdownMenuItem(
                                    text = { Text("当前 Codex 配置") },
                                    onClick = {
                                        draft = draft.copy(profileId = null, modelId = null)
                                        providerExpanded = false
                                    },
                                )
                            }
                            if (compatibleProfiles.isEmpty() && (piAgent || lightAgent)) {
                                DropdownMenuItem(
                                    text = { Text("暂无 Chat Completions 服务 · 请先到模型服务添加") },
                                    onClick = { providerExpanded = false },
                                )
                            }
                            compatibleProfiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        draft = draft.copy(
                                            profileId = profile.id,
                                            modelId = profile.defaultModel,
                                        )
                                        providerExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (selectedProfile != null) {
                        ExposedDropdownMenuBox(
                            expanded = modelExpanded,
                            onExpandedChange = { modelExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selectedModelLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("模型") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = modelExpanded,
                                onDismissRequest = { modelExpanded = false },
                            ) {
                                selectableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.displayName)
                                                if (model.displayName != model.id) {
                                                    Text(
                                                        model.id,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            draft = draft.copy(modelId = model.id)
                                            modelExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (draft.id == null) "给这个助手写个人设" else "角色身份",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (draft.id == null) "可选，跳过也能直接开始聊" else "本会话的固定人设",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.identity != null,
                            onCheckedChange = { enabled ->
                                draft = draft.copy(
                                    identity = if (enabled) {
                                        draft.identity ?: ConversationIdentity("", "")
                                    } else {
                                        null
                                    },
                                )
                            },
                        )
                    }
                    draft.identity?.let { identity ->
                        OutlinedTextField(
                            value = identity.roleName,
                            onValueChange = {
                                if (it.length <= ConversationIdentityPolicy.MAX_ROLE_NAME_LENGTH) {
                                    draft = draft.copy(identity = identity.copy(roleName = it))
                                }
                            },
                            label = { Text("角色名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = identity.selfDefinition,
                            onValueChange = {
                                if (it.length <= ConversationIdentityPolicy.MAX_FIELD_LENGTH) {
                                    draft = draft.copy(identity = identity.copy(selfDefinition = it))
                                }
                            },
                            label = { Text("这个助手是谁") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = identity.objective,
                            onValueChange = {
                                if (it.length <= ConversationIdentityPolicy.MAX_FIELD_LENGTH) {
                                    draft = draft.copy(identity = identity.copy(objective = it))
                                }
                            },
                            label = { Text("主要目标（可选）") },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = identity.communicationStyle,
                            onValueChange = {
                                if (it.length <= ConversationIdentityPolicy.MAX_FIELD_LENGTH) {
                                    draft = draft.copy(identity = identity.copy(communicationStyle = it))
                                }
                            },
                            label = { Text("表达方式（可选）") },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = identity.boundaries,
                            onValueChange = {
                                if (it.length <= ConversationIdentityPolicy.MAX_FIELD_LENGTH) {
                                    draft = draft.copy(identity = identity.copy(boundaries = it))
                                }
                            },
                            label = { Text("必须遵守的设定（可选）") },
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!lightAgent && !piAgent && !externalAgent) {
                        ListItem(
                            headlineContent = { Text("扩展") },
                            supportingContent = {
                                Text(extensionSelectionLabel(draft.selectedExtensionIds, extensions))
                            },
                            leadingContent = {
                                Icon(Icons.Filled.Extension, contentDescription = null)
                            },
                            trailingContent = {
                                if (extensions.isNotEmpty()) {
                                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                                }
                            },
                            modifier = Modifier.clickable(enabled = extensions.isNotEmpty()) {
                                extensionPickerVisible = true
                            },
                        )
                    }
                    if (showAdvanced && !lightAgent && !piAgent && !externalAgent) {
                        ExposedDropdownMenuBox(
                            expanded = permissionExpanded,
                            onExpandedChange = { permissionExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = permissionSelectionLabel(
                                    draft.permissionLevel,
                                    defaultPermissionLevel,
                                ),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Codex 权限") },
                                supportingText = {
                                    Text(permissionPresentation.description)
                                },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(permissionExpanded)
                                },
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                            )
                            ExposedDropdownMenu(
                                expanded = permissionExpanded,
                                onDismissRequest = { permissionExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "使用默认 · " +
                                                codexPermissionPresentation(defaultPermissionLevel).title,
                                        )
                                    },
                                    onClick = {
                                        draft = draft.copy(permissionLevel = null)
                                        permissionExpanded = false
                                    },
                                )
                                CodexPermissionLevel.entries.forEach { level ->
                                    val presentation = codexPermissionPresentation(level)
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(presentation.title)
                                                Text(
                                                    presentation.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            draft = draft.copy(permissionLevel = level)
                                            permissionExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = draft.workspacePath,
                            onValueChange = { draft = draft.copy(workspacePath = it) },
                            label = { Text("项目文件夹") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (showAdvanced || !draft.enabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("启用此对话", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = draft.enabled,
                            onCheckedChange = { draft = draft.copy(enabled = it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = selectedAdapter != null &&
                    draft.name.isNotBlank() &&
                    draft.workspacePath.startsWith('/') &&
                    (externalAgent || (
                        (draft.identity == null ||
                            draft.identity?.roleName?.isNotBlank() == true &&
                            draft.identity?.selfDefinition?.isNotBlank() == true) &&
                            (
                                if (piAgent || lightAgent) {
                                    draft.profileId != null &&
                                        availableModels.any { it.id == draft.modelId }
                                } else {
                                    draft.profileId == null ||
                                        availableModels.any { it.id == draft.modelId }
                                }
                            )
                        )),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

}

@Composable
private fun ExtensionPickerDialog(
    extensions: List<ManagedExtension>,
    selectedIds: Set<String>,
    selectableIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val availableIds = remember(extensions) { extensions.mapTo(hashSetOf()) { it.id } }
    var pending by remember(selectedIds, availableIds) {
        mutableStateOf(selectedIds.intersect(availableIds))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择扩展") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                ExtensionKind.entries.forEach { kind ->
                    val group = extensions.filter { it.kind == kind }
                    if (group.isNotEmpty()) {
                        item(key = "extension-kind-${kind.name}") {
                            Text(
                                extensionKindLabel(kind),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(group, key = ManagedExtension::id) { extension ->
                            val canToggle = extension.id in selectableIds || extension.id in pending
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canToggle) {
                                        pending = if (extension.id in pending) {
                                            pending - extension.id
                                        } else {
                                            pending + extension.id
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = extension.id in pending,
                                    enabled = canToggle,
                                    onCheckedChange = { checked ->
                                        pending = if (checked) {
                                            pending + extension.id
                                        } else {
                                            pending - extension.id
                                        }
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(extension.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val availability = extensionUnavailableLabel(extension, selectableIds)
                                    val supporting = listOfNotNull(
                                        extension.description.takeIf(String::isNotBlank),
                                        availability,
                                    ).joinToString(" · ")
                                    if (supporting.isNotBlank()) {
                                        Text(
                                            supporting,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(pending) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

internal fun extensionPickerOptions(
    selectable: List<ManagedExtension>,
    all: List<ManagedExtension>,
    selectedIds: Set<String>,
): List<ManagedExtension> {
    val selectableIds = selectable.mapTo(hashSetOf(), ManagedExtension::id)
    return (selectable + all.filter { it.id in selectedIds && it.id !in selectableIds })
        .distinctBy(ManagedExtension::id)
        .sortedWith(compareBy<ManagedExtension>({ it.kind.ordinal }, { it.name.lowercase() }))
}

private fun extensionUnavailableLabel(
    extension: ManagedExtension,
    selectableIds: Set<String>,
): String? = when {
    extension.id in selectableIds -> null
    !extension.enabled -> "已停用，取消勾选后不可重新选择"
    extension.status != ExtensionStatus.READY -> "当前不可用，取消勾选后不可重新选择"
    else -> "当前版本不可用，取消勾选后不可重新选择"
}

internal fun extensionSelectionLabel(
    selectedIds: Set<String>,
    extensions: List<ManagedExtension>,
): String {
    if (extensions.isEmpty()) return "尚未添加扩展"
    val selected = extensions.filter { it.id in selectedIds }
    return when (selected.size) {
        0 -> "未启用"
        1 -> selected.single().name
        else -> "已选 ${selected.size} 个"
    }
}

internal fun extensionKindLabel(kind: ExtensionKind): String = when (kind) {
    ExtensionKind.SKILL -> "Skills"
    ExtensionKind.REMOTE_MCP -> "远程 MCP"
    ExtensionKind.LOCAL_MCP -> "本地 MCP"
}
