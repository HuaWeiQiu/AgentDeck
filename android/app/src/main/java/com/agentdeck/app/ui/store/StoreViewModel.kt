package com.agentdeck.app.ui.store

import androidx.lifecycle.ViewModel
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.setup.SetupState
import kotlinx.coroutines.flow.StateFlow

class StoreViewModel : ViewModel() {
    private val setup = ServiceLocator.setup

    val state: StateFlow<SetupState> = setup.state

    fun scan() = setup.scan(force = true)

    fun installCodex() = setup.installCodex()

}
