package com.agentdeck.app.ui.chat

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out for Application.onTrimMemory into per-chat caches without keeping a
 * static reference to any ViewModel. Listeners must stay cheap and main-safe.
 */
internal object ChatMemoryTrim {
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()

    fun register(listener: (Int) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: (Int) -> Unit) {
        listeners.remove(listener)
    }

    fun dispatch(level: Int) {
        listeners.forEach { listener -> runCatching { listener(level) } }
    }
}
