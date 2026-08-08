package com.agentdeck.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentDeckRootTest {
    @Test
    fun `setup is the start destination until environment was completed`() {
        assertEquals("store", resolveStartDestination(false, false, false))
        assertEquals("store", resolveStartDestination(true, false, false))
        assertEquals("store", resolveStartDestination(true, true, false))
        assertEquals("sessions", resolveStartDestination(true, true, true))
    }
}
