package com.agentdeck.app.data.runtime

import android.util.Log
import com.agentdeck.app.data.chat.ChatSessionRegistry
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.launch.CliAdapterRegistry
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * List-row press prefetch: warm at most one upcoming agent so open feels instant.
 * Cancelled when the user lifts before [PRESS_HOLD_MS] or presses another row.
 */
internal object SessionAgentPrefetch {
    private const val TAG = "SessionAgentPrefetch"
    private const val PRESS_HOLD_MS = 180L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pendingJob: Job? = null
    private var activeCardId: String? = null

    fun onCardPress(card: AgentCard) {
        val job = scope.launch {
            delay(PRESS_HOLD_MS)
            mutex.withLock {
                if (activeCardId != null && activeCardId != card.id) {
                    // Newer press superseded us.
                }
                activeCardId = card.id
            }
            runCatching { warm(card) }
                .onFailure { Log.w(TAG, "prefetch ${card.id}: ${it.message}") }
        }
        scope.launch {
            mutex.withLock {
                pendingJob?.cancel()
                pendingJob = job
                activeCardId = card.id
            }
        }
    }

    fun onCardCancel(cardId: String) {
        scope.launch {
            mutex.withLock {
                if (activeCardId == cardId) {
                    pendingJob?.cancel()
                    pendingJob = null
                    activeCardId = null
                }
            }
        }
    }

    fun onCardOpened(cardId: String) {
        // Open path owns lifecycle; drop pending cancel bookkeeping.
        scope.launch {
            mutex.withLock {
                if (activeCardId == cardId) {
                    pendingJob = null
                }
            }
        }
    }

    private suspend fun warm(card: AgentCard) {
        if (CliAdapterRegistry.usesLightChat(card.recipeId)) return
        when {
            CliAdapterRegistry.usesPiNativeChat(card.recipeId) -> warmPi(card)
            card.recipeId == "recipe_codex" -> warmCodex(card)
            else -> Unit
        }
    }

    private suspend fun warmPi(card: AgentCard) {
        val profileId = card.profileId ?: return
        val profile = ServiceLocator.profiles.getProfile(profileId) ?: return
        if (!profile.adapterId.isChatCompletionsCompatible()) return
        if (!PiRuntimePaths.shared(ServiceLocator.appContext).isReady()) return
        val modelId = card.modelId?.takeIf { it.isNotBlank() } ?: profile.defaultModel
        Log.i(TAG, "prefetch pi card=${card.id} model=$modelId")
        ServiceLocator.piRpcSession.ensureStarted(profile, modelId)
            .onSuccess { Log.i(TAG, "prefetch pi ready") }
            .onFailure { Log.w(TAG, "prefetch pi failed: ${it.message}") }
    }

    private fun warmCodex(card: AgentCard) {
        // If already held, reattach path will be fast; nothing to launch here
        // (launching a full bridge without UI ownership races ChatViewModel).
        if (ChatSessionRegistry.isHeld(card.id)) {
            Log.i(TAG, "prefetch codex already held card=${card.id}")
            NativeRuntimeBudget.onCodexForeground()
        } else {
            Log.i(TAG, "prefetch codex skip cold launch card=${card.id}")
        }
    }
}
