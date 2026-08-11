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
        ProviderModelEntity::class,
        AgentCardEntity::class,
        ExtensionEntity::class,
        McpExtensionConfigEntity::class,
        SkillExtensionConfigEntity::class,
        ExtensionToolEntity::class,
        AgentCardExtensionEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun providerProfileDao(): ProviderProfileDao
    abstract fun providerModelDao(): ProviderModelDao
    abstract fun agentCardDao(): AgentCardDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun mcpExtensionConfigDao(): McpExtensionConfigDao
    abstract fun skillExtensionConfigDao(): SkillExtensionConfigDao
    abstract fun extensionToolDao(): ExtensionToolDao
    abstract fun agentCardExtensionDao(): AgentCardExtensionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentdeck.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN adapterId TEXT NOT NULL " +
                        "DEFAULT 'OPENAI_RESPONSES'",
                )
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN credentialRef TEXT",
                )
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN connectionStatus TEXT NOT NULL " +
                        "DEFAULT 'UNVERIFIED'",
                )
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN lastCheckedAtEpochMs INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE provider_profiles ADD COLUMN updatedAtEpochMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE provider_profiles SET updatedAtEpochMs = createdAtEpochMs",
                )
                db.execSQL(
                    "UPDATE provider_profiles SET adapterId = 'ANTHROPIC', " +
                        "connectionStatus = 'UNSUPPORTED' WHERE type = 'ANTHROPIC'",
                )
                db.execSQL(
                    "ALTER TABLE agent_cards ADD COLUMN modelId TEXT",
                )
                db.execSQL("UPDATE agent_cards SET profileId = NULL, modelId = NULL")
                db.execSQL(
                    """
                    CREATE TABLE provider_models (
                        providerId TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        discoveredAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(providerId, modelId),
                        FOREIGN KEY(providerId) REFERENCES provider_profiles(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_provider_models_providerId ON provider_models(providerId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN permissionLevel TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN customTitle TEXT")
                db.execSQL(
                    "ALTER TABLE agent_cards ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE agent_cards ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE agent_cards ADD COLUMN lastActiveAtEpochMs INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN roleName TEXT")
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN roleSelfDefinition TEXT")
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN roleObjective TEXT")
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN roleCommunicationStyle TEXT")
                db.execSQL("ALTER TABLE agent_cards ADD COLUMN roleBoundaries TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE extensions (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        requiredLevel INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE mcp_extension_configs (
                        extensionId TEXT NOT NULL,
                        transport TEXT NOT NULL,
                        url TEXT,
                        command TEXT,
                        argsJson TEXT NOT NULL,
                        authType TEXT NOT NULL,
                        credentialRef TEXT,
                        PRIMARY KEY(extensionId),
                        FOREIGN KEY(extensionId) REFERENCES extensions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_mcp_extension_configs_extensionId " +
                        "ON mcp_extension_configs(extensionId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE skill_extension_configs (
                        extensionId TEXT NOT NULL,
                        installedPath TEXT NOT NULL,
                        version TEXT,
                        manifestHash TEXT NOT NULL,
                        PRIMARY KEY(extensionId),
                        FOREIGN KEY(extensionId) REFERENCES extensions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX index_skill_extension_configs_extensionId " +
                        "ON skill_extension_configs(extensionId)",
                )
                db.execSQL(
                    """
                    CREATE TABLE extension_tools (
                        extensionId TEXT NOT NULL,
                        toolName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        accessLevel TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        discoveredAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(extensionId, toolName),
                        FOREIGN KEY(extensionId) REFERENCES extensions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_extension_tools_extensionId ON extension_tools(extensionId)")
                db.execSQL(
                    """
                    CREATE TABLE agent_card_extensions (
                        cardId TEXT NOT NULL,
                        extensionId TEXT NOT NULL,
                        PRIMARY KEY(cardId, extensionId),
                        FOREIGN KEY(cardId) REFERENCES agent_cards(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(extensionId) REFERENCES extensions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_agent_card_extensions_cardId ON agent_card_extensions(cardId)")
                db.execSQL(
                    "CREATE INDEX index_agent_card_extensions_extensionId " +
                        "ON agent_card_extensions(extensionId)",
                )
            }
        }
    }
}
