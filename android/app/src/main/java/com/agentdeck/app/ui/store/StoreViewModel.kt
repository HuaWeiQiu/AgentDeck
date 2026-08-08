package com.agentdeck.app.ui.store

import androidx.lifecycle.ViewModel
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.flow.StateFlow

class StoreViewModel : ViewModel() {
    private val setup = ServiceLocator.setup

    val state: StateFlow<SetupState> = setup.state

    fun scan() = setup.scan()

    fun installCodex() = setup.installCodex()

    fun openTermuxInstallPage(): Boolean = setup.openTermuxInstallPage()

    fun openTermux(): Boolean = setup.openTermux()

    fun openTermuxAppSettings(): Boolean = setup.openTermuxAppSettings()

    fun allowExternalAppsFixCommand(): String = setup.allowExternalAppsFixCommand()

    fun startCodexAuthentication(): Result<Unit> = setup.startCodexAuthentication()
}
