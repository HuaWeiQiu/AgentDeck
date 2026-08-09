package com.agentdeck.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentDeckRootTest {
    @Test
    fun `setup is the start destination until environment was completed`() {
        assertEquals("setup", resolveStartDestination(false, false))
        assertEquals("setup", resolveStartDestination(true, false))
        assertEquals("sessions", resolveStartDestination(true, true))
    }

    @Test
    fun `standard mode exposes only conversations and settings`() {
        assertEquals(setOf("sessions", "settings"), standardTopLevelRoutes)
    }
}
