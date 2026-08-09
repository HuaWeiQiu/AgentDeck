package com.agentdeck.app.domain.env

import com.agentdeck.app.domain.model.EnvironmentReport
import com.agentdeck.app.domain.runtime.RuntimeSelection
import kotlinx.coroutines.flow.StateFlow

class RoutingEnvironmentScanner(
    private val selection: StateFlow<RuntimeSelection>,
    private val embedded: EnvironmentScanner,
    private val termux: EnvironmentScanner,
) : EnvironmentScanner {
    override fun initialReport(): EnvironmentReport = selected().initialReport()
    override suspend fun scan(): EnvironmentReport = selected().scan()
    override fun allowExternalAppsFixCommand(): String = selected().allowExternalAppsFixCommand()
    override fun errorReport(message: String): EnvironmentReport = selected().errorReport(message)

    private fun selected(): EnvironmentScanner = when (selection.value) {
        RuntimeSelection.EMBEDDED -> embedded
        RuntimeSelection.TERMUX_COMPATIBILITY -> termux
    }
}
