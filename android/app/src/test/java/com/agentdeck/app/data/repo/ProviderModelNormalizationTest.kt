package com.agentdeck.app.data.repo

import com.agentdeck.app.domain.model.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelNormalizationTest {
    @Test
    fun `new provider models are rebound to persisted provider id`() {
        val models = listOf(ProviderModel("preview", "model-a", "Model A", 123L))

        val normalized = normalizeProviderModels("prof_real", models)

        assertEquals("prof_real", normalized.single().providerId)
        assertEquals("model-a", normalized.single().id)
    }
}
