package com.agentdeck.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.agentdeck.app.di.ServiceLocator
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
    val workspaceGrants: StateFlow<List<WorkspaceGrant>> = ServiceLocator.workspaceGrants.grants

    fun setAdvancedEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            if (enabled) ExperienceLevel.ADVANCED else ExperienceLevel.STANDARD,
        )
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        ServiceLocator.experienceSettings.setCodexPermissionLevel(level)
    }

    fun setHostWorkspaceEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setHostWorkspaceEnabled(enabled)
    }

    fun addWorkspaceGrant(treeUri: Uri, displayName: String) {
        ServiceLocator.workspaceGrants.addGrant(treeUri, displayName)
        // 选中文件夹后自动打开 L1 开关（仍要求高级模式）
        ServiceLocator.experienceSettings.setHostWorkspaceEnabled(true)
    }

    fun revokeWorkspaceGrant(grantId: String) {
        ServiceLocator.workspaceGrants.revoke(grantId)
    }
}
