package com.agentdeck.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppMetadataEntity::class,
        ProviderProfileEntity::class,
        AgentCardEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun agentCardDao(): AgentCardDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentdeck.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE provider_profiles_v2 (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        baseUrl TEXT NOT NULL,
                        defaultModel TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO provider_profiles_v2 (
                        id, name, type, baseUrl, defaultModel, createdAtEpochMs
                    )
                    SELECT id, name, type, baseUrl, defaultModel, createdAtEpochMs
                    FROM provider_profiles
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE provider_profiles")
                db.execSQL("ALTER TABLE provider_profiles_v2 RENAME TO provider_profiles")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE agent_cards_v3 (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        recipeId TEXT NOT NULL,
                        templateId TEXT NOT NULL,
                        profileId TEXT,
                        termuxSessionName TEXT NOT NULL,
                        workspaceNamespace TEXT NOT NULL,
                        workspacePath TEXT NOT NULL,
                        distro TEXT NOT NULL,
                        innerBin TEXT NOT NULL,
                        innerArgsCsv TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(profileId) REFERENCES provider_profiles(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO agent_cards_v3 (
                        id, name, icon, recipeId, templateId, profileId,
                        termuxSessionName, workspaceNamespace, workspacePath,
                        distro, innerBin, innerArgsCsv, enabled
                    )
                    SELECT
                        id, name, icon, recipeId, templateId,
                        CASE
                            WHEN profileId IS NULL THEN NULL
                            WHEN EXISTS (
                                SELECT 1 FROM provider_profiles WHERE provider_profiles.id = agent_cards.profileId
                            ) THEN profileId
                            ELSE NULL
                        END,
                        termuxSessionName, workspaceNamespace, workspacePath,
                        distro, innerBin, innerArgsCsv, enabled
                    FROM agent_cards
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE agent_cards")
                db.execSQL("ALTER TABLE agent_cards_v3 RENAME TO agent_cards")
                db.execSQL("CREATE INDEX index_agent_cards_profileId ON agent_cards(profileId)")
                db.execSQL(
                    """
                    CREATE TABLE app_metadata (
                        `key` TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO app_metadata (`key`, value) VALUES ('initial_seed_completed', 'true')",
                )
            }
        }
    }
}
