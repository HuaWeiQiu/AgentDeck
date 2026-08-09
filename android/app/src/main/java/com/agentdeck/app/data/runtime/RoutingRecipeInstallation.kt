package com.agentdeck.app.data.runtime

import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.runtime.RuntimeSelection
import kotlinx.coroutines.flow.StateFlow

class RoutingRecipeInstallation(
    private val selection: StateFlow<RuntimeSelection>,
    private val embedded: RecipeInstallation,
    private val termux: RecipeInstallation,
) : RecipeInstallation {
    override suspend fun install(
        recipeId: String,
        onProgress: (RecipeInstallProgress) -> Unit,
    ): Result<String> = when (selection.value) {
        RuntimeSelection.EMBEDDED -> embedded.install(recipeId, onProgress)
        RuntimeSelection.TERMUX_COMPATIBILITY -> termux.install(recipeId, onProgress)
    }
}
