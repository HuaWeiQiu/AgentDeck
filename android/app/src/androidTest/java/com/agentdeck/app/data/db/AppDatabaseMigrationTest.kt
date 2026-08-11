package com.agentdeck.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun prepare() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migration3To8PreservesDataAndAddsExtensionRelations() {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE provider_profiles (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    baseUrl TEXT NOT NULL,
                    defaultModel TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE agent_cards (
                    id TEXT NOT NULL PRIMARY KEY,
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
                    FOREIGN KEY(profileId) REFERENCES provider_profiles(id)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX index_agent_cards_profileId ON agent_cards(profileId)")
            db.execSQL(
                "CREATE TABLE app_metadata (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO provider_profiles VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf("prof_old", "Old", "OPENAI_COMPATIBLE", "https://example.com/v1", "old-model", 42L),
            )
            db.execSQL(
                """
                INSERT INTO agent_cards VALUES (
                    'card_old', 'Codex', 'codex', 'recipe_codex', 'tpl_codex_ubuntu',
                    'prof_old', 'agentdeck-codex-old', 'UBUNTU', '/root/projects/default',
                    'ubuntu', 'codex', '', 1
                )
                """.trimIndent(),
            )
            db.version = 3
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
            )
            .build()
        try {
            val database = room.openHelper.writableDatabase
            database.query(
                "SELECT adapterId, credentialRef, connectionStatus, updatedAtEpochMs " +
                    "FROM provider_profiles WHERE id = 'prof_old'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("OPENAI_RESPONSES", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertEquals("UNVERIFIED", cursor.getString(2))
                assertEquals(42L, cursor.getLong(3))
            }
            database.query(
                "SELECT profileId, modelId, permissionLevel FROM agent_cards WHERE id = 'card_old'",
            )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.isNull(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.isNull(2))
                }
            database.query("PRAGMA foreign_key_list(provider_models)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("provider_profiles", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
            database.query(
                "SELECT customTitle, pinned, archived FROM agent_cards WHERE id = 'card_old'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                }
            database.query(
                "SELECT lastActiveAtEpochMs, roleName, roleSelfDefinition, roleObjective, " +
                    "roleCommunicationStyle, roleBoundaries FROM agent_cards WHERE id = 'card_old'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
                for (index in 1..5) assertTrue(cursor.isNull(index))
            }
            database.query("PRAGMA index_list(agent_card_extensions)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("index_agent_card_extensions_extensionId" in names)
            }

            database.execSQL(
                "INSERT INTO extensions VALUES ('ext_test', 'Docs', '', 'REMOTE_MCP', 1, 1, 'READY', 1, 1)",
            )
            assertTrue(
                runCatching {
                    database.execSQL(
                        "INSERT INTO agent_card_extensions VALUES ('card_old', 'missing_extension')",
                    )
                }.isFailure,
            )
            database.execSQL(
                "INSERT INTO mcp_extension_configs VALUES " +
                    "('ext_test', 'streamable_http', 'https://example.com/mcp', NULL, '[]', 'NONE', NULL)",
            )
            database.execSQL(
                "INSERT INTO extension_tools VALUES " +
                    "('ext_test', 'search', 'Search', '', 'READ', 1, 1)",
            )
            database.execSQL(
                "INSERT INTO agent_card_extensions VALUES ('card_old', 'ext_test')",
            )
            database.execSQL("DELETE FROM extensions WHERE id = 'ext_test'")
            listOf("mcp_extension_configs", "extension_tools", "agent_card_extensions").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            room.close()
        }
    }

    companion object {
        private const val DATABASE_NAME = "agentdeck-migration-test.db"
    }
}
