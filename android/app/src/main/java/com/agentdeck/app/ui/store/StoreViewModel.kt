package com.agentdeck.app.ui.store

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.RecipeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InstallStatus {
    IDLE,
    INSTALLING,
    SUCCEEDED,
    FAILED,
}

data class RecipeInstallUiState(
    val status: InstallStatus = InstallStatus.IDLE,
    val message: String = "",
)

data class StoreUiState(
    val recipes: List<RecipeSummary>,
    val installs: Map<String, RecipeInstallUiState> = emptyMap(),
    val activeRecipeId: String? = null,
)

class StoreViewModel : ViewModel() {
    private val installer = ServiceLocator.installer
    private val mutableState = MutableStateFlow(
        StoreUiState(recipes = ServiceLocator.recipes.loadRecipes().map { it.summary }),
    )

    val state: StateFlow<StoreUiState> = mutableState.asStateFlow()

    fun install(recipeId: String) {
        if (mutableState.value.activeRecipeId != null) return
        if (mutableState.value.recipes.firstOrNull { it.id == recipeId }?.available != true) return
        mutableState.update { current ->
            current.copy(
                activeRecipeId = recipeId,
                installs = current.installs + (
                    recipeId to RecipeInstallUiState(
                        status = InstallStatus.INSTALLING,
                        message = "正在通过 Termux 安装",
                    )
                ),
            )
        }
        viewModelScope.launch {
            val result = try {
                installer.install(recipeId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            mutableState.update { current ->
                val next = result.fold(
                    onSuccess = { message ->
                        RecipeInstallUiState(InstallStatus.SUCCEEDED, message)
                    },
                    onFailure = { error ->
                        RecipeInstallUiState(
                            InstallStatus.FAILED,
                            error.message?.trim()?.take(MAX_MESSAGE_LENGTH) ?: "安装失败",
                        )
                    },
                )
                current.copy(
                    activeRecipeId = null,
                    installs = current.installs + (recipeId to next),
                )
            }
        }
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 240
    }
}
