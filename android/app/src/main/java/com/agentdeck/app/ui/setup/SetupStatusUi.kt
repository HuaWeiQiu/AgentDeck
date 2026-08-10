package com.agentdeck.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.setup.SetupAction
import com.agentdeck.app.domain.setup.SetupState

data class SetupDisplayStep(
    val id: String,
    val label: String,
    val status: EnvironmentCheckStatus,
    val detail: String,
)

data class CustomerSetupPresentation(
    val title: String,
    val summary: String,
    val primaryActionLabel: String,
    val errorMessage: String?,
)

fun customerSetupPresentation(state: SetupState): CustomerSetupPresentation {
    val title = when {
        state.isScanning -> "正在检查设备"
        state.isInstalling -> "正在准备 Codex"
        state.error != null -> "准备未完成"
        state.action == SetupAction.READY -> "一切就绪"
        state.action == SetupAction.CONFIGURE_CODEX_AUTH -> "连接模型服务"
        state.action == SetupAction.INSTALL_CODEX -> "准备 Codex"
        else -> "完成设备准备"
    }
    val summary = when {
        state.isScanning -> "正在确认本机运行环境和模型连接"
        state.isInstalling -> "正在安装并验证所需组件"
        else -> when (state.action) {
            SetupAction.SCAN -> "正在确认本机运行环境和模型连接"
            SetupAction.INSTALL_CODEX -> "将安装或修复所需组件，不会删除对话和项目"
            SetupAction.CONFIGURE_CODEX_AUTH ->
                "选择 ChatGPT、OpenAI API Key 或第三方 Responses 服务"
            SetupAction.UNSUPPORTED_DEVICE -> "当前测试版仅支持 ARM64 Android 设备"
            SetupAction.READY -> "可以开始新的对话"
        }
    }
    val primaryActionLabel = when {
        state.isScanning -> "检查中"
        state.isInstalling -> "准备中"
        else -> when (state.action) {
            SetupAction.SCAN -> "重新检查"
            SetupAction.INSTALL_CODEX -> "安装或修复"
            SetupAction.CONFIGURE_CODEX_AUTH -> "连接模型服务"
            SetupAction.UNSUPPORTED_DEVICE -> "设备不支持"
            SetupAction.READY -> "开始对话"
        }
    }
    return CustomerSetupPresentation(
        title = title,
        summary = summary,
        primaryActionLabel = primaryActionLabel,
        errorMessage = state.error?.let {
            "未能完成当前步骤。请重试；现有对话和项目不会受到影响。"
        },
    )
}

fun customerSetupSteps(report: EnvironmentReport): List<SetupDisplayStep> {
    return listOf(
        report.combinedStep(
            id = "device_ready",
            label = "设备准备",
            checkIds = listOf("embedded_supported"),
        ),
        report.combinedStep(
            id = "local_runtime",
            label = "本机运行环境",
            checkIds = listOf("embedded_runtime", "ubuntu_installed", "embedded_tools"),
        ),
        report.combinedStep(
            id = "agent_ready",
            label = "Codex",
            checkIds = listOf("codex_installed", "codex_wrapper"),
        ),
        report.combinedStep(
            id = "model_connection",
            label = "模型连接",
            checkIds = listOf("codex_authenticated"),
        ),
    )
}

@Composable
fun SetupStepList(
    steps: List<SetupDisplayStep>,
    modifier: Modifier = Modifier,
    showDetails: Boolean = false,
) {
    Column(modifier) {
        steps.forEachIndexed { index, step ->
            StatusRow(step.label, step.status, if (showDetails) step.detail else null)
            if (index != steps.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
fun TechnicalEnvironmentList(
    report: EnvironmentReport,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        report.checks.forEachIndexed { index, check ->
            StatusRow(check.label, check.status, check.detail)
            if (index != report.checks.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    status: EnvironmentCheckStatus,
    detail: String?,
) {
    val appearance = statusAppearance(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = appearance.icon,
            contentDescription = appearance.label,
            tint = appearance.color,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            appearance.label,
            style = MaterialTheme.typography.labelMedium,
            color = appearance.color,
        )
    }
}

@Composable
private fun statusAppearance(status: EnvironmentCheckStatus): StatusAppearance = when (status) {
    EnvironmentCheckStatus.UNKNOWN -> StatusAppearance(
        Icons.Filled.RadioButtonUnchecked,
        "待检测",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    EnvironmentCheckStatus.CHECKING -> StatusAppearance(
        Icons.Filled.HourglassTop,
        "检测中",
        MaterialTheme.colorScheme.primary,
    )
    EnvironmentCheckStatus.READY -> StatusAppearance(
        Icons.Filled.CheckCircle,
        "完成",
        MaterialTheme.colorScheme.secondary,
    )
    EnvironmentCheckStatus.ACTION_REQUIRED -> StatusAppearance(
        Icons.Filled.WarningAmber,
        "需要操作",
        MaterialTheme.colorScheme.tertiary,
    )
    EnvironmentCheckStatus.BLOCKED -> StatusAppearance(
        Icons.Filled.Block,
        "等待前置步骤",
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
    EnvironmentCheckStatus.ERROR -> StatusAppearance(
        Icons.Filled.ErrorOutline,
        "检查失败",
        MaterialTheme.colorScheme.error,
    )
}

private fun EnvironmentReport.combinedStep(
    id: String,
    label: String,
    checkIds: List<String>,
): SetupDisplayStep {
    val checks = checkIds.map { checkId -> check(checkId) }
    val missingCheck = checks.any { it == null }
    val firstIncomplete = checks.filterNotNull().firstOrNull { !it.ok }
    val status = when {
        missingCheck -> EnvironmentCheckStatus.ERROR
        firstIncomplete != null -> firstIncomplete.status
        else -> EnvironmentCheckStatus.READY
    }
    return SetupDisplayStep(
        id = id,
        label = label,
        status = status,
        detail = customerStatusDetail(status),
    )
}

private fun customerStatusDetail(status: EnvironmentCheckStatus): String = when (status) {
    EnvironmentCheckStatus.UNKNOWN -> "等待检查"
    EnvironmentCheckStatus.CHECKING -> "正在检查"
    EnvironmentCheckStatus.READY -> "已完成"
    EnvironmentCheckStatus.ACTION_REQUIRED -> "需要完成此步骤"
    EnvironmentCheckStatus.BLOCKED -> "等待前一步完成"
    EnvironmentCheckStatus.ERROR -> "暂时无法完成检查"
}

private data class StatusAppearance(
    val icon: ImageVector,
    val label: String,
    val color: Color,
)
