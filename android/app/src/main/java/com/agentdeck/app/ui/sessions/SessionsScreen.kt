package com.agentdeck.app.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.setup.SetupAction
import com.agentdeck.app.domain.setup.SetupState
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
    val setupState by vm.setupState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CardDraft?>(null) }
    var deleting by remember { mutableStateOf<AgentCard?>(null) }

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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!setupState.isReady) {
                item {
                    SetupBanner(
                        state = setupState,
                        onClick = onOpenSetup,
                    )
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
                val profileName = profiles.firstOrNull { it.id == card.profileId }?.name ?: "未绑定配置"
                val recipeAvailable = vm.isRecipeAvailable(card.recipeId)
                AgentCardItem(
                    card = card,
                    cliName = vm.adapterDisplayName(card.recipeId),
                    profileName = profileName,
                    recipeAvailable = recipeAvailable,
                    environmentReady = setupState.canStartChat,
                    onEnter = {
                        if (!setupState.canStartChat) {
                            onOpenSetup()
                        } else {
                            onOpenChat(card.id)
                        }
                    },
                    onEdit = { editor = vm.editDraft(card) },
                    onDelete = { deleting = card },
                )
            }
        }
    }

    editor?.let { draft ->
        CardEditorDialog(
            initial = draft,
            adapters = vm.availableAdapters,
            profiles = profiles,
            compatibleProfiles = vm::compatibleProfiles,
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
            text = { Text("该操作只删除对话入口，不会删除 Termux、工作区或 CLI 数据。") },
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

@Composable
private fun SetupBanner(
    state: SetupState,
    onClick: () -> Unit,
) {
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
                    if (state.isScanning) "正在检测 Codex 环境" else "完成 Codex 设置",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    state.message ?: setupBannerDetail(state.action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "打开 Codex 设置",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun setupBannerDetail(action: SetupAction): String = when (action) {
    SetupAction.SCAN -> "确认 Termux、Ubuntu 和 Codex 状态"
    SetupAction.INSTALL_TERMUX -> "需要安装 Termux"
    SetupAction.GRANT_PERMISSION -> "需要授予 Termux 调用权限"
    SetupAction.ENABLE_EXTERNAL_APPS -> "需要启用 Termux 外部调用"
    SetupAction.INSTALL_CODEX -> "需要安装、更新或修复 Codex 环境"
    SetupAction.CONFIGURE_CODEX_AUTH -> "需要登录账号或配置 API Key"
    SetupAction.READY -> "运行环境已就绪"
}

@Composable
private fun AgentCardItem(
    card: AgentCard,
    cliName: String,
    profileName: String,
    recipeAvailable: Boolean,
    environmentReady: Boolean,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val canEnter = recipeAvailable && card.enabled
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onEnter,
        enabled = canEnter,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "$cliName · $profileName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    card.workspacePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = if (canEnter) {
                            if (environmentReady) "进入 ${card.name}" else "完成 Codex 设置"
                        } else {
                            null
                        },
                        tint = if (canEnter) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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
    compatibleProfiles: (String, List<ProviderProfile>) -> List<ProviderProfile>,
    onDismiss: () -> Unit,
    onSave: (CardDraft) -> Unit,
) {
    var draft by remember(initial.id, initial.recipeId) { mutableStateOf(initial) }
    var cliExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    val selectedAdapter = adapters.firstOrNull { it.recipeId == draft.recipeId }
    val profileOptions = compatibleProfiles(draft.recipeId, profiles)
    val selectedProfile = profileOptions.firstOrNull { it.id == draft.profileId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "新建对话" else "编辑对话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(
                    expanded = cliExpanded,
                    onExpandedChange = { cliExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedAdapter?.displayName.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("CLI") },
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
                                        workspacePath = adapter.defaultWorkspacePath,
                                    )
                                    cliExpanded = false
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
                ExposedDropdownMenuBox(
                    expanded = profileExpanded,
                    onExpandedChange = { profileExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProfile?.name ?: "不绑定",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("CLI 配置") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(profileExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = profileExpanded,
                        onDismissRequest = { profileExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("不绑定") },
                            onClick = {
                                draft = draft.copy(profileId = null)
                                profileExpanded = false
                            },
                        )
                        profileOptions.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name) },
                                onClick = {
                                    draft = draft.copy(profileId = profile.id)
                                    profileExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.workspacePath,
                    onValueChange = { draft = draft.copy(workspacePath = it) },
                    label = { Text("工作区") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("启用", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = selectedAdapter != null &&
                    draft.name.isNotBlank() &&
                    draft.workspacePath.startsWith('/'),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
