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
    val version: String,
    val available: Boolean,
    val dependsOn: List<String> = emptyList(),
)

enum class RecipeRuntime {
    TERMUX,
}

data class RecipeCommand(
    val runtime: RecipeRuntime,
    val script: String,
)

data class AgentRecipe(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val description: String,
    val priority: String,
    val version: String,
    val available: Boolean,
    val dependsOn: List<String>,
    val timeoutMinutes: Int,
    val install: RecipeCommand?,
    val verify: RecipeCommand?,
    val wrapperAsset: String?,
    val additionalWrapperAssets: List<String> = emptyList(),
) {
    val summary: RecipeSummary
        get() = RecipeSummary(
            id = id,
            name = name,
            description = description,
            priority = priority,
            version = version,
            available = available,
            dependsOn = dependsOn,
        )
}

enum class EnvironmentCheckStatus {
    UNKNOWN,
    CHECKING,
    READY,
    ACTION_REQUIRED,
    BLOCKED,
    ERROR,
}

data class EnvironmentCheck(
    val id: String,
    val label: String,
    val status: EnvironmentCheckStatus,
    val detail: String,
) {
    val ok: Boolean
        get() = status == EnvironmentCheckStatus.READY
}

data class EnvironmentReport(
    val checks: List<EnvironmentCheck>,
) {
    val isTermuxReady: Boolean
        get() = checks.firstOrNull { it.id == "termux_installed" }?.ok == true

    fun check(id: String): EnvironmentCheck? = checks.firstOrNull { it.id == id }

    val canLaunchSessions: Boolean
        get() {
            val launchIds = setOf(
                "termux_installed",
                "termux_run_command_permission",
                "termux_background_execution",
                "allow_external_apps",
                "proot_distro",
                "ubuntu_installed",
                "codex_installed",
                "codex_wrapper",
            )
            return launchIds.all { id -> check(id)?.ok == true }
        }

    val allCriticalOk: Boolean
        get() {
            val criticalIds = setOf(
                "termux_installed",
                "termux_run_command_permission",
                "termux_background_execution",
                "allow_external_apps",
                "proot_distro",
                "ubuntu_installed",
                "codex_installed",
                "codex_authenticated",
                "codex_wrapper",
            )
            return criticalIds.all { id -> check(id)?.ok == true }
        }
}

sealed class LaunchResult {
    data object Success : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}
