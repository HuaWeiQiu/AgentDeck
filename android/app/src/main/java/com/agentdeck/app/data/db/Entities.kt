package com.agentdeck.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType

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
    val createdAtEpochMs: Long,
) {
    fun toDomain(): ProviderProfile = ProviderProfile(
        id = id,
        name = name,
        type = ProviderType.valueOf(type),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        createdAtEpochMs = createdAtEpochMs,
    )

    companion object {
        fun from(domain: ProviderProfile) = ProviderProfileEntity(
            id = domain.id,
            name = domain.name,
            type = domain.type.name,
            baseUrl = domain.baseUrl,
            defaultModel = domain.defaultModel,
            createdAtEpochMs = domain.createdAtEpochMs,
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
    val termuxSessionName: String,
    val workspaceNamespace: String,
    val workspacePath: String,
    val distro: String,
    val innerBin: String,
    val innerArgsCsv: String,
    val enabled: Boolean,
) {
    fun toDomain(): AgentCard = AgentCard(
        id = id,
        name = name,
        icon = icon,
        recipeId = recipeId,
        templateId = templateId,
        profileId = profileId,
        termuxSessionName = termuxSessionName,
        workspaceNamespace = PathNamespace.valueOf(workspaceNamespace),
        workspacePath = workspacePath,
        distro = distro,
        innerBin = innerBin,
        innerArgs = if (innerArgsCsv.isBlank()) emptyList() else innerArgsCsv.split('\u0001'),
        enabled = enabled,
    )

    companion object {
        fun from(domain: AgentCard) = AgentCardEntity(
            id = domain.id,
            name = domain.name,
            icon = domain.icon,
            recipeId = domain.recipeId,
            templateId = domain.templateId,
            profileId = domain.profileId,
            termuxSessionName = domain.termuxSessionName,
            workspaceNamespace = domain.workspaceNamespace.name,
            workspacePath = domain.workspacePath,
            distro = domain.distro,
            innerBin = domain.innerBin,
            innerArgsCsv = domain.innerArgs.joinToString("\u0001"),
            enabled = domain.enabled,
        )
    }
}
