package com.agentdeck.app.ui.setup

import androidx.compose.foundation.isSystemInDarkTheme
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
import com.agentdeck.app.domain.model.EnvironmentCheck
import com.agentdeck.app.domain.model.EnvironmentCheckStatus
import com.agentdeck.app.domain.model.EnvironmentReport

data class SetupDisplayStep(
    val id: String,
    val label: String,
    val status: EnvironmentCheckStatus,
    val detail: String,
)

fun primarySetupSteps(report: EnvironmentReport): List<SetupDisplayStep> {
    val ubuntu = combinedStep(
        id = "ubuntu_runtime",
        label = "Ubuntu 24.04",
        checks = listOfNotNull(report.check("proot_distro"), report.check("ubuntu_installed")),
    )
    return listOf(
        report.display("termux_installed", "Termux"),
        report.display("termux_run_command_permission", "调用权限"),
        report.display("termux_background_execution", "后台运行"),
        report.display("allow_external_apps", "Termux 集成"),
        ubuntu,
        report.display("codex_installed", "Codex CLI"),
        report.display("codex_authenticated", "Codex 认证"),
        report.display("codex_wrapper", "启动组件"),
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
        if (isSystemInDarkTheme()) Color(0xFF6EE7B7) else Color(0xFF16825D),
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

private fun EnvironmentReport.display(id: String, label: String): SetupDisplayStep {
    val check = check(id)
    return SetupDisplayStep(
        id = id,
        label = label,
        status = check?.status ?: EnvironmentCheckStatus.ERROR,
        detail = check?.detail ?: "未返回检查结果",
    )
}

private fun combinedStep(
    id: String,
    label: String,
    checks: List<EnvironmentCheck>,
): SetupDisplayStep {
    val firstIncomplete = checks.firstOrNull { !it.ok }
    return SetupDisplayStep(
        id = id,
        label = label,
        status = when {
            checks.isEmpty() -> EnvironmentCheckStatus.ERROR
            firstIncomplete != null -> firstIncomplete.status
            else -> EnvironmentCheckStatus.READY
        },
        detail = when {
            checks.isEmpty() -> "未返回 Ubuntu 检查结果"
            firstIncomplete != null -> firstIncomplete.detail
            else -> checks.last().detail
        },
    )
}

private data class StatusAppearance(
    val icon: ImageVector,
    val label: String,
    val color: Color,
)
