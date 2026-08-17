package com.agentdeck.app.data.runtime

import android.util.Log
import com.agentdeck.app.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global warm-state for heavy agents (Codex bridge / dsh Web / pi RPC).
 *
 * Product policy (user-facing handfeel):
 * - **First open in this App process may be slow** (cold start under PRoot).
 * - **While the App stays alive**, agents stay **warm** so re-entering chat is fast.
 * - **Do not kill** just because the user left a chat screen or switched agents.
 * - **Reclaim only under system memory pressure** (or explicit uninstall/delete).
 *
 * Codex hold/reattach remains in [com.agentdeck.app.data.chat.ChatSessionRegistry];
 * this object tracks foreground + warm flags and centralizes reclaim.
 */
internal object NativeRuntimeBudget {
    private const val TAG = "NativeRuntimeBudget"

    enum class AgentKind { CODEX, PI, DSH }

    data class Snapshot(
        val foreground: AgentKind? = null,
        val piWarm: Boolean = false,
        val dshWarm: Boolean = false,
        val codexWarm: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    fun onPiForeground() {
        scope.launch {
            mutex.withLock {
                state.value = state.value.copy(foreground = AgentKind.PI, piWarm = true)
            }
            Log.i(TAG, "pi foreground (warm keep-alive)")
        }
    }

    /** Left pi chat UI — process stays warm for instant re-entry. */
    fun onPiBackground() {
        scope.launch {
            mutex.withLock {
                if (state.value.foreground == AgentKind.PI) {
                    state.value = state.value.copy(foreground = null)
                }
            }
            Log.i(TAG, "pi background — kept warm")
        }
    }

    fun onCodexForeground() {
        scope.launch {
            mutex.withLock {
                state.value = state.value.copy(foreground = AgentKind.CODEX, codexWarm = true)
            }
            Log.i(TAG, "codex foreground (warm keep-alive)")
        }
    }

    fun onCodexBackground() {
        scope.launch {
            mutex.withLock {
                if (state.value.foreground == AgentKind.CODEX) {
                    state.value = state.value.copy(foreground = null)
                }
            }
        }
    }

    fun onDshOpen() {
        scope.launch {
            mutex.withLock {
                state.value = state.value.copy(foreground = AgentKind.DSH, dshWarm = true)
            }
            Log.i(TAG, "dsh open (warm keep-alive)")
        }
    }

    fun onDshClosed() {
        scope.launch {
            mutex.withLock {
                if (state.value.foreground == AgentKind.DSH) {
                    state.value = state.value.copy(foreground = null)
                }
                // WebView is destroyed on leave; Node may still run — mark cool if stopped.
            }
        }
    }

    /** @deprecated Prefer [onCodexForeground]; kept so call sites compile during transition. */
    fun onCodexOpen() = onCodexForeground()

    /**
     * System memory pressure / process death path — drop warm agents.
     * Not used for ordinary navigation.
     */
    fun reclaimForMemoryPressure(aggressive: Boolean) {
        scope.launch {
            mutex.withLock {
                state.value = Snapshot()
            }
            runCatching { ServiceLocator.piRpcSession.stop() }
            runCatching { ServiceLocator.runtimeInventory.dshSupervisor().stop() }
            // Codex idle bridges released by ChatSessionRegistry.releaseAllIdleSessions().
            Log.w(TAG, "reclaimForMemoryPressure aggressive=$aggressive")
        }
    }

    /** Immediate pi stop (uninstall / explicit settings action only). */
    fun stopPiNow() {
        scope.launch {
            mutex.withLock {
                state.value = state.value.copy(piWarm = false, foreground = null)
            }
            runCatching { ServiceLocator.piRpcSession.stop() }
        }
    }
}
