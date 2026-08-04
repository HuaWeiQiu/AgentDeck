package com.agentdeck.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderProfileDao {
    @Query("SELECT * FROM provider_profiles ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ProviderProfileEntity>>

    @Query("SELECT * FROM provider_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProviderProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentCardEntity)

    @Query("DELETE FROM agent_cards WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM agent_cards")
    suspend fun count(): Int
}
