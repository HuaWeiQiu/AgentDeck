package com.agentdeck.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetadataDao {
    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Upsert
    suspend fun upsert(entity: AppMetadataEntity)
}

@Dao
interface ProviderProfileDao {
    @Query("SELECT * FROM provider_profiles ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ProviderProfileEntity>>

    @Query("SELECT * FROM provider_profiles ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<ProviderProfileEntity>

    @Query("SELECT * FROM provider_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProviderProfileEntity?

    @Query("SELECT * FROM provider_profiles WHERE baseUrl = :baseUrl LIMIT 1")
    suspend fun getByBaseUrl(baseUrl: String): ProviderProfileEntity?

    @Upsert
    suspend fun upsert(entity: ProviderProfileEntity)

    @Query("DELETE FROM provider_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM provider_profiles")
    suspend fun count(): Int
}

@Dao
interface ProviderModelDao {
    @Query("SELECT * FROM provider_models ORDER BY providerId ASC, modelId ASC")
    fun observeAll(): Flow<List<ProviderModelEntity>>

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY modelId ASC")
    fun observeByProvider(providerId: String): Flow<List<ProviderModelEntity>>

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId ORDER BY modelId ASC")
    suspend fun getByProvider(providerId: String): List<ProviderModelEntity>

    @Upsert
    suspend fun upsertAll(models: List<ProviderModelEntity>)

    @Query("DELETE FROM provider_models WHERE providerId = :providerId")
    suspend fun deleteByProvider(providerId: String)
}

@Dao
interface AgentCardDao {
    @Query("SELECT * FROM agent_cards ORDER BY name ASC")
    fun observeAll(): Flow<List<AgentCardEntity>>

    @Query("SELECT * FROM agent_cards WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentCardEntity?

    @Upsert
    suspend fun upsert(entity: AgentCardEntity)

    @Query("UPDATE agent_cards SET customTitle = :title WHERE id = :id")
    suspend fun updateCustomTitle(id: String, title: String?)

    @Query("UPDATE agent_cards SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("UPDATE agent_cards SET archived = :archived WHERE id = :id")
    suspend fun updateArchived(id: String, archived: Boolean)

    @Query(
        "UPDATE agent_cards SET lastActiveAtEpochMs = :timestamp " +
            "WHERE id = :id AND lastActiveAtEpochMs < :timestamp",
    )
    suspend fun touchActivity(id: String, timestamp: Long)

    @Query("DELETE FROM agent_cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM agent_cards")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM agent_cards WHERE profileId = :profileId")
    suspend fun countByProfileId(profileId: String): Int
}

@Dao
interface ExtensionDao {
    @Query("SELECT * FROM extensions ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<ExtensionEntity>

    @Query("SELECT * FROM extensions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExtensionEntity?

    @Upsert
    suspend fun upsert(extension: ExtensionEntity)

    @Query("UPDATE extensions SET enabled = :enabled, updatedAtEpochMs = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM extensions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface McpExtensionConfigDao {
    @Query("SELECT * FROM mcp_extension_configs")
    fun observeAll(): Flow<List<McpExtensionConfigEntity>>

    @Query("SELECT * FROM mcp_extension_configs")
    suspend fun getAll(): List<McpExtensionConfigEntity>

    @Query("SELECT * FROM mcp_extension_configs WHERE extensionId = :extensionId LIMIT 1")
    suspend fun getByExtensionId(extensionId: String): McpExtensionConfigEntity?

    @Upsert
    suspend fun upsert(config: McpExtensionConfigEntity)
}

@Dao
interface SkillExtensionConfigDao {
    @Query("SELECT * FROM skill_extension_configs")
    fun observeAll(): Flow<List<SkillExtensionConfigEntity>>

    @Query("SELECT * FROM skill_extension_configs")
    suspend fun getAll(): List<SkillExtensionConfigEntity>

    @Query("SELECT * FROM skill_extension_configs WHERE extensionId = :extensionId LIMIT 1")
    suspend fun getByExtensionId(extensionId: String): SkillExtensionConfigEntity?

    @Upsert
    suspend fun upsert(config: SkillExtensionConfigEntity)
}

@Dao
interface ExtensionToolDao {
    @Query("SELECT * FROM extension_tools ORDER BY extensionId, toolName")
    fun observeAll(): Flow<List<ExtensionToolEntity>>

    @Query("SELECT * FROM extension_tools ORDER BY extensionId, toolName")
    suspend fun getAll(): List<ExtensionToolEntity>

    @Query("SELECT * FROM extension_tools WHERE extensionId = :extensionId ORDER BY toolName")
    suspend fun getByExtensionId(extensionId: String): List<ExtensionToolEntity>

    @Upsert
    suspend fun upsertAll(tools: List<ExtensionToolEntity>)

    @Query("DELETE FROM extension_tools WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)

    @Query(
        "UPDATE extension_tools SET enabled = :enabled WHERE extensionId = :extensionId AND toolName = :toolName",
    )
    suspend fun setEnabled(extensionId: String, toolName: String, enabled: Boolean)
}

@Dao
interface AgentCardExtensionDao {
    @Query("SELECT * FROM agent_card_extensions")
    fun observeAll(): Flow<List<AgentCardExtensionEntity>>

    @Query("SELECT extensionId FROM agent_card_extensions WHERE cardId = :cardId")
    suspend fun getExtensionIds(cardId: String): List<String>

    @Query("DELETE FROM agent_card_extensions WHERE cardId = :cardId")
    suspend fun deleteByCardId(cardId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(values: List<AgentCardExtensionEntity>)
}
