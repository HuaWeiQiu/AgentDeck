package com.agentdeck.app.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.data.config.CodexProfileRepository
import com.agentdeck.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CodexConfigUiState(
    val content: String = "",
    val savedContent: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val hasUnsavedChanges: Boolean
        get() = !isLoading && content != savedContent
}

class CodexConfigViewModel : ViewModel() {
    private val repository = ServiceLocator.codexProfile
    private val mutableState = MutableStateFlow(CodexConfigUiState())
    val state: StateFlow<CodexConfigUiState> = mutableState.asStateFlow()
    init {
        load()
    }

    fun updateContent(content: String) {
        mutableState.update { it.copy(content = content, error = null, message = null) }
    }

    fun save() {
        val content = state.value.content
        if (state.value.isSaving || !state.value.hasUnsavedChanges) return
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, error = null, message = null) }
            repository.save(content).fold(
                onSuccess = { snapshot ->
                    mutableState.update { current ->
                        current.copy(
                            content = if (current.content == content) snapshot.content else current.content,
                            savedContent = snapshot.content,
                            isSaving = false,
                            error = null,
                            message = "Codex 配置已保存",
                        )
                    }
                },
                onFailure = { error ->
                    mutableState.update {
                        it.copy(
                            isSaving = false,
                            error = error.message ?: "无法保存 Codex 配置",
                        )
                    }
                },
            )
        }
    }

    fun restoreDefaultDraft() {
        updateContent(CodexProfileRepository.DEFAULT_CONTENT)
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun load() {
        viewModelScope.launch {
            repository.load().fold(
                onSuccess = { snapshot ->
                    mutableState.value = CodexConfigUiState(
                        content = snapshot.content,
                        savedContent = snapshot.content,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "无法读取 Codex 配置",
                        )
                    }
                },
            )
        }
    }
}
