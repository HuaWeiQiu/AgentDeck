package com.agentdeck.app.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.cards.CardDraft
import com.agentdeck.app.domain.chat.ConversationIdentityPolicy
import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ConversationIdentity
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.ui.common.DEFAULT_MAX_VISIBLE_MODELS
import com.agentdeck.app.ui.common.filterSelectableModels
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.ui.setup.customerSetupPresentation
import com.agentdeck.app.ui.permissions.codexPermissionPresentation
import com.agentdeck.app.ui.permissions.permissionSelectionLabel
import com.agentdeck.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenSetup: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    vm: SessionsViewModel = viewModel(),
) {
    val cardItems by vm.cardItems.collectAsStateWithLifecycle()
    val cardsHydrated by vm.cardsHydrated.collectAsStateWithLifecycle()
    val visibleItems by vm.visibleItems.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val allExtensions by vm.extensions.collectAsStateWithLifecycle()
    val selectableExtensions by vm.selectableExtensions.collectAsStateWithLifecycle()
    val setupState by vm.setupState.collectAsStateWithLifecycle()
    val experienceLevel by vm.experienceLevel.collectAsStateWithLifecycle()
    val defaultPermissionLevel by vm.defaultPermissionLevel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CardDraft?>(null) }
    var deleting by remember { mutableStateOf<AgentCard?>(null) }
    var renaming by remember { mutableStateOf<SessionCardUi?>(null) }
    var runtimeMissingDialog by remember { mutableStateOf(false) }
    val showLocalDataNotice by vm.showLocalDataNotice.collectAsStateWithLifecycle()
    val showSetupBanner = shouldShowSetupBanner(setupState, cardItems.map { it.card }, profiles)
    val activeItems = remember(visibleItems) { visibleItems.filter { !it.card.archived } }
    val archivedItems = remember(visibleItems) { visibleItems.filter { it.card.archived } }
    val runtimeReady = setupState.canStartChat

    fun tryCreateSession() {
        if (!runtimeReady) {
            runtimeMissingDialog = true
        } else {
            editor = vm.newDraft()
        }
    }

    fun tryOpenChat(cardId: String) {
        if (!runtimeReady) {
            runtimeMissingDialog = true
        } else {
            onOpenChat(cardId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话") },
                actions = {
                    IconButton(
                        onClick = { tryCreateSession() },
                        enabled = vm.availableAdapters.isNotEmpty(),
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
            contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        ) {
            if (showSetupBanner) {
                item {
                    Box(Modifier.padding(bottom = 10.dp)) {
                        SetupBanner(
                            state = setupState,
                            onClick = onOpenSetup,
                            titleOverride = "运行环境未就绪",
                        )
                    }
                }
            }
            if (cardItems.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = vm::setSearchQuery,
                        placeholder = { Text("搜索对话") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = AppSpacing.sm),
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
                        runtimeReady = runtimeReady,
                        modelReady = setupState.isReady || profiles.any { profile ->
                            profile.credentialRef != null &&
                                (profile.connectionStatus == ProviderConnectionStatus.READY ||
                                    profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
                        },
                        onOpenSetup = onOpenSetup,
                        onOpenModels = onOpenModels,
                        onCreate = { tryCreateSession() },
                        canCreate = vm.availableAdapters.isNotEmpty(),
                    )
                }
            } else if (visibleItems.isEmpty()) {
                item {
                    Text(
                        "没有匹配的对话",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            itemsIndexed(activeItems, key = { _, item -> item.card.id }) { index, item ->
                AgentCardItem(
                    item = item,
                    setupState = setupState,
                    onEnter = {
                        if (!canStartConversation(setupState, item.profile)) {
                            onOpenSetup()
                        } else {
                            tryOpenChat(item.card.id)
                        }
                    },
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
                if (index < activeItems.lastIndex) {
                    HorizontalDivider()
                }
            }
            if (archivedItems.isNotEmpty()) {
                item {
                    Text(
                        "已归档",
                        modifier = Modifier.padding(top = AppSpacing.lg, bottom = AppSpacing.xs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(archivedItems, key = { _, item -> item.card.id }) { index, item ->
                    AgentCardItem(
                        item = item,
                        setupState = setupState,
                        onEnter = {
                            if (!canStartConversation(setupState, item.profile)) {
                                onOpenSetup()
                            } else {
                                tryOpenChat(item.card.id)
                            }
                        },
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
                    if (index < archivedItems.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editor?.let { draft ->
        CardEditorDialog(
            initial = draft,
            adapters = vm.availableAdapters,
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
    setupState: SetupState,
    cards: List<AgentCard>,
    profiles: List<ProviderProfile>,
): Boolean {
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
    runtimeReady: Boolean,
    modelReady: Boolean,
    onOpenSetup: () -> Unit,
    onOpenModels: () -> Unit,
    onCreate: () -> Unit,
    canCreate: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (runtimeReady && modelReady) "还没有会话" else "开始使用",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Text(
            if (runtimeReady && modelReady) {
                "新建一个会话就可以开始聊天，也可以先给助手写个人设"
            } else {
                "准备好环境并连上模型后，就可以聊天"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AppSpacing.lg))
        if (runtimeReady && modelReady) {
            Button(onClick = onCreate, enabled = canCreate) {
                Text("新建会话")
            }
            return@Column
        }
        ChecklistStep(
            index = 1,
            title = "准备聊天环境",
            done = runtimeReady,
            actionLabel = if (runtimeReady) "已完成" else "去准备",
            onAction = onOpenSetup,
            enabled = !runtimeReady,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        ChecklistStep(
            index = 2,
            title = "连接模型服务",
            done = modelReady,
            actionLabel = if (modelReady) "已完成" else "去设置",
            onAction = onOpenModels,
            enabled = !modelReady,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        ChecklistStep(
            index = 3,
            title = "新建会话",
            done = false,
            actionLabel = "新建",
            onAction = onCreate,
            enabled = canCreate,
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
) {
    val card = item.card
    val canEnter = item.recipeAvailable && card.enabled
    var menuExpanded by remember { mutableStateOf(false) }
    val statusDescription = when {
        !item.recipeAvailable -> "尚未开放"
        !card.enabled -> "已停用"
        else -> "运行中"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canEnter, onClick = onEnter)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态圆点仅靠颜色传达语义，补 contentDescription 供无障碍读取
        Box(
            modifier = Modifier
                .size(9.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = statusDescription
                }
                .background(
                    color = when {
                        !item.recipeAvailable -> MaterialTheme.colorScheme.error
                        !card.enabled -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(14.dp))
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    card.identity?.roleName?.let { "角色 · $it" },
                    "最后活动 · ${item.lastActiveLabel}",
                ).joinToString("   "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(AppSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "对话操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
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
                    )
                    DropdownMenuItem(
                        text = { Text(if (card.archived) "取消归档" else "归档") },
                        leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onToggleArchived()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
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
    val compatibleProfiles = profiles.filter {
        it.type == selectedAdapter?.providerType &&
            (it.connectionStatus == ProviderConnectionStatus.READY ||
                it.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED)
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
                if (showAdvanced && adapters.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = cliExpanded,
                        onExpandedChange = { cliExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedAdapter?.displayName.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Agent") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cliExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = cliExpanded,
                            onDismissRequest = { cliExpanded = false },
                        ) {
                            adapters.forEach { adapter ->
                                DropdownMenuItem(
                                    text = { Text(adapter.displayName) },
                                    onClick = {
                                        draft = draft.copy(
                                            recipeId = adapter.recipeId,
                                            profileId = null,
                                            modelId = null,
                                            workspacePath = adapter.defaultWorkspacePath,
                                        )
                                        cliExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProfile?.name ?: "当前 Codex 配置",
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
                        DropdownMenuItem(
                            text = { Text("当前 Codex 配置") },
                            onClick = {
                                draft = draft.copy(profileId = null, modelId = null)
                                providerExpanded = false
                            },
                        )
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
                if (showAdvanced) {
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
                    (draft.identity == null ||
                        draft.identity?.roleName?.isNotBlank() == true &&
                        draft.identity?.selfDefinition?.isNotBlank() == true) &&
                    (draft.profileId == null ||
                        availableModels.any { it.id == draft.modelId }),
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
