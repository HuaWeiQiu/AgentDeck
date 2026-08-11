package com.agentdeck.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.host.HostWriteApprovalMode
import com.agentdeck.app.domain.host.WorkspaceGrant
import com.agentdeck.app.domain.settings.ExperienceLevel
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.flow.StateFlow

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
}
