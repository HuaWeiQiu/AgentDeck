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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型") },
                actions = {
                    OutlinedButton(
                        onClick = {
                            editor = EditorState(
                                id = null,
                                name = "",
                                type = ProviderType.OPENAI_COMPATIBLE,
                                baseUrl = "https://api.openai.com/v1",
                                model = "",
                                apiKey = "",
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("新建") }
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
            item {
                Text(
                    "支持 OpenAI 兼容（任意 Base URL）与 Anthropic。API Key 加密存储。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
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
                            apiKey = "",
                        )
                    },
                    onDelete = {
                        scope.launch {
                            vm.delete(profile.id)
                            Toast.makeText(context, "已删除 ${profile.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
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
                    vm.save(next)
                    editor = null
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                }
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
            Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
            Text("模型：${profile.defaultModel}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = onDelete) { Text("删除") }
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
    val apiKey: String,
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
        title = { Text(if (state.id == null) "新建 Profile" else "编辑 Profile") },
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
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = { draft = draft.copy(apiKey = it) },
                    label = { Text(if (state.id == null) "API Key" else "API Key（留空则不改）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft) },
                enabled = draft.name.isNotBlank() && draft.baseUrl.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
