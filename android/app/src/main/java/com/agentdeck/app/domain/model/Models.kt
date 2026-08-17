package com.agentdeck.app.domain.model

enum class ProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
}

enum class ProviderAdapterId {
    SUB2API,
    OPENAI_RESPONSES,
    /**
     * OpenAI Chat Completions (`POST /v1/chat/completions`).
     * For pi / dsh / native light chat — **not** Codex Responses.
     */
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC,
}

fun ProviderAdapterId.isCodexResponsesCompatible(): Boolean =
    this == ProviderAdapterId.SUB2API || this == ProviderAdapterId.OPENAI_RESPONSES

fun ProviderAdapterId.isChatCompletionsCompatible(): Boolean =
    this == ProviderAdapterId.OPENAI_CHAT_COMPLETIONS

enum class ProviderConnectionStatus {
    UNVERIFIED,
    READY,
    CREDENTIAL_REJECTED,
    FORBIDDEN,
    DISCOVERY_UNSUPPORTED,
    RATE_LIMITED,
    NETWORK_ERROR,
    TLS_ERROR,
    INVALID_RESPONSE,
    UNSUPPORTED,
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
    val adapterId: ProviderAdapterId = if (type == ProviderType.ANTHROPIC) {
        ProviderAdapterId.ANTHROPIC
    } else {
        ProviderAdapterId.OPENAI_RESPONSES
    },
    val credentialRef: String? = null,
    val connectionStatus: ProviderConnectionStatus = ProviderConnectionStatus.UNVERIFIED,
    val lastCheckedAtEpochMs: Long? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = createdAtEpochMs,
)

data class ProviderModel(
    val providerId: String,
    val id: String,
    val displayName: String = id,
    val discoveredAtEpochMs: Long,
)

data class ConversationIdentity(
    val roleName: String,
    val selfDefinition: String,
    val objective: String = "",
    val communicationStyle: String = "",
    val boundaries: String = "",
)

data class AgentCard(
    val id: String,
    val name: String,
    val icon: String,
    val recipeId: String,
    val templateId: String,
    val profileId: String?,
    val modelId: String? = null,
    val permissionLevel: CodexPermissionLevel? = null,
    val termuxSessionName: String,
    val workspaceNamespace: PathNamespace,
    val workspacePath: String,
    val distro: String = "ubuntu",
    val innerBin: String = "codex",
    val innerArgs: List<String> = emptyList(),
    val enabled: Boolean = true,
    /** 用户自定义标题；null 时 UI 回退到 [name] 派生的标题。 */
    val customTitle: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val lastActiveAtEpochMs: Long = 0L,
    val identity: ConversationIdentity? = null,
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
    fun check(id: String): EnvironmentCheck? = checks.firstOrNull { it.id == id }

    val canLaunchSessions: Boolean
        get() = setOf(
            "embedded_supported",
            "embedded_runtime",
            "ubuntu_installed",
            "embedded_tools",
            "codex_installed",
            "codex_wrapper",
        ).all { id -> check(id)?.ok == true }

    val allCriticalOk: Boolean
        get() = canLaunchSessions && check("codex_authenticated")?.ok == true
}

sealed class LaunchResult {
    data object Success : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}
