package com.agentdeck.app.data.repo

import android.content.Context
import androidx.core.content.edit
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.domain.host.HostWriteApprovalMode
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.settings.ConversationMode
import com.agentdeck.app.domain.settings.ExperienceLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExperienceSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableConversationMode = MutableStateFlow(
        ConversationMode.fromStorage(preferences.getString(KEY_CONVERSATION_MODE, null)),
    )
    private val mutableLevel = MutableStateFlow(
        ExperienceLevel.fromStorage(preferences.getString(KEY_LEVEL, null)),
    )
    private val mutableCodexPermissionLevel = MutableStateFlow(
        CodexPermissionLevel.fromStorage(preferences.getString(KEY_CODEX_PERMISSION_LEVEL, null)),
    )
    private val mutableHostWorkspaceEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_HOST_WORKSPACE_ENABLED, false),
    )
    private val mutableHostWriteApprovalMode = MutableStateFlow(
        HostWriteApprovalMode.fromStorage(preferences.getString(KEY_HOST_WRITE_APPROVAL, null)),
    )
    private val mutableLabRiskAccepted = MutableStateFlow(
        preferences.getBoolean(KEY_LAB_RISK, false) && BuildConfig.HOST_LAB,
    )
    private val mutableLabIntentEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_LAB_INTENT, false) && BuildConfig.HOST_LAB,
    )
    private val mutableLabUiEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_LAB_UI, false) && BuildConfig.HOST_LAB,
    )
    private val mutableLabPrivEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_LAB_PRIV, false) && BuildConfig.HOST_LAB,
    )

    /** 轻聊 / 开发：影响新建会话可选引擎与默认入口。 */
    val conversationMode: StateFlow<ConversationMode> = mutableConversationMode.asStateFlow()
    val level: StateFlow<ExperienceLevel> = mutableLevel.asStateFlow()
    val codexPermissionLevel: StateFlow<CodexPermissionLevel> =
        mutableCodexPermissionLevel.asStateFlow()
    /** L1 本机工作区总开关；默认 false，与 Codex 权限档位无关。 */
    val hostWorkspaceEnabled: StateFlow<Boolean> = mutableHostWorkspaceEnabled.asStateFlow()
    /** 真实目录写操作：每次询问 / 不再询问（默认每次询问）。 */
    val hostWriteApprovalMode: StateFlow<HostWriteApprovalMode> =
        mutableHostWriteApprovalMode.asStateFlow()
    val labRiskAccepted: StateFlow<Boolean> = mutableLabRiskAccepted.asStateFlow()
    val labIntentEnabled: StateFlow<Boolean> = mutableLabIntentEnabled.asStateFlow()
    val labUiEnabled: StateFlow<Boolean> = mutableLabUiEnabled.asStateFlow()
    val labPrivEnabled: StateFlow<Boolean> = mutableLabPrivEnabled.asStateFlow()

    fun setConversationMode(mode: ConversationMode) {
        if (mutableConversationMode.value == mode) return
        preferences.edit { putString(KEY_CONVERSATION_MODE, mode.name) }
        mutableConversationMode.value = mode
    }

    fun setLevel(level: ExperienceLevel) {
        if (mutableLevel.value == level) return
        preferences.edit { putString(KEY_LEVEL, level.name) }
        mutableLevel.value = level
        if (level == ExperienceLevel.STANDARD) {
            setHostWorkspaceEnabled(false)
            clearLabFlags()
        } else if (level != ExperienceLevel.DEVELOPER) {
            clearLabFlags()
        }
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        if (mutableCodexPermissionLevel.value == level) return
        preferences.edit { putString(KEY_CODEX_PERMISSION_LEVEL, level.name) }
        mutableCodexPermissionLevel.value = level
    }

    fun setHostWorkspaceEnabled(enabled: Boolean) {
        if (mutableHostWorkspaceEnabled.value == enabled) return
        val effective = enabled && mutableLevel.value.advancedEnabled
        preferences.edit { putBoolean(KEY_HOST_WORKSPACE_ENABLED, effective) }
        mutableHostWorkspaceEnabled.value = effective
        if (!effective) {
            // 关闭工作区时回到最严写审批，避免下次开启残留「不再询问」
            setHostWriteApprovalMode(HostWriteApprovalMode.ALWAYS_ASK)
        }
    }

    fun setHostWriteApprovalMode(mode: HostWriteApprovalMode) {
        if (mutableHostWriteApprovalMode.value == mode) return
        preferences.edit { putString(KEY_HOST_WRITE_APPROVAL, mode.name) }
        mutableHostWriteApprovalMode.value = mode
    }

    fun setLabRiskAccepted(accepted: Boolean) {
        if (!BuildConfig.HOST_LAB) return
        val effective = accepted && mutableLevel.value == ExperienceLevel.DEVELOPER
        preferences.edit { putBoolean(KEY_LAB_RISK, effective) }
        mutableLabRiskAccepted.value = effective
        if (!effective) {
            setLabIntentEnabled(false)
            setLabUiEnabled(false)
            setLabPrivEnabled(false)
        }
    }

    fun setLabIntentEnabled(enabled: Boolean) {
        if (!BuildConfig.HOST_LAB) return
        val effective = enabled && mutableLabRiskAccepted.value
        preferences.edit { putBoolean(KEY_LAB_INTENT, effective) }
        mutableLabIntentEnabled.value = effective
    }

    fun setLabUiEnabled(enabled: Boolean) {
        if (!BuildConfig.HOST_LAB) return
        val effective = enabled && mutableLabRiskAccepted.value
        preferences.edit { putBoolean(KEY_LAB_UI, effective) }
        mutableLabUiEnabled.value = effective
    }

    fun setLabPrivEnabled(enabled: Boolean) {
        if (!BuildConfig.HOST_LAB) return
        val effective = enabled && mutableLabRiskAccepted.value
        preferences.edit { putBoolean(KEY_LAB_PRIV, effective) }
        mutableLabPrivEnabled.value = effective
    }

    private fun clearLabFlags() {
        if (mutableLabRiskAccepted.value) setLabRiskAccepted(false)
    }

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_experience"
        private const val KEY_CONVERSATION_MODE = "conversation_mode"
        private const val KEY_LEVEL = "level"
        private const val KEY_CODEX_PERMISSION_LEVEL = "codex_permission_level"
        private const val KEY_HOST_WORKSPACE_ENABLED = "host_workspace_enabled"
        private const val KEY_HOST_WRITE_APPROVAL = "host_write_approval_mode"
        private const val KEY_LAB_RISK = "lab_risk_accepted"
        private const val KEY_LAB_INTENT = "lab_intent_enabled"
        private const val KEY_LAB_UI = "lab_ui_enabled"
        private const val KEY_LAB_PRIV = "lab_priv_enabled"
    }
}
