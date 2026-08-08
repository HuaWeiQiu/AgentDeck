package com.agentdeck.app.domain.profiles

import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInputValidatorTest {
    @Test
    fun `valid http endpoints are accepted`() {
        assertTrue(
            ProfileInputValidator.validate(
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-5",
            ).isSuccess,
        )
    }

    @Test
    fun `credentials fragments and non http schemes are rejected`() {
        val values = listOf(
            "https://token@example.com/v1",
            "https://example.com/v1?api_key=secret",
            "https://example.com/v1#secret",
            "file:///tmp/provider",
            "not a url",
        )

        values.forEach { baseUrl ->
            assertTrue(
                ProfileInputValidator.validate("Profile", baseUrl, "model").isFailure,
            )
        }
    }

    @Test
    fun `blank model is rejected`() {
        assertTrue(
            ProfileInputValidator.validate("Profile", "https://example.com/v1", " ").isFailure,
        )
    }
}
