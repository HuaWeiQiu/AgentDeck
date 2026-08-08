package com.agentdeck.app.ui.sessions

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.cards.CardDraft
import com.agentdeck.app.domain.launch.CliAdapterDescriptor
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.LaunchResult
import com.agentdeck.app.domain.model.ProviderProfile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: SessionsViewModel = viewModel(),
) {
    val cards by vm.cards.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CardDraft?>(null) }
    var deleting by remember { mutableStateOf<AgentCard?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
                actions = {
                    IconButton(
                        onClick = { editor = vm.newDraft() },
                        enabled = vm.availableAdapters.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建卡片")
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
            if (cards.isEmpty()) {
                item {
                    Text(
                        "暂无会话卡片",
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
                    profileName = profileName,
                    recipeAvailable = recipeAvailable,
                    onEnter = {
                        scope.launch {
                            when (val result = vm.launch(card.id)) {
                                LaunchResult.Success -> Toast.makeText(
                                    context,
                                    "已请求启动 ${card.name}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                is LaunchResult.Failed -> Toast.makeText(
                                    context,
                                    result.message,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
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
                            Toast.makeText(context, "卡片已保存", Toast.LENGTH_SHORT).show()
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
            text = { Text("该操作只删除启动卡片，不会删除 Termux、工作区或 CLI 数据。") },
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
private fun AgentCardItem(
    card: AgentCard,
    profileName: String,
    recipeAvailable: Boolean,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        !recipeAvailable -> "尚未开放"
                        !card.enabled -> "已停用"
                        else -> "可启动"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        !recipeAvailable -> MaterialTheme.colorScheme.error
                        card.enabled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("配置：$profileName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${card.workspaceNamespace.name.lowercase()} · ${card.workspacePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                card.termuxSessionName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEnter,
                    enabled = recipeAvailable && card.enabled,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("进入")
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除卡片")
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
        title = { Text(if (draft.id == null) "新建卡片" else "编辑卡片") },
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
