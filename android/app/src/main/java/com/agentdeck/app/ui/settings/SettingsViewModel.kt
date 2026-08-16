package com.agentdeck.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.host.HostWriteApprovalMode
import com.agentdeck.app.domain.host.WorkspaceGrant
import com.agentdeck.app.domain.settings.ExperienceLevel
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.runtime.RuntimeCliStatus
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel : ViewModel() {
    private val setup = ServiceLocator.setup

    val state: StateFlow<SetupState> = setup.state
    val experienceLevel: StateFlow<ExperienceLevel> = ServiceLocator.experienceSettings.level
    val codexPermissionLevel: StateFlow<CodexPermissionLevel> =
        ServiceLocator.experienceSettings.codexPermissionLevel
    val hostWorkspaceEnabled: StateFlow<Boolean> =
        ServiceLocator.experienceSettings.hostWorkspaceEnabled
    val hostWriteApprovalMode: StateFlow<HostWriteApprovalMode> =
        ServiceLocator.experienceSettings.hostWriteApprovalMode
    val workspaceGrants: StateFlow<List<WorkspaceGrant>> = ServiceLocator.workspaceGrants.grants
    val isLabBuild: Boolean = BuildConfig.HOST_LAB
    val labRiskAccepted: StateFlow<Boolean> = ServiceLocator.experienceSettings.labRiskAccepted
    val labIntentEnabled: StateFlow<Boolean> = ServiceLocator.experienceSettings.labIntentEnabled
    val labUiEnabled: StateFlow<Boolean> = ServiceLocator.experienceSettings.labUiEnabled
    val labPrivEnabled: StateFlow<Boolean> = ServiceLocator.experienceSettings.labPrivEnabled
    private val backup = ServiceLocator.conversationBackup
    private val lastBackupExportAtState = MutableStateFlow<Long?>(null)
    val lastBackupExportAt: StateFlow<Long?> = lastBackupExportAtState.asStateFlow()
    private val runtimeStatusesState = MutableStateFlow(ServiceLocator.runtimeInventory.statuses())
    val runtimeStatuses: StateFlow<List<RuntimeCliStatus>> = runtimeStatusesState.asStateFlow()

    init {
        viewModelScope.launch {
            lastBackupExportAtState.value = backup.lastExportAtEpochMs()
        }
    }

    fun setAdvancedEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            if (enabled) ExperienceLevel.ADVANCED else ExperienceLevel.STANDARD,
        )
    }

    /** 连续开启高级后，Lab 需要开发者模式才能开 L2+。 */
    fun setDeveloperEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            when {
                enabled -> ExperienceLevel.DEVELOPER
                ServiceLocator.experienceSettings.level.value == ExperienceLevel.STANDARD ->
                    ExperienceLevel.STANDARD
                else -> ExperienceLevel.ADVANCED
            },
        )
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        ServiceLocator.experienceSettings.setCodexPermissionLevel(level)
    }

    fun setHostWorkspaceEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setHostWorkspaceEnabled(enabled)
    }

    fun setHostWriteApprovalMode(mode: HostWriteApprovalMode) {
        ServiceLocator.experienceSettings.setHostWriteApprovalMode(mode)
    }

    fun setLabRiskAccepted(accepted: Boolean) {
        ServiceLocator.experienceSettings.setLabRiskAccepted(accepted)
    }

    fun setLabIntentEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLabIntentEnabled(enabled)
    }

    fun setLabUiEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLabUiEnabled(enabled)
    }

    fun setLabPrivEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLabPrivEnabled(enabled)
    }

    fun addWorkspaceGrant(treeUri: Uri, displayName: String) {
        ServiceLocator.workspaceGrants.addGrant(treeUri, displayName)
        ServiceLocator.experienceSettings.setHostWorkspaceEnabled(true)
    }

    fun revokeWorkspaceGrant(grantId: String) {
        ServiceLocator.workspaceGrants.revoke(grantId)
    }

    fun suggestedBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        return "agentdeck-backup-" + stamp + ".json"
    }

    fun exportConversations(context: Context, uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val preview = backup.writeExport(context, uri)
                lastBackupExportAtState.value = backup.lastExportAtEpochMs()
                "已导出 " + preview.conversationCount + " 个会话（其中 " + preview.identityCount + " 个人设）"
            }.getOrElse { error -> "导出失败：" + (error.message ?: "未知错误") }
            onDone(message)
        }
    }

    fun refreshRuntimes() {
        runtimeStatusesState.value = ServiceLocator.runtimeInventory.statuses()
    }

    fun deleteCodexRuntime() {
        ServiceLocator.runtimeInventory.deleteCodex()
        refreshRuntimes()
        setup.scan(force = true)
    }

    fun importLocalBackup(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val preview = backup.importLocalCopy()
                "已从本机备份恢复 " + preview.conversationCount + " 个会话（其中 " + preview.identityCount + " 个人设）"
            }.getOrElse { error -> "恢复失败：" + (error.message ?: "未知错误") }
            onDone(message)
        }
    }

    fun importConversations(context: Context, uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val message = runCatching {
                val preview = backup.importFrom(context, uri)
                "已恢复 " + preview.conversationCount + " 个会话（其中 " + preview.identityCount + " 个人设）。模型密钥需要重新验证。"
            }.getOrElse { error -> "恢复失败：" + (error.message ?: "未知错误") }
            onDone(message)
        }
    }
}
