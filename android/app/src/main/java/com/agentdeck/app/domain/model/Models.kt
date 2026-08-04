package com.agentdeck.app.domain.model

enum class ProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
}

enum class PathNamespace {
    UBUNTU,
    TERMUX,
}

enum class CardStatus {
    READY,
    NOT_READY,
    RUNNING,
    INSTALLING,
}

data class ProviderProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val defaultModel: String,
    val keyRef: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

data class AgentCard(
    val id: String,
    val name: String,
    val icon: String,
    val recipeId: String,
    val templateId: String,
    val profileId: String?,
    val termuxSessionName: String,
    val workspaceNamespace: PathNamespace,
    val workspacePath: String,
    val distro: String = "ubuntu",
    val innerBin: String = "codex",
    val innerArgs: List<String> = emptyList(),
    val enabled: Boolean = true,
)

data class RecipeSummary(
    val id: String,
    val name: String,
    val description: String,
    val priority: String,
    val dependsOn: List<String> = emptyList(),
)

data class EnvironmentCheck(
    val id: String,
    val label: String,
    val ok: Boolean,
    val detail: String,
)

data class EnvironmentReport(
    val checks: List<EnvironmentCheck>,
) {
    val isTermuxReady: Boolean
        get() = checks.firstOrNull { it.id == "termux_installed" }?.ok == true

    val allCriticalOk: Boolean
        get() = checks.filter {
            it.id in setOf(
                "termux_installed",
                "termux_run_command_permission",
            )
        }.all { it.ok }
}

data class LaunchRequest(
    val card: AgentCard,
    val profile: ProviderProfile?,
    val apiKey: String?,
)

sealed class LaunchResult {
    data object Success : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}
