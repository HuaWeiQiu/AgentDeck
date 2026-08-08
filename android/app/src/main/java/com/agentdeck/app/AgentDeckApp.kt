package com.agentdeck.app

import android.app.Application
import com.agentdeck.app.data.secure.LegacyCredentialCleaner
import com.agentdeck.app.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentDeckApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LegacyCredentialCleaner.clear(this)
        ServiceLocator.init(this)
        appScope.launch {
            ServiceLocator.seeder.ensureInitialData()
        }
    }
}
