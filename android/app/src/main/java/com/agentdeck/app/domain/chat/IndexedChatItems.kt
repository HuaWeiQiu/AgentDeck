package com.agentdeck.app.domain.chat

/**
 * Ordered chat items with an id-to-slot index.
 *
 * Live upserts, patch merges and streaming commits locate items by id instead
 * of scanning the whole list. The snapshot published to UI remains an immutable
 * [List]; this structure is the in-memory working set only.
 */
class IndexedChatItems(
    initial: List<ChatItem> = emptyList(),
) {
    private val items = ArrayList<ChatItem>(initial.size)
    private val slots = HashMap<String, Int>(initial.size * 2)
    private val optimisticUserSlots = ArrayList<Int>(2)

    init {
        initial.forEach(::appendNew)
    }

    fun toList(): List<ChatItem> = items.toList()

    fun size(): Int = items.size

    fun get(id: String): ChatItem? = slots[id]?.let(items::get)

    fun contains(id: String): Boolean = id in slots

    fun replaceAll(next: List<ChatItem>) {
        items.clear()
        slots.clear()
        optimisticUserSlots.clear()
        items.ensureCapacity(next.size)
        next.forEach(::appendNew)
    }

    fun prependMissing(older: List<ChatItem>) {
        if (older.isEmpty()) return
        val incoming = older.filterNot { it.id in slots }
        if (incoming.isEmpty()) return
        items.addAll(0, incoming)
        rebuildSlots()
    }

    data class UpsertOutcome(
        val item: ChatItem,
        val replacedOptimisticId: String? = null,
        val isNew: Boolean = false,
    )

    fun upsert(incoming: ChatItem): UpsertOutcome {
        val existingIndex = slots[incoming.id]
        if (existingIndex != null) {
            val merged = mergeExisting(items[existingIndex], incoming)
            items[existingIndex] = merged
            refreshOptimisticSlot(existingIndex, merged)
            return UpsertOutcome(merged)
        }
        if (incoming.kind == ChatItemKind.USER) {
            val optimisticIndex = findOptimisticUser(incoming.text)
            if (optimisticIndex >= 0) {
                val previous = items[optimisticIndex]
                slots.remove(previous.id)
                items[optimisticIndex] = incoming
                slots[incoming.id] = optimisticIndex
                refreshOptimisticSlot(optimisticIndex, incoming)
                return UpsertOutcome(incoming, replacedOptimisticId = previous.id)
            }
        }
        appendNew(incoming)
        return UpsertOutcome(incoming, isNew = true)
    }

    fun appendAgentDelta(itemId: String, delta: String): ChatItem {
        val index = slots[itemId]
        if (index == null) {
            val created = ChatItem(itemId, ChatItemKind.ASSISTANT, delta)
            appendNew(created)
            return created
        }
        val item = items[index]
        val updated = item.copy(kind = ChatItemKind.ASSISTANT, text = item.text + delta)
        items[index] = updated
        return updated
    }

    fun ensureAssistant(itemId: String, turnId: String?): ChatItem {
        val existing = get(itemId)
        if (existing != null) return existing
        val created = ChatItem(itemId, ChatItemKind.ASSISTANT, "", turnId = turnId)
        appendNew(created)
        return created
    }

    fun mergePatches(itemId: String, patches: List<FilePatch>): ChatItem? {
        val index = slots[itemId] ?: return null
        val item = items[index]
        val existingPaths = item.patches.mapTo(HashSet(item.patches.size)) { it.path }
        val merged = item.patches + patches.filterNot { it.path in existingPaths }
        val updated = item.copy(patches = merged)
        items[index] = updated
        return updated
    }

    fun update(id: String, transform: (ChatItem) -> ChatItem): ChatItem? {
        val index = slots[id] ?: return null
        val updated = transform(items[index])
        if (updated.id != id) {
            slots.remove(id)
            slots[updated.id] = index
        }
        items[index] = updated
        refreshOptimisticSlot(index, updated)
        return updated
    }

    fun remove(id: String): Boolean {
        val index = slots.remove(id) ?: return false
        items.removeAt(index)
        rebuildSlots()
        return true
    }

    private fun appendNew(item: ChatItem) {
        slots[item.id] = items.size
        if (item.id.startsWith(OPTIMISTIC_USER_PREFIX) && item.kind == ChatItemKind.USER) {
            optimisticUserSlots.add(items.size)
        }
        items.add(item)
    }

    private fun findOptimisticUser(text: String): Int {
        val iterator = optimisticUserSlots.iterator()
        while (iterator.hasNext()) {
            val index = iterator.next()
            if (index !in items.indices) {
                iterator.remove()
                continue
            }
            val item = items[index]
            if (item.id.startsWith(OPTIMISTIC_USER_PREFIX) &&
                item.kind == ChatItemKind.USER &&
                item.text == text
            ) {
                return index
            }
        }
        return -1
    }

    private fun refreshOptimisticSlot(index: Int, item: ChatItem) {
        val isOptimistic = item.id.startsWith(OPTIMISTIC_USER_PREFIX) && item.kind == ChatItemKind.USER
        if (isOptimistic) {
            if (index !in optimisticUserSlots) optimisticUserSlots.add(index)
        } else {
            optimisticUserSlots.remove(index)
        }
    }

    private fun rebuildSlots() {
        slots.clear()
        optimisticUserSlots.clear()
        items.forEachIndexed { index, item ->
            slots[item.id] = index
            if (item.id.startsWith(OPTIMISTIC_USER_PREFIX) && item.kind == ChatItemKind.USER) {
                optimisticUserSlots.add(index)
            }
        }
    }

    companion object {
        const val OPTIMISTIC_USER_PREFIX = "local-user-"

        fun mergeExisting(existing: ChatItem, incoming: ChatItem): ChatItem =
            if (incoming.patches.isEmpty() && existing.patches.isNotEmpty()) {
                incoming.copy(patches = existing.patches)
            } else {
                incoming
            }
    }
}
