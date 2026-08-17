package com.agentdeck.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.agentdeck.app.data.chat.ChatSessionRegistry
import com.agentdeck.app.data.secure.LegacyCredentialCleaner
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.ui.chat.ChatMemoryTrim
import com.agentdeck.app.ui.chat.ChatTranscriptPreviewCache
import com.agentdeck.app.ui.chat.SharedChatMarkdown
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Off-screen ASTs first; current visible documents stay intact.
        ChatMemoryTrim.dispatch(level)
        // Warm keep-alive: do NOT kill agents on UI_HIDDEN / ordinary background.
        // Only reclaim when the system is under real memory pressure.
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
            level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (pressure) {
            ChatSessionRegistry.releaseAllIdleSessions()
            com.agentdeck.app.data.runtime.NativeRuntimeBudget.reclaimForMemoryPressure(
                aggressive = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            )
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            ChatTranscriptPreviewCache.clear()
            SharedChatMarkdown.clear()
            // Keep disk previews — they are the force-stop recovery path.
            runCatching { System.gc() }
        }
    }

    companion object {
        private const val TAG = "AgentDeckApp"
    }
}
