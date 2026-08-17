package com.agentdeck.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide Markdown parser + LRU cache shared by Codex and pi chat UIs.
 * First open pays parse cost; reopening reuses ASTs across both surfaces.
 */
internal object SharedChatMarkdown {
    private val lock = Mutex()
    private val parser = ChatMarkdownParser()
    private val parseDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Volatile
    private var cacheRef: ChatMarkdownCache? = null

    fun cache(): ChatMarkdownCache {
        cacheRef?.let { return it }
        return synchronized(this) {
            cacheRef ?: ChatMarkdownCache.forMemoryClass(markdownMemoryClassMb()).also {
                cacheRef = it
            }
        }
    }

    suspend fun parse(messageId: String, content: String): ChatMarkdownDocument {
        val cache = cache()
        cache.get(messageId, content)?.let { return it }
        return withContext(parseDispatcher) {
            lock.withLock {
                cache.get(messageId, content)?.let { return@withLock it }
                val document = parser.parse(messageId, content)
                cache.put(document)
                document
            }
        }
    }

    fun getCached(messageId: String, content: String): ChatMarkdownDocument? =
        cache().get(messageId, content)

    fun touch(messageId: String) = cache().touch(messageId)

    fun retain(messageIds: Set<String>) = cache().retain(messageIds)

    fun snapshot(): Map<String, ChatMarkdownDocument> = cache().snapshot()

    fun clear() {
        synchronized(this) {
            cacheRef = ChatMarkdownCache.forMemoryClass(markdownMemoryClassMb())
        }
    }
}
