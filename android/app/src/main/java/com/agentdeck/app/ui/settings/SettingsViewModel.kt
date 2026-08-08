package com.agentdeck.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdeck.app.di.ServiceLocator
import com.agentdeck.app.domain.model.EnvironmentReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val report: EnvironmentReport,
    val isScanning: Boolean = false,
)

class SettingsViewModel : ViewModel() {
    private val probe = ServiceLocator.envProbe
    private var scanJob: Job? = null
    private val mutableState = MutableStateFlow(
        SettingsUiState(report = probe.initialReport()),
    )

    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        mutableState.value = SettingsUiState(
            report = probe.checkingReport(),
            isScanning = true,
        )
        scanJob = viewModelScope.launch {
            val report = try {
                probe.scan()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                probe.errorReport(error.message ?: "环境检测意外失败")
            }
            ServiceLocator.onboarding.record(report)
            mutableState.update { SettingsUiState(report = report) }
        }
    }
}
