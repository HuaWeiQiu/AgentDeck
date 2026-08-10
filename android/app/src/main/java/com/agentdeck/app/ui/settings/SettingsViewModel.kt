package com.agentdeck.app.ui.settings

import androidx.lifecycle.ViewModel
import com.agentdeck.app.di.ServiceLocator
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

    fun setAdvancedEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            if (enabled) ExperienceLevel.ADVANCED else ExperienceLevel.STANDARD,
        )
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        ServiceLocator.experienceSettings.setCodexPermissionLevel(level)
    }

}
