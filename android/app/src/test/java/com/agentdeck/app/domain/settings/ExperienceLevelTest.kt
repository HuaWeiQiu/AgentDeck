package com.agentdeck.app.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceLevelTest {
    @Test
    fun `unknown or missing preference defaults to standard`() {
        assertEquals(ExperienceLevel.STANDARD, ExperienceLevel.fromStorage(null))
        assertEquals(ExperienceLevel.STANDARD, ExperienceLevel.fromStorage("UNKNOWN"))
        assertFalse(ExperienceLevel.STANDARD.advancedEnabled)
    }

    @Test
    fun `advanced and developer levels expose advanced controls`() {
        assertEquals(ExperienceLevel.ADVANCED, ExperienceLevel.fromStorage("ADVANCED"))
        assertTrue(ExperienceLevel.ADVANCED.advancedEnabled)
        assertTrue(ExperienceLevel.DEVELOPER.advancedEnabled)
    }
}
