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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.cards.CardDraft
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.ui.setup.customerSetupPresentation
import com.agentdeck.app.ui.permissions.codexPermissionPresentation
import com.agentdeck.app.ui.permissions.permissionSelectionLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenSetup: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    vm: SessionsViewModel = viewModel(),
) {
    val cards by vm.cards.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val models by vm.models.collectAsState()
    val setupState by vm.setupState.collectAsState()
    val experienceLevel by vm.experienceLevel.collectAsState()
    val defaultPermissionLevel by vm.defaultPermissionLevel.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CardDraft?>(null) }
    var deleting by remember { mutableStateOf<AgentCard?>(null) }
    val showSetupBanner = shouldShowSetupBanner(setupState, cards, profiles)

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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
            if (cards.isEmpty()) {
                item {
                    Text(
                        "暂无对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(cards, key = { it.id }) { card ->
                val recipeAvailable = vm.isRecipeAvailable(card.recipeId)
                val selectedProfile = card.profileId?.let { profileId ->
                    profiles.firstOrNull { it.id == profileId }
                }
                val canStartConversation = canStartConversation(setupState, selectedProfile)
                AgentCardItem(
                    card = card,
                    summary = conversationSummary(
                        cardName = card.name,
                        cliName = vm.adapterDisplayName(card.recipeId),
                        runtimeName = card.profileId?.let { profileId ->
                            val model = card.modelId?.let { modelId ->
                                models.firstOrNull {
                                    it.providerId == profileId && it.id == modelId
                                }
                            }
                            listOfNotNull(selectedProfile?.name, model?.displayName ?: card.modelId)
                                .joinToString(" · ")
                                .ifBlank { "模型服务不可用" }
                        } ?: "当前 Codex 配置",
                    ),
                    recipeAvailable = recipeAvailable,
                    onEnter = {
                        if (!canStartConversation) {
                            onOpenSetup()
                        } else {
                            onOpenChat(card.id)
                        }
                    },
                    onEdit = { editor = vm.editDraft(card) },
                    onDelete = { deleting = card },
                )
                HorizontalDivider()
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
            title = { Text("删除 ${card.name}") },
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
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
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
    card: AgentCard,
    summary: String,
    recipeAvailable: Boolean,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val canEnter = recipeAvailable && card.enabled
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canEnter, onClick = onEnter)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(
                    color = when {
                        !recipeAvailable -> MaterialTheme.colorScheme.error
                        !card.enabled -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.secondary
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!recipeAvailable || !card.enabled) {
                Text(
                    if (!recipeAvailable) "尚未开放" else "已停用",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!recipeAvailable) {
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
