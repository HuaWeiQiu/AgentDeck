package com.agentdeck.app.ui.models

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    vm: ModelsViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var deleting by remember { mutableStateOf<ProviderProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CLI 配置") },
                actions = {
                    IconButton(
                        onClick = {
                            editor = EditorState(
                                id = null,
                                name = "",
                                type = ProviderType.OPENAI_COMPATIBLE,
                                baseUrl = "https://api.openai.com/v1",
                                model = "",
                            )
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 CLI 配置")
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
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "暂无 CLI 配置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    onEdit = {
                        editor = EditorState(
                            id = profile.id,
                            name = profile.name,
                            type = profile.type,
                            baseUrl = profile.baseUrl,
                            model = profile.defaultModel,
                        )
                    },
                    onDelete = { deleting = profile },
                )
            }
        }
    }

    editor?.let { state ->
        ProfileEditorDialog(
            state = state,
            onDismiss = { editor = null },
            onSave = { next ->
                scope.launch {
                    vm.save(next).fold(
                        onSuccess = {
                            editor = null
                            Toast.makeText(context, "CLI 配置已保存", Toast.LENGTH_SHORT).show()
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

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${profile.name}") },
            text = { Text("引用该配置的卡片将保留，但会解除绑定。CLI 登录信息不受影响。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            vm.delete(profile.id).fold(
                                onSuccess = { affected ->
                                    deleting = null
                                    val message = if (affected == 0) {
                                        "CLI 配置已删除"
                                    } else {
                                        "CLI 配置已删除，$affected 张卡片已解除绑定"
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
private fun ProfileCard(
    profile: ProviderProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(
                when (profile.type) {
                    ProviderType.OPENAI_COMPATIBLE -> "OpenAI 兼容"
                    ProviderType.ANTHROPIC -> "Anthropic"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                profile.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "模型：${profile.defaultModel}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除 CLI 配置")
                }
            }
        }
    }
}

data class EditorState(
    val id: String?,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
)

@Composable
private fun ProfileEditorDialog(
    state: EditorState,
    onDismiss: () -> Unit,
    onSave: (EditorState) -> Unit,
) {
    var draft by remember(state.id) { mutableStateOf(state) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.id == null) "新建 CLI 配置" else "编辑 CLI 配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.type == ProviderType.OPENAI_COMPATIBLE,
                        onClick = { draft = draft.copy(type = ProviderType.OPENAI_COMPATIBLE) },
                        label = { Text("OpenAI 兼容") },
                    )
                    FilterChip(
                        selected = draft.type == ProviderType.ANTHROPIC,
                        onClick = { draft = draft.copy(type = ProviderType.ANTHROPIC) },
                        label = { Text("Anthropic") },
                    )
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { draft = draft.copy(baseUrl = it) },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { draft = draft.copy(model = it) },
                    label = { Text("默认模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = draft.name.isNotBlank() &&
                    draft.baseUrl.isNotBlank() &&
                    draft.model.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
