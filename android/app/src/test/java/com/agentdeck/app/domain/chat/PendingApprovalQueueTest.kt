package com.agentdeck.app.domain.chat

import com.agentdeck.app.data.chat.RpcRequestId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingApprovalQueueTest {
    @Test
    fun `queue is bounded ordered and removable by request id`() {
        val queue = PendingApprovalQueue(capacity = 2)
        val first = approval("first")
        val second = approval("second")

        assertTrue(queue.offer(first))
        assertTrue(queue.offer(second))
        assertFalse(queue.offer(approval("overflow")))
        queue.remove(RpcRequestId.Text("first"))

        assertEquals(second, queue.poll())
        assertNull(queue.poll())
    }

    @Test
    fun `restore preserves only the bounded prefix`() {
        val queue = PendingApprovalQueue(capacity = 2)

        queue.restore(listOf(approval("one"), approval("two"), approval("three")))

        assertEquals(listOf("one", "two"), queue.snapshot().map { (it.requestId as RpcRequestId.Text).value })
    }

    private fun approval(id: String) = ChatApproval(
        requestId = RpcRequestId.Text(id),
        kind = ApprovalKind.MCP_TOOL,
        title = id,
        detail = id,
    )
}
