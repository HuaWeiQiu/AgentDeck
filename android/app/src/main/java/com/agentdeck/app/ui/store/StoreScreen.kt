package com.agentdeck.app.ui.store

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentdeck.app.domain.setup.SetupAction
import com.agentdeck.app.ui.setup.customerSetupPresentation
import com.agentdeck.app.ui.setup.customerSetupSteps
import com.agentdeck.app.ui.setup.SetupStepList
import com.agentdeck.app.ui.setup.setupInstallProgressPresentation
import com.agentdeck.app.ui.theme.AgentDeckTopBar
import com.agentdeck.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: (() -> Unit)? = null,
    onReady: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    vm: StoreViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val presentation = customerSetupPresentation(state)
    val steps = customerSetupSteps(state.report)
    val completed = steps.count { it.status == com.agentdeck.app.domain.model.EnvironmentCheckStatus.READY }

    Scaffold(
        topBar = {
            AgentDeckTopBar(
                title = "准备 AgentDeck",
                navigationIcon = {
                    onBack?.let { navigateBack ->
                        IconButton(onClick = navigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
        ) {
            item {
                Column {
                    Text(
                        presentation.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        presentation.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(
                        "$completed / ${steps.size} 已完成",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isInstalling) {
                        Spacer(Modifier.height(14.dp))
                        val install = setupInstallProgressPresentation(state.progress)
                        Text(install.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            install.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { install.overallFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            install.stageLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        install.hint?.let { hint ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (state.isScanning) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }

            presentation.errorMessage?.let { error ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                SetupStepList(steps)
            }

            item {
                Button(
                    onClick = {
                        performPrimaryAction(
                            action = state.action,
                            context = context,
                            vm = vm,
                            onReady = onReady,
                            onOpenModels = onOpenModels,
                        )
                    },
                    enabled = !state.isScanning && !state.isInstalling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isScanning || state.isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                    } else if (state.action == SetupAction.READY) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.size(AppSpacing.sm))
                    }
                    Text(presentation.primaryActionLabel)
                }
            }
        }
    }
}

private fun performPrimaryAction(
    action: SetupAction,
    context: Context,
    vm: StoreViewModel,
    onReady: () -> Unit,
    onOpenModels: () -> Unit,
) {
    when (action) {
        SetupAction.SCAN -> vm.scan()
        SetupAction.INSTALL_CODEX -> vm.installCodex()
        SetupAction.CONFIGURE_CODEX_AUTH -> onOpenModels()
        SetupAction.UNSUPPORTED_DEVICE -> Toast.makeText(
            context,
            "当前测试版仅支持 ARM64 或 x86_64 Android 设备",
            Toast.LENGTH_LONG,
        ).show()
        SetupAction.READY -> onReady()
    }
}
