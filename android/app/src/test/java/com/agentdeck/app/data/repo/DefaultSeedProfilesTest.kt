package com.agentdeck.app.data.repo

import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.profiles.ProfileInputValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSeedProfilesTest {
    @Test
    fun `fresh install profiles have stable ids and valid metadata`() {
        val profiles = defaultSeedProfiles(createdAtEpochMs = 123L)

        assertEquals(
            setOf("prof_openai_demo", "prof_anthropic_demo"),
            profiles.map { it.id }.toSet(),
        )
        assertEquals(
            setOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.ANTHROPIC),
            profiles.map { it.type }.toSet(),
        )
        assertTrue(profiles.all { it.createdAtEpochMs == 123L })
        profiles.forEach { profile ->
            assertTrue(
                ProfileInputValidator.validate(
                    profile.name,
                    profile.baseUrl,
                    profile.defaultModel,
                ).isSuccess,
            )
        }
    }
}
