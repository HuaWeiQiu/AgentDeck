package com.agentdeck.app.domain.extensions

enum class ExtensionKind {
    SKILL,
    REMOTE_MCP,
    LOCAL_MCP,
}

enum class ExtensionLevel(val value: Int) {
    SKILL(0),
    REMOTE_READ(1),
    REMOTE_WRITE(2),
    LOCAL_PROCESS(3),
    HOST_CONTROL(4),
}

enum class ExtensionStatus {
    READY,
    UNVERIFIED,
    ERROR,
}

enum class ExtensionAuthType {
    NONE,
    BEARER,
}

enum class ExtensionToolAccess {
    READ,
    WRITE,
}

data class ExtensionTool(
    val extensionId: String,
    val name: String,
    val title: String = name,
    val description: String = "",
    val access: ExtensionToolAccess = ExtensionToolAccess.WRITE,
    val enabled: Boolean = true,
    val discoveredAtEpochMs: Long = System.currentTimeMillis(),
)

data class McpExtensionConfig(
    val transport: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val authType: ExtensionAuthType = ExtensionAuthType.NONE,
    val credentialRef: String? = null,
)

data class SkillExtensionConfig(
    val installedPath: String,
    val version: String? = null,
    val manifestHash: String,
)

data class ManagedExtension(
    val id: String,
    val name: String,
    val description: String,
    val kind: ExtensionKind,
    val requiredLevel: ExtensionLevel,
    val enabled: Boolean,
    val status: ExtensionStatus,
    val mcp: McpExtensionConfig? = null,
    val skill: SkillExtensionConfig? = null,
    val tools: List<ExtensionTool> = emptyList(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class ExtensionSessionPlan(
    val configOverlay: String = "{}",
    val skillSnapshotKey: String? = null,
    val enabledNames: List<String> = emptyList(),
)

class ExtensionSessionHandle(
    val plan: ExtensionSessionPlan,
    private val resources: List<AutoCloseable>,
) : AutoCloseable {
    override fun close() {
        resources.asReversed().forEach { resource -> runCatching(resource::close) }
    }

    companion object {
        val EMPTY = ExtensionSessionHandle(ExtensionSessionPlan(), emptyList())
    }
}
