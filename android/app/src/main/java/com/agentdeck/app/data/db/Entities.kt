package com.agentdeck.app.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.CodexPermissionLevel
import com.agentdeck.app.domain.model.ConversationIdentity
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.extensions.ExtensionAuthType
import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionLevel
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ExtensionTool
import com.agentdeck.app.domain.extensions.ExtensionToolAccess
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.domain.extensions.McpExtensionConfig
import com.agentdeck.app.domain.extensions.SkillExtensionConfig
import org.json.JSONArray

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "provider_profiles")
data class ProviderProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val defaultModel: String,
    @ColumnInfo(defaultValue = "'OPENAI_RESPONSES'") val adapterId: String,
    val credentialRef: String?,
    @ColumnInfo(defaultValue = "'UNVERIFIED'") val connectionStatus: String,
    val lastCheckedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val updatedAtEpochMs: Long,
) {
    fun toDomain(): ProviderProfile = ProviderProfile(
        id = id,
        name = name,
        type = ProviderType.valueOf(type),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        adapterId = ProviderAdapterId.valueOf(adapterId),
        credentialRef = credentialRef,
        connectionStatus = ProviderConnectionStatus.valueOf(connectionStatus),
        lastCheckedAtEpochMs = lastCheckedAtEpochMs,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        fun from(domain: ProviderProfile) = ProviderProfileEntity(
            id = domain.id,
            name = domain.name,
            type = domain.type.name,
            baseUrl = domain.baseUrl,
            defaultModel = domain.defaultModel,
            adapterId = domain.adapterId.name,
            credentialRef = domain.credentialRef,
            connectionStatus = domain.connectionStatus.name,
            lastCheckedAtEpochMs = domain.lastCheckedAtEpochMs,
            createdAtEpochMs = domain.createdAtEpochMs,
            updatedAtEpochMs = domain.updatedAtEpochMs,
        )
    }
}

@Entity(
    tableName = "provider_models",
    primaryKeys = ["providerId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("providerId")],
)
data class ProviderModelEntity(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val discoveredAtEpochMs: Long,
) {
    fun toDomain() = ProviderModel(
        providerId = providerId,
        id = modelId,
        displayName = displayName,
        discoveredAtEpochMs = discoveredAtEpochMs,
    )

    companion object {
        fun from(domain: ProviderModel) = ProviderModelEntity(
            providerId = domain.providerId,
            modelId = domain.id,
            displayName = domain.displayName,
            discoveredAtEpochMs = domain.discoveredAtEpochMs,
        )
    }
}

@Entity(
    tableName = "agent_cards",
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("profileId")],
)
data class AgentCardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val recipeId: String,
    val templateId: String,
    val profileId: String?,
    val modelId: String?,
    val permissionLevel: String?,
    val termuxSessionName: String,
    val workspaceNamespace: String,
    val workspacePath: String,
    val distro: String,
    val innerBin: String,
    val innerArgsCsv: String,
    val enabled: Boolean,
    val customTitle: String?,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean,
    @ColumnInfo(defaultValue = "0") val archived: Boolean,
    @ColumnInfo(defaultValue = "0") val lastActiveAtEpochMs: Long,
    val roleName: String?,
    val roleSelfDefinition: String?,
    val roleObjective: String?,
    val roleCommunicationStyle: String?,
    val roleBoundaries: String?,
) {
    fun toDomain(): AgentCard = AgentCard(
        id = id,
        name = name,
        icon = icon,
        recipeId = recipeId,
        templateId = templateId,
        profileId = profileId,
        modelId = modelId,
        permissionLevel = CodexPermissionLevel.overrideFromStorage(permissionLevel),
        termuxSessionName = termuxSessionName,
        workspaceNamespace = PathNamespace.valueOf(workspaceNamespace),
        workspacePath = workspacePath,
        distro = distro,
        innerBin = innerBin,
        innerArgs = if (innerArgsCsv.isBlank()) emptyList() else innerArgsCsv.split('\u0001'),
        enabled = enabled,
        customTitle = customTitle,
        pinned = pinned,
        archived = archived,
        lastActiveAtEpochMs = lastActiveAtEpochMs,
        identity = roleName?.let { name ->
            ConversationIdentity(
                roleName = name,
                selfDefinition = roleSelfDefinition.orEmpty(),
                objective = roleObjective.orEmpty(),
                communicationStyle = roleCommunicationStyle.orEmpty(),
                boundaries = roleBoundaries.orEmpty(),
            )
        },
    )

    companion object {
        fun from(domain: AgentCard) = AgentCardEntity(
            id = domain.id,
            name = domain.name,
            icon = domain.icon,
            recipeId = domain.recipeId,
            templateId = domain.templateId,
            profileId = domain.profileId,
            modelId = domain.modelId,
            permissionLevel = domain.permissionLevel?.name,
            termuxSessionName = domain.termuxSessionName,
            workspaceNamespace = domain.workspaceNamespace.name,
            workspacePath = domain.workspacePath,
            distro = domain.distro,
            innerBin = domain.innerBin,
            innerArgsCsv = domain.innerArgs.joinToString("\u0001"),
            enabled = domain.enabled,
            customTitle = domain.customTitle,
            pinned = domain.pinned,
            archived = domain.archived,
            lastActiveAtEpochMs = domain.lastActiveAtEpochMs,
            roleName = domain.identity?.roleName,
            roleSelfDefinition = domain.identity?.selfDefinition,
            roleObjective = domain.identity?.objective,
            roleCommunicationStyle = domain.identity?.communicationStyle,
            roleBoundaries = domain.identity?.boundaries,
        )
    }
}

