package com.agentdeck.app.ui.chat

import androidx.compose.runtime.Immutable
import com.mikepenz.markdown.model.Input
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.intellij.markdown.MarkdownTokenTypes.Companion.EOL
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

@Immutable
internal data class ChatMarkdownBlock(
    val key: String,
    val node: ASTNode,
    val contentType: String,
)

@Immutable
internal data class ChatMarkdownDocument(
    val messageId: String,
    val content: String,
    val referenceLinkHandler: ReferenceLinkHandler,
    val blocks: List<ChatMarkdownBlock>,
    val estimatedBytes: Int,
)

internal class ChatMarkdownParser {
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)
    private val parseMutex = Mutex()

    suspend fun parse(messageId: String, content: String): ChatMarkdownDocument = parseMutex.withLock {
        val markdownState = MarkdownState(
            Input(
                content = content,
                lookupLinks = true,
                flavour = flavour,
                parser = parser,
                referenceLinkHandler = ReferenceLinkHandlerImpl(),
            ),
        )
        val state = checkNotNull(markdownState.parse() as? State.Success) {
            "Markdown parsing failed"
        }
        val blocks = state.node.children.filterNot { it.type == EOL }.mapIndexed { index, node ->
            ChatMarkdownBlock(
                key = "$messageId:markdown:${node.startOffset}:$index",
                node = node,
                contentType = "markdown:${node.type}",
            )
        }
        ChatMarkdownDocument(
            messageId = messageId,
            content = content,
            referenceLinkHandler = state.referenceLinkHandler,
            blocks = blocks,
            estimatedBytes = estimateDocumentBytes(content, state.node),
        )
    }
}

internal class ChatMarkdownCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxEntries > 0)
        require(maxBytes > 0)
    }

    private val documents = LinkedHashMap<String, ChatMarkdownDocument>(16, 0.75f, true)
    private var totalBytes = 0

    @Synchronized
    fun get(messageId: String, content: String): ChatMarkdownDocument? {
        val document = documents[messageId] ?: return null
        if (document.content == content) return document
        documents.remove(messageId)
        totalBytes -= document.estimatedBytes
        return null
    }

    @Synchronized
    fun put(document: ChatMarkdownDocument): Map<String, ChatMarkdownDocument> {
        documents.remove(document.messageId)?.let { totalBytes -= it.estimatedBytes }
        documents[document.messageId] = document
        totalBytes += document.estimatedBytes
        trim()
        return snapshot()
    }

    @Synchronized
    fun retain(messageIds: Set<String>): Map<String, ChatMarkdownDocument> {
        val iterator = documents.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in messageIds) {
                totalBytes -= entry.value.estimatedBytes
                iterator.remove()
            }
        }
        return snapshot()
    }

    @Synchronized
    fun touch(messageId: String) {
        documents[messageId]
    }

    @Synchronized
    fun snapshot(): Map<String, ChatMarkdownDocument> = LinkedHashMap(documents)

    @Synchronized
    internal fun sizeBytes(): Int = totalBytes

    private fun trim() {
        val iterator = documents.entries.iterator()
        while (
            (documents.size > maxEntries || (totalBytes > maxBytes && documents.size > 1)) &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            totalBytes -= entry.value.estimatedBytes
            iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 24
        const val DEFAULT_MAX_BYTES = 12 * 1024 * 1024
    }
}

private fun estimateDocumentBytes(content: String, root: ASTNode): Int {
    var nodeCount = 0
    val pending = ArrayDeque<ASTNode>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        nodeCount += 1
        node.children.forEach(pending::add)
    }
    return (content.length.toLong() * 2L + nodeCount.toLong() * ESTIMATED_AST_NODE_BYTES)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private const val ESTIMATED_AST_NODE_BYTES = 96L
