package com.agentdeck.app

import android.app.Application
import android.util.Log
import com.agentdeck.app.data.chat.ChatSessionRegistry
import com.agentdeck.app.data.secure.LegacyCredentialCleaner
import com.agentdeck.app.di.ServiceLocator
import kotlinx.coroutines.CancellationException
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
        ChatSessionRegistry.init(this)
        // 不在主线程做重初始化：后台预热对象图，首次环境扫描由首屏 ON_RESUME 触发。
        appScope.launch {
            try {
                ServiceLocator.warmUp()
                ServiceLocator.seeder.ensureInitialData()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Initial data setup failed", error)
            }
        }
    }

    companion object {
        private const val TAG = "AgentDeckApp"
    }
}
