package com.agentdeck.app.domain.recipe

import com.agentdeck.app.domain.model.AgentRecipe
import com.agentdeck.app.domain.model.RecipeCommand
import com.agentdeck.app.domain.model.RecipeRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeDependencyResolverTest {
    @Test
    fun `dependencies are returned before target without duplicates`() {
        val recipes = listOf(
            recipe("base"),
            recipe("tools", listOf("base")),
            recipe("target", listOf("base", "tools")),
        )

        val ordered = RecipeDependencyResolver.resolve(recipes, "target").getOrThrow()

        assertEquals(listOf("base", "tools", "target"), ordered.map { it.id })
    }

    @Test
    fun `cycles and missing dependencies fail closed`() {
        val cycle = RecipeDependencyResolver.resolve(
            listOf(recipe("a", listOf("b")), recipe("b", listOf("a"))),
            "a",
        )
        val missing = RecipeDependencyResolver.resolve(listOf(recipe("a", listOf("missing"))), "a")

        assertTrue(cycle.isFailure)
        assertTrue(missing.isFailure)
    }

    private fun recipe(id: String, dependencies: List<String> = emptyList()) = AgentRecipe(
        schemaVersion = 1,
        id = id,
        name = id,
        description = id,
        priority = "p0",
        version = "1",
        available = true,
        dependsOn = dependencies,
        timeoutMinutes = 5,
        install = RecipeCommand(RecipeRuntime.TERMUX, "echo install-$id"),
        verify = RecipeCommand(RecipeRuntime.TERMUX, "echo verify-$id"),
        wrapperAsset = null,
    )
}
