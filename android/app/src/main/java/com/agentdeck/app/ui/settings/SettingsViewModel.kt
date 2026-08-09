package com.agentdeck.app.ui.settings

import androidx.lifecycle.ViewModel
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.settings.ExperienceLevel
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.setup.SetupState
import com.agentdeck.app.domain.runtime.RuntimeSelection
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel : ViewModel() {
    private val setup = ServiceLocator.setup

    val state: StateFlow<SetupState> = setup.state
    val experienceLevel: StateFlow<ExperienceLevel> = ServiceLocator.experienceSettings.level
    val codexPermissionLevel: StateFlow<CodexPermissionLevel> =
        ServiceLocator.experienceSettings.codexPermissionLevel
    val runtimeSelection: StateFlow<RuntimeSelection> = ServiceLocator.runtimeSettings.selection

    fun setAdvancedEnabled(enabled: Boolean) {
        ServiceLocator.experienceSettings.setLevel(
            if (enabled) ExperienceLevel.ADVANCED else ExperienceLevel.STANDARD,
        )
    }

    fun setCodexPermissionLevel(level: CodexPermissionLevel) {
        ServiceLocator.experienceSettings.setCodexPermissionLevel(level)
    }

    fun setRuntimeSelection(selection: RuntimeSelection) {
        ServiceLocator.runtimeSettings.setSelection(selection)
        setup.scan()
    }

    fun scan() = setup.scan()

    fun openTermux(): Boolean = setup.openTermux()
}
