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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.LaunchResult
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
    var editing by remember { mutableStateOf<AgentCard?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话") },
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
                    "点击「进入」会在 Termux 中启动预设链路（Codex：ubuntu → codex 聊天 TUI）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
            items(cards, key = { it.id }) { card ->
                val profileName = profiles.firstOrNull { it.id == card.profileId }?.name ?: "未绑定模型"
                AgentCardItem(
                    card = card,
                    profileName = profileName,
                    onEnter = {
                        scope.launch {
                            when (val result = vm.launch(card.id)) {
                                LaunchResult.Success -> {
                                    Toast.makeText(context, "已请求启动 ${card.name}", Toast.LENGTH_SHORT).show()
                                }
                                is LaunchResult.Failed -> {
                                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onEdit = { editing = card },
                )
            }
        }
    }

    editing?.let { card ->
        EditCardDialog(
            card = card,
            onDismiss = { editing = null },
            onSave = { path ->
                scope.launch {
                    vm.updateWorkspace(card.id, path)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun AgentCardItem(
    card: AgentCard,
    profileName: String,
    onEnter: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "模型：$profileName",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "工作区：${card.workspaceNamespace.name.lowercase()} ${card.workspacePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Text(
                "模板：${card.templateId} · 会话：${card.termuxSessionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnter) { Text("进入") }
                OutlinedButton(onClick = onEdit) { Text("编辑工作区") }
            }
        }
    }
}

@Composable
private fun EditCardDialog(
    card: AgentCard,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var path by remember(card.id) { mutableStateOf(card.workspacePath) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${card.name}") },
        text = {
            Column {
                Text("Ubuntu/Termux 内工作目录")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(path.trim()) }) { Text("保存") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
