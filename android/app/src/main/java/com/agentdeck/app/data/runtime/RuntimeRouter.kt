package com.agentdeck.app.data.runtime

import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeCommandResult
import com.agentdeck.app.domain.runtime.RuntimeKind
import com.agentdeck.app.domain.runtime.RuntimeProgram
import com.agentdeck.app.domain.runtime.RuntimeSelection
import com.agentdeck.app.domain.runtime.RuntimeStatus
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class RuntimeRouter(
    private val selection: StateFlow<RuntimeSelection>,
    private val embedded: AgentRuntime,
    private val termux: AgentRuntime,
) : AgentRuntime {
    private val appServerOwners = ConcurrentHashMap<String, AgentRuntime>()

    override val kind: RuntimeKind
        get() = selected().kind

    override fun status(): RuntimeStatus = selected().status()

    override fun openConsole(): Boolean = selected().openConsole()

    override fun openInstallPage(): Boolean = selected().openInstallPage()

    override fun openAppSettings(): Boolean = selected().openAppSettings()

    override fun runCommand(command: RuntimeCommand): Result<Unit> {
        val owner = command.appServerInstanceKey()?.let(appServerOwners::remove) ?: selected()
        return owner.runCommand(command)
    }

    override suspend fun runCommandForResult(
        command: RuntimeCommand,
        timeoutMillis: Long,
    ): Result<RuntimeCommandResult> {
        val owner = selected()
        val result = owner.runCommandForResult(command, timeoutMillis)
        if (result.isSuccess && command.program == RuntimeProgram.CODEX_APP_SERVER) {
            command.appServerInstanceKey()?.let { appServerOwners[it] = owner }
        }
        return result
    }

    override fun stop(instanceId: String): Result<Unit> =
        (appServerOwners.remove(instanceId) ?: selected()).stop(instanceId)

    private fun selected(): AgentRuntime = when (selection.value) {
        RuntimeSelection.EMBEDDED -> embedded
        RuntimeSelection.TERMUX_COMPATIBILITY -> termux
    }

    private fun RuntimeCommand.appServerInstanceKey(): String? {
        if (program != RuntimeProgram.CODEX_APP_SERVER) return null
        val index = args.indexOf("--instance-key")
        return args.getOrNull(index + 1)?.takeIf { it.matches(Regex("[a-f0-9]{1,16}")) }
    }
}
