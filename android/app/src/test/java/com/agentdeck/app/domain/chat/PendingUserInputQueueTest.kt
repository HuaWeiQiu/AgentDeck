package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUserInputQueueTest {
    @Test
    fun `queue preserves order and enforces capacity`() {
        val queue = PendingUserInputQueue(capacity = 2)
        val first = request("first")
        val second = request("second")

        assertTrue(queue.offer(first))
        assertTrue(queue.offer(second))
        assertFalse(queue.offer(request("overflow")))
        assertEquals(first, queue.poll())
        assertEquals(second, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `resolved request is removed without disturbing order`() {
        val queue = PendingUserInputQueue()
        val first = request("first")
        val second = request("second")
        queue.offer(first)
        queue.offer(second)

        queue.remove(first.requestId)

        assertEquals(second, queue.poll())
    }

    private fun request(id: String) = ChatUserInputRequest(
        requestId = RpcRequestId.from(id),
        itemId = "item-$id",
        questions = emptyList(),
    )
}
