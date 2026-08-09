package com.agentdeck.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultSeedProfilesTest {
    @Test
    fun `fresh install does not seed synthetic provider profiles`() {
        val profiles = defaultSeedProfiles(createdAtEpochMs = 123L)
        assertEquals(emptyList<Any>(), profiles)
    }
}
