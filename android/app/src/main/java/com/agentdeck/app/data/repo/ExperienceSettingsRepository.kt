package com.agentdeck.app.data.repo

import android.content.Context
import androidx.core.content.edit
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.settings.ExperienceLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExperienceSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableLevel = MutableStateFlow(
        ExperienceLevel.fromStorage(preferences.getString(KEY_LEVEL, null)),
    )
    private val mutableCodexPermissionLevel = MutableStateFlow(
        CodexPermissionLevel.fromStorage(preferences.getString(KEY_CODEX_PERMISSION_LEVEL, null)),
    )
    private val mutableHostWorkspaceEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_HOST_WORKSPACE_ENABLED, false),
    )

    val level: StateFlow<ExperienceLevel> = mutableLevel.asStateFlow()
    val codexPermissionLevel: StateFlow<CodexPermissionLevel> =
        mutableCodexPermissionLevel.asStateFlow()
    /** L1 本机工作区总开关；默认 false，与 Codex 权限档位无关。 */
    val hostWorkspaceEnabled: StateFlow<Boolean> = mutableHostWorkspaceEnabled.asStateFlow()

    fun setLevel(level: ExperienceLevel) {
        if (mutableLevel.value == level) return
        preferences.edit { putString(KEY_LEVEL, level.name) }
        mutableLevel.value = level
        // 退出高级模式时强制关闭宿主工作区，避免标准模式残留能力
        if (level == ExperienceLevel.STANDARD && mutableHostWorkspaceEnabled.value) {
            setHostWorkspaceEnabled(false)
        }
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        if (mutableCodexPermissionLevel.value == level) return
        preferences.edit { putString(KEY_CODEX_PERMISSION_LEVEL, level.name) }
        mutableCodexPermissionLevel.value = level
    }

    fun setHostWorkspaceEnabled(enabled: Boolean) {
        if (mutableHostWorkspaceEnabled.value == enabled) return
        // 标准模式不允许开启
        val effective = enabled && mutableLevel.value.advancedEnabled
        preferences.edit { putBoolean(KEY_HOST_WORKSPACE_ENABLED, effective) }
        mutableHostWorkspaceEnabled.value = effective
    }

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_experience"
        private const val KEY_LEVEL = "level"
        private const val KEY_CODEX_PERMISSION_LEVEL = "codex_permission_level"
        private const val KEY_HOST_WORKSPACE_ENABLED = "host_workspace_enabled"
    }
}