@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val requiredLevel: Int,
    val enabled: Boolean,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "mcp_extension_configs",
    foreignKeys = [
        ForeignKey(
            entity = ExtensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("extensionId", unique = true)],
)
data class McpExtensionConfigEntity(
    @PrimaryKey val extensionId: String,
    val transport: String,
    val url: String?,
    val command: String?,
    val argsJson: String,
    val authType: String,
    val credentialRef: String?,
) {
    fun toDomain() = McpExtensionConfig(
        transport = transport,
        url = url,
        command = command,
        args = JSONArray(argsJson).let { values ->
            buildList { for (index in 0 until values.length()) add(values.getString(index)) }
        },
        authType = ExtensionAuthType.valueOf(authType),
        credentialRef = credentialRef,
    )
}

@Entity(
    tableName = "skill_extension_configs",
    foreignKeys = [
        ForeignKey(
            entity = ExtensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("extensionId", unique = true)],
)
data class SkillExtensionConfigEntity(
    @PrimaryKey val extensionId: String,
    val installedPath: String,
    val version: String?,
    val manifestHash: String,
) {
    fun toDomain() = SkillExtensionConfig(installedPath, version, manifestHash)
}

@Entity(
    tableName = "extension_tools",
    primaryKeys = ["extensionId", "toolName"],
    foreignKeys = [
        ForeignKey(
            entity = ExtensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("extensionId")],
)
data class ExtensionToolEntity(
    val extensionId: String,
    val toolName: String,
    val title: String,
    val description: String,
    val accessLevel: String,
    val enabled: Boolean,
    val discoveredAtEpochMs: Long,
) {
    fun toDomain() = ExtensionTool(
        extensionId = extensionId,
        name = toolName,
        title = title,
        description = description,
        access = ExtensionToolAccess.valueOf(accessLevel),
        enabled = enabled,
        discoveredAtEpochMs = discoveredAtEpochMs,
    )

    companion object {
        fun from(tool: ExtensionTool) = ExtensionToolEntity(
            extensionId = tool.extensionId,
            toolName = tool.name,
            title = tool.title,
            description = tool.description,
            accessLevel = tool.access.name,
            enabled = tool.enabled,
            discoveredAtEpochMs = tool.discoveredAtEpochMs,
        )
    }
}

@Entity(
    tableName = "agent_card_extensions",
    primaryKeys = ["cardId", "extensionId"],
    foreignKeys = [
        ForeignKey(
            entity = AgentCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExtensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cardId"), Index("extensionId")],
)
data class AgentCardExtensionEntity(
    val cardId: String,
    val extensionId: String,
)

internal fun ExtensionEntity.toDomain(
    mcp: McpExtensionConfigEntity?,
    skill: SkillExtensionConfigEntity?,
    tools: List<ExtensionToolEntity>,
) = ManagedExtension(
    id = id,
    name = name,
    description = description,
    kind = ExtensionKind.valueOf(kind),
    requiredLevel = ExtensionLevel.entries.first { it.value == requiredLevel },
    enabled = enabled,
    status = ExtensionStatus.valueOf(status),
    mcp = mcp?.toDomain(),
    skill = skill?.toDomain(),
    tools = tools.map(ExtensionToolEntity::toDomain),
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)
