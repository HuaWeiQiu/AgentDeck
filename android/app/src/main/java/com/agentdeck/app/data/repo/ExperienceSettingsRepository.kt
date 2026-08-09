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

    val level: StateFlow<ExperienceLevel> = mutableLevel.asStateFlow()
    val codexPermissionLevel: StateFlow<CodexPermissionLevel> =
        mutableCodexPermissionLevel.asStateFlow()

    fun setLevel(level: ExperienceLevel) {
        if (mutableLevel.value == level) return
        preferences.edit { putString(KEY_LEVEL, level.name) }
        mutableLevel.value = level
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        if (mutableCodexPermissionLevel.value == level) return
        preferences.edit { putString(KEY_CODEX_PERMISSION_LEVEL, level.name) }
        mutableCodexPermissionLevel.value = level
    }

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_experience"
        private const val KEY_LEVEL = "level"
        private const val KEY_CODEX_PERMISSION_LEVEL = "codex_permission_level"
    }
}
