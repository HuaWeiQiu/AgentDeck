package com.agentdeck.app.ui.models

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    vm: ModelsViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<ProviderEditorDraft?>(null) }
    var deleting by remember { mutableStateOf<ProviderProfile?>(null) }
    var showCurrentConfig by remember { mutableStateOf(false) }
    var importingCurrent by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val activeEditor = editor
    if (activeEditor != null) {
        ProviderEditorScreen(
            initial = activeEditor,
            onBack = { editor = null },
            onDiscover = vm::discover,
            onSave = vm::save,
            onSaved = {
                editor = null
                Toast.makeText(context, "模型服务已保存", Toast.LENGTH_SHORT).show()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editor = vm.newDraft() }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加模型服务")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("当前 Codex 配置") },
                    supportingContent = { Text("导入现有 Termux / Ubuntu CLI Provider") },
                    leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        importError = null
                        showCurrentConfig = true
                    },
                )
                HorizontalDivider()
            }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "尚未添加第三方模型服务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                    )
                }
            }
            items(profiles, key = { it.id }) { profile ->
                ProviderRow(
                    profile = profile,
                    onEdit = { editor = vm.editDraft(profile) },
                    onDelete = { deleting = profile },
                )
                HorizontalDivider()
            }
        }
    }

    if (showCurrentConfig) {
        AlertDialog(
            onDismissRequest = { if (!importingCurrent) showCurrentConfig = false },
            title = { Text("当前 Codex 配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("可从已有 Termux / Ubuntu 读取当前 CLI Provider，并用于内嵌 Codex。")
                    Text(
                        "API Key 会转存到 Android Keystore 加密凭据区，不会显示在页面中。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    importError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            importingCurrent = true
                            importError = null
                            vm.importCurrentCodexProvider().fold(
                                onSuccess = { imported ->
                                    importingCurrent = false
                                    showCurrentConfig = false
                                    Toast.makeText(
                                        context,
                                        "已导入 ${imported.profile.name} · ${imported.modelId}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onFailure = { error ->
                                    importingCurrent = false
                                    importError = error.message ?: "无法导入当前 Codex 配置"
                                },
                            )
                        }
                    },
                    enabled = !importingCurrent,
                ) {
                    if (importingCurrent) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp).size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (importingCurrent) "正在导入" else "导入并用于 Codex")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCurrentConfig = false },
                    enabled = !importingCurrent,
                ) { Text("取消") }
            },
        )
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 ${profile.name}") },
            text = { Text("已被对话使用的服务不能删除。删除后，加密 API Key 和模型缓存也会移除。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            vm.delete(profile).fold(
                                onSuccess = {
                                    deleting = null
                                    Toast.makeText(context, "模型服务已删除", Toast.LENGTH_SHORT).show()
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
private fun ProviderRow(
    profile: ProviderProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    profile.baseUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    profile.defaultModel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            val ready = profile.connectionStatus == ProviderConnectionStatus.READY ||
                profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED
            Icon(
                if (ready) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑 ${profile.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除 ${profile.name}")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorScreen(
    initial: ProviderEditorDraft,
    onBack: () -> Unit,
    onDiscover: suspend (ProviderEditorDraft) -> ProviderEditorDraft,
    onSave: suspend (ProviderEditorDraft) -> Result<ProviderProfile>,
    onSaved: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var keyVisible by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.id == null) "添加模型服务" else "编辑模型服务") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !working) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.SUB2API,
                        onClick = {
                            draft = draft.copy(
                                adapterId = ProviderAdapterId.SUB2API,
                                validated = false,
                                models = emptyList(),
                                error = null,
                            )
                        },
                        label = { Text("Sub2API") },
                    )
                    FilterChip(
                        selected = draft.adapterId == ProviderAdapterId.OPENAI_RESPONSES,
                        onClick = {
                            draft = draft.copy(
                                adapterId = ProviderAdapterId.OPENAI_RESPONSES,
                                validated = false,
                                models = emptyList(),
                                error = null,
                            )
                        },
                        label = { Text("Responses 兼容") },
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it, error = null) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = {
                        draft = draft.copy(
                            baseUrl = it,
                            validated = false,
                            models = emptyList(),
                            error = null,
                        )
                    },
                    label = { Text("API Base URL") },
                    placeholder = { Text("https://example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.apiKey,
                    onValueChange = {
                        draft = draft.copy(apiKey = it, validated = false, error = null)
                    },
                    label = {
                        Text(if (draft.hasStoredCredential) "API Key（留空沿用）" else "API Key")
                    },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            working = true
                            draft = onDiscover(draft)
                            working = false
                        }
                    },
                    enabled = !working && draft.name.isNotBlank() && draft.baseUrl.isNotBlank() &&
                        (draft.apiKey.isNotBlank() || draft.hasStoredCredential),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp).size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Text(if (working) "正在验证" else "验证并获取模型")
                }
            }
            draft.error?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                if (draft.models.isNotEmpty()) {
                    ModelDropdown(
                        selected = draft.model,
                        models = draft.models,
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it },
                        onQueryChange = {
                            draft = draft.copy(model = "", error = null)
                        },
                        onSelect = { model ->
                            draft = draft.copy(model = model.id, error = null)
                            modelExpanded = false
                        },
                    )
                } else {
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it, error = null) },
                        label = { Text("模型 ID") },
                        singleLine = true,
                        enabled = draft.status == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = {
                        scope.launch {
                            working = true
                            onSave(draft).fold(
                                onSuccess = {
                                    draft = draft.copy(apiKey = "")
                                    onSaved()
                                },
                                onFailure = { error ->
                                    draft = draft.copy(error = error.message ?: "保存失败")
                                    working = false
                                },
                            )
                        }
                    },
                    enabled = !working && draft.validated && draft.model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("保存模型服务")
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    selected: String,
    models: List<ProviderModel>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onQueryChange: () -> Unit,
    onSelect: (ProviderModel) -> Unit,
) {
    var query by remember(models) { mutableStateOf(selected) }
    val filtered = remember(models, query) {
        models.filter { model ->
            query.isBlank() || model.id.contains(query, ignoreCase = true) ||
                model.displayName.contains(query, ignoreCase = true)
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { next ->
            if (!next) query = selected
            onExpandedChange(next)
        },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                onQueryChange()
                onExpandedChange(true)
            },
            label = { Text("默认模型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            filtered.take(MAX_VISIBLE_MODELS).forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        query = model.id
                        onSelect(model)
                    },
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val MAX_VISIBLE_MODELS = 100
