package com.agentdeck.app.data.repo

import android.content.Context
import androidx.core.content.edit
import com.agentdeck.app.domain.runtime.RuntimeSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RuntimeSettingsRepository(
    context: Context,
    initialSelection: RuntimeSelection,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableSelection = MutableStateFlow(readSelection() ?: initialSelection)

    val selection: StateFlow<RuntimeSelection> = mutableSelection.asStateFlow()

    init {
        if (readSelection() == null) persist(initialSelection)
    }

    fun setSelection(selection: RuntimeSelection) {
        persist(selection)
        mutableSelection.value = selection
    }

    private fun readSelection(): RuntimeSelection? = preferences.getString(KEY_SELECTION, null)
        ?.let { value -> RuntimeSelection.entries.firstOrNull { it.name == value } }

    private fun persist(selection: RuntimeSelection) {
        preferences.edit(commit = true) { putString(KEY_SELECTION, selection.name) }
    }

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_runtime"
        private const val KEY_SELECTION = "selection"
    }
}
