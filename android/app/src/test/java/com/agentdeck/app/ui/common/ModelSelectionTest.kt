package com.agentdeck.app.ui.common

import com.agentdeck.app.domain.model.ProviderModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionTest {
    private val models = listOf(
        ProviderModel(providerId = "p1", id = "gpt-4.1", discoveredAtEpochMs = 1),
        ProviderModel(providerId = "p1", id = "claude-sonnet", displayName = "Claude Sonnet", discoveredAtEpochMs = 1),
    )

    @Test
    fun `selected id in query still shows all models`() {
        val result = filterSelectableModels(
            models = models,
            query = "gpt-4.1",
            selectedId = "gpt-4.1",
        )

        assertEquals(models.map { it.id }, result.map { it.id })
    }

    @Test
    fun `blank query shows all models`() {
        val result = filterSelectableModels(
            models = models,
            query = "  ",
            selectedId = "gpt-4.1",
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `user search filters by id and display name`() {
        val byId = filterSelectableModels(
            models = models,
            query = "claude",
            selectedId = "gpt-4.1",
        )
        val byName = filterSelectableModels(
            models = models,
            query = "Sonnet",
            selectedId = "gpt-4.1",
        )

        assertEquals(listOf("claude-sonnet"), byId.map { it.id })
        assertEquals(listOf("claude-sonnet"), byName.map { it.id })
    }

    @Test
    fun `respects max visible cap`() {
        val many = (1..5).map {
            ProviderModel(providerId = "p1", id = "model-$it", discoveredAtEpochMs = 1)
        }
        val result = filterSelectableModels(
            models = many,
            query = "",
            selectedId = null,
            maxVisible = 3,
        )

        assertEquals(listOf("model-1", "model-2", "model-3"), result.map { it.id })
    }
}
