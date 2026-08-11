package com.agentdeck.app.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionRegistryPolicyTest {
    @Test
    fun `standalone resolved request becomes idle when no work remains`() {
        assertTrue(
            canBecomeIdle(
                activeTurnId = null,
                pendingRequestCount = 0,
                pendingApprovalCount = 0,
                hasPendingApproval = false,
                pendingUserInputCount = 0,
                hasPendingUserInput = false,
            ),
        )
    }

    @Test
    fun `active turn or another pending request stays busy`() {
        assertFalse(
            canBecomeIdle(
                activeTurnId = "turn-1",
                pendingRequestCount = 0,
                pendingApprovalCount = 0,
                hasPendingApproval = false,
                pendingUserInputCount = 0,
                hasPendingUserInput = false,
            ),
        )
        assertFalse(
            canBecomeIdle(
                activeTurnId = null,
                pendingRequestCount = ChatSessionRegistry.MAX_PENDING_SERVER_REQUESTS,
                pendingApprovalCount = 0,
                hasPendingApproval = false,
                pendingUserInputCount = 0,
                hasPendingUserInput = false,
            ),
        )
    }
}
