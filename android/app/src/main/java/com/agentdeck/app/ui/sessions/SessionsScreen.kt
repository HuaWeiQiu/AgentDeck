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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ConversationIdentity
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderConnectionStatus
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
    onOpenChat: (String) -> Unit = {},
    vm: SessionsViewModel = viewModel(),
) {
    val cardItems by vm.cardItems.collectAsStateWithLifecycle()
    val visibleItems by vm.visibleItems.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val setupState by vm.setupState.collectAsStateWithLifecycle()
    val experienceLevel by vm.experienceLevel.collectAsStateWithLifecycle()
    val defaultPermissionLevel by vm.defaultPermissionLevel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CardDraft?>(null) }
    var deleting by remember { mutableStateOf<AgentCard?>(null) }
    var renaming by remember { mutableStateOf<SessionCardUi?>(null) }
    val showSetupBanner = shouldShowSetupBanner(setupState, cardItems.map { it.card }, profiles)
    val activeItems = remember(visibleItems) { visibleItems.filter { !it.card.archived } }
    val archivedItems = remember(visibleItems) { visibleItems.filter { it.card.archived } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("对话") },
                actions = {
                    IconButton(
                        onClick = { editor = vm.newDraft() },
                        enabled = vm.availableAdapters.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建对话")
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
            if (cardItems.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.md))
                        Text(
                            "暂无对话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            "新建一个对话，开始在手机上使用 Codex",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.lg))
                        Button(
                            onClick = { editor = vm.newDraft() },
                            enabled = vm.availableAdapters.isNotEmpty(),
                        ) {
                            Text("新建对话")
                        }
                    }
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
                            onOpenChat(item.card.id)
                        }
                    },
                    onEdit = { editor = vm.editDraft(item.card) },
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
                                onOpenChat(item.card.id)
                            }
                        },
                        onEdit = { editor = vm.editDraft(item.card) },
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
    if (setupState.isReady) return false
    return cards.none { card ->
        val profile = card.profileId?.let { profileId ->
            profiles.firstOrNull { it.id == profileId }
        }
        card.enabled && canStartConversation(setupState, profile)
    }
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
private fun SetupBanner(
    state: SetupState,
    onClick: () -> Unit,
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
                    presentation.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    presentation.summary,
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
    var modelQuery by remember(initial.id, initial.profileId) {
        mutableStateOf(initial.modelId.orEmpty())
    }
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
    val filteredModels = remember(availableModels, modelQuery) {
        availableModels.filter { model ->
            modelQuery.isBlank() || model.id.contains(modelQuery, ignoreCase = true) ||
                model.displayName.contains(modelQuery, ignoreCase = true)
        }.take(MAX_VISIBLE_SESSION_MODELS)
    }
    val effectivePermission = CodexPermissionLevel.effective(
        draft.permissionLevel,
        defaultPermissionLevel,
    )
    val permissionPresentation = codexPermissionPresentation(effectivePermission)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "新建对话" else "编辑对话") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                                        modelQuery = ""
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
                                modelQuery = ""
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
                                    modelQuery = profile.defaultModel
                                    providerExpanded = false
                                },
                            )
                        }
                    }
                }
                if (selectedProfile != null) {
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { next ->
                            if (!next) modelQuery = draft.modelId.orEmpty()
                            modelExpanded = next
                        },
                    ) {
                        OutlinedTextField(
                            value = modelQuery,
                            onValueChange = { query ->
                                modelQuery = query
                                draft = draft.copy(modelId = null)
                                modelExpanded = true
                            },
                            label = { Text("模型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                        ) {
                            filteredModels.forEach { model ->
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
                                        modelQuery = model.id
                                        modelExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
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
                            Text(
                                if (showAdvanced) {
                                    permissionPresentation.technicalSummary
                                } else {
                                    permissionPresentation.description
                                },
                            )
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
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("角色身份", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "作为此对话中持续生效的身份",
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
                        label = { Text("角色是谁") },
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
                if (showAdvanced) {
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

private const val MAX_VISIBLE_SESSION_MODELS = 100
