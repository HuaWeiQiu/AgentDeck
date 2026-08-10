package com.agentdeck.app.ui.config

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexConfigScreen(
    onBack: () -> Unit,
    vm: CodexConfigViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }
    var discardDialogVisible by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        if (state.hasUnsavedChanges) discardDialogVisible = true else onBack()
    }

    BackHandler(enabled = state.hasUnsavedChanges, onBack = requestBack)
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("Codex 参数") },
                actions = {
                    IconButton(
                        onClick = vm::save,
                        enabled = state.hasUnsavedChanges && !state.isSaving,
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(Modifier.padding(10.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Save, contentDescription = "保存")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("恢复示例模板") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Restore, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    vm.restoreDefaultDraft()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("官方参数参考") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(CODEX_CONFIG_REFERENCE_URL),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    "agentdeck.config.toml · 内嵌 ARM64 · 启动前同步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = state.content,
                    onValueChange = vm::updateContent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    label = { Text("TOML") },
                    supportingText = {
                        Text(
                            state.error
                                ?: "无必填项；去掉行首 # 启用示例。账号授权与 API Key 不保存在此文件",
                        )
                    },
                    isError = state.error != null,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
            }
        }
    }

    if (discardDialogVisible) {
        AlertDialog(
            onDismissRequest = { discardDialogVisible = false },
            title = { Text("放弃未保存的修改？") },
            confirmButton = {
                TextButton(onClick = onBack) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { discardDialogVisible = false }) { Text("继续编辑") }
            },
        )
    }
}

private const val CODEX_CONFIG_REFERENCE_URL =
    "https://developers.openai.com/codex/config-reference/"
