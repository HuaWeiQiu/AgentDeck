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
import com.agentdeck.app.domain.install.InstallPhase
import com.agentdeck.app.domain.install.RecipeInstallProgress
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

data class SetupInstallProgressPresentation(
    val title: String,
    val detail: String,
    val stageLabel: String,
    val overallFraction: Float,
    val hint: String? = null,
)

fun setupInstallProgressPresentation(
    progress: RecipeInstallProgress?,
): SetupInstallProgressPresentation {
    if (progress == null) {
        return SetupInstallProgressPresentation(
            title = "检查现有环境",
            detail = "正在确认设备架构、存储空间和已安装组件",
            stageLabel = "准备开始",
            overallFraction = 0.02f,
        )
    }
    val downloadFraction = if (progress.bytesDone != null && progress.bytesTotal != null &&
        progress.bytesTotal > 0
    ) {
        (progress.bytesDone.toFloat() / progress.bytesTotal).coerceIn(0f, 1f)
    } else 0f
    val presentation = when (progress.phase) {
        InstallPhase.PROBING -> ProgressStage(1, "检查安装条件", "确认架构、空间和 APK 运行组件", 0.03f)
        InstallPhase.DOWNLOADING -> ProgressStage(
            2,
            "正在下载聊天组件",
            if (progress.bytesDone != null && progress.bytesTotal != null) {
                "${formatInstallBytes(progress.bytesDone)} / ${formatInstallBytes(progress.bytesTotal)}" +
                    "（${(downloadFraction * 100).toInt()}%）"
            } else {
                "正在连接下载线路"
            },
            0.05f + 0.45f * downloadFraction,
        )
        InstallPhase.VERIFYING_ARTIFACTS -> ProgressStage(
            3,
            "校验下载文件",
            "核对文件大小与 SHA-256，损坏内容不会安装",
            0.54f,
        )
        InstallPhase.EXTRACTING -> ProgressStage(
            4,
            "解压 Runtime",
            "正在安全解压 Ubuntu 和 Codex，可能需要几分钟",
            0.62f,
        )
        InstallPhase.INSTALLING,
        InstallPhase.INSTALLING_TOOLS,
        -> ProgressStage(
            5,
            "安装基础工具",
            "通过国内软件源安装证书、Git、Python 和 PDF 工具，可能需要几分钟",
            0.76f,
        )
        InstallPhase.VERIFYING,
        InstallPhase.VERIFYING_RUNTIME,
        -> ProgressStage(
            6,
            "验证 Runtime",
            "正在运行 Ubuntu、Codex 和文件解析器自检",
            0.93f,
        )
        InstallPhase.COMPLETE -> ProgressStage(
            7,
            "Runtime 已安装",
            "正在刷新设备和模型连接状态",
            1f,
        )
    }
    return SetupInstallProgressPresentation(
        title = presentation.title,
        detail = presentation.detail,
        stageLabel = customerInstallStageLabel(presentation.index),
        overallFraction = presentation.fraction,
        hint = customerInstallHint(progress),
    )
}

internal fun customerInstallHint(progress: RecipeInstallProgress?): String? {
    progress ?: return null
    if (progress.phase != InstallPhase.DOWNLOADING) return null
    return when {
        progress.switchingSource -> "当前线路较慢，正在换一条线路继续下"
        progress.sourceSwitchCount > 0 && progress.prefersDomesticSources == false ->
            "当前网络走了国际线路，下载可能较慢，已换过 ${progress.sourceSwitchCount} 次线路"
        progress.sourceSwitchCount > 0 ->
            "刚才那条线路不通或太慢，已换过 ${progress.sourceSwitchCount} 次线路"
        progress.prefersDomesticSources == false ->
            "当前网络走了国际线路，下载可能较慢"
        else -> "请保持 Wi-Fi 连接，第一次准备大约需要几分钟"
    }
}

private fun customerInstallStageLabel(index: Int): String = when (index) {
    1 -> "正在检查"
    2 -> "正在下载"
    3 -> "正在校验"
    4 -> "正在解压"
    5 -> "正在安装工具"
    6 -> "正在验证"
    7 -> "即将完成"
    else -> "准备中"
}

fun customerSetupPresentation(state: SetupState): CustomerSetupPresentation {
    val title = when {
        state.isScanning -> "正在检查设备"
        state.isInstalling -> "正在准备聊天环境"
        state.error != null -> "准备未完成"
        state.action == SetupAction.READY -> "一切就绪"
        state.action == SetupAction.CONFIGURE_CODEX_AUTH -> "连接模型服务"
        state.action == SetupAction.INSTALL_CODEX -> "准备聊天环境"
        else -> "完成设备准备"
    }
    val summary = when {
        state.isScanning -> "正在确认这台手机能不能开始聊天"
        state.isInstalling -> "第一次需要下载组件，请保持网络畅通"
        else -> when (state.action) {
            SetupAction.SCAN -> "正在确认本机运行环境和模型连接"
            SetupAction.INSTALL_CODEX -> "将安装或修复所需组件，不会删除对话和项目"
            SetupAction.CONFIGURE_CODEX_AUTH ->
                "选择 ChatGPT、OpenAI API Key 或第三方 Responses 服务"
            SetupAction.UNSUPPORTED_DEVICE -> "当前测试版仅支持 ARM64 或 x86_64 Android 设备"
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
        errorMessage = state.error?.let(::customerSetupErrorMessage),
    )
}

/** 准备失败时保留可操作原因；不伪造成功，也不再吞掉具体错误。 */
internal fun customerSetupErrorMessage(rawError: String): String {
    val reason = rawError.trim().replace(Regex("\\s+"), " ").ifBlank { "未知错误" }
    return "未能完成当前步骤。现有对话和项目不会受到影响。\n\n原因：$reason"
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

private data class ProgressStage(
    val index: Int,
    val title: String,
    val detail: String,
    val fraction: Float,
)

private fun formatInstallBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private const val INSTALL_STAGE_COUNT = 7
