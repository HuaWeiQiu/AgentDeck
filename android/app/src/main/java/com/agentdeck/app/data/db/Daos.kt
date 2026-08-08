package com.agentdeck.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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

    @Query("SELECT * FROM provider_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProviderProfileEntity?

    @Upsert
    suspend fun upsert(entity: ProviderProfileEntity)

    @Query("DELETE FROM provider_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM provider_profiles")
    suspend fun count(): Int
}

@Dao
interface AgentCardDao {
    @Query("SELECT * FROM agent_cards ORDER BY name ASC")
    fun observeAll(): Flow<List<AgentCardEntity>>

    @Query("SELECT * FROM agent_cards WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentCardEntity?

    @Upsert
    suspend fun upsert(entity: AgentCardEntity)

    @Query("DELETE FROM agent_cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM agent_cards")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM agent_cards WHERE profileId = :profileId")
    suspend fun countByProfileId(profileId: String): Int
}
