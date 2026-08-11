package com.agentdeck.app.data.extensions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.db.ExtensionEntity
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.data.secure.ExtensionCredentialVault
import com.agentdeck.app.domain.extensions.ExtensionLevel
import com.agentdeck.app.domain.extensions.ExtensionPolicy
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExtensionRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repository: ExtensionRepository
    private lateinit var paths: EmbeddedRuntimePaths
    private lateinit var vault: TrackingExtensionVault

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        paths = EmbeddedRuntimePaths(context)
        vault = TrackingExtensionVault()
        repository = ExtensionRepository(
            db,
            ExtensionPolicy(ExtensionLevel.REMOTE_WRITE.value),
            vault,
            paths,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun cardSelectionPreservesDisabledItemsForTheGlobalToggle() = runBlocking {
        db.extensionDao().upsert(extension(enabled = true))
        val card = card("Original")

        repository.saveCardWithSelections(card, setOf("ext_test"))

        assertEquals(listOf("ext_test"), db.agentCardExtensionDao().getExtensionIds(card.id))
        assertEquals("Original", db.agentCardDao().getById(card.id)?.name)

        db.extensionDao().setEnabled("ext_test", false, 2)
        repository.saveCardWithSelections(card("Renamed"), setOf("ext_test"))

        assertEquals("Renamed", db.agentCardDao().getById(card.id)?.name)
        assertEquals(listOf("ext_test"), db.agentCardExtensionDao().getExtensionIds(card.id))
    }

    @Test
    fun concurrentSkillImportsRejectTheDuplicateName() = runBlocking {
        val source = "---\nname: duplicate-skill\ndescription: Duplicate import test.\n---\nBody\n".toByteArray()
        val results = List(2) {
            async { runCatching { repository.importSkill(ByteArrayInputStream(source)) } }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        results.mapNotNull { it.getOrNull() }.forEach { repository.delete(it.id) }
    }

    @Test
    fun firstReadReconcilesOrphanedExternalResources() = runBlocking {
        paths.ensureHostLayout()
        val orphan = File(paths.extensionPackages, "ext_ffffffffffffffffffffffffffffffff")
        assertTrue(orphan.mkdirs() || orphan.isDirectory)
        File(orphan, "SKILL.md").writeText("orphan")

        repository.getAll()

        assertTrue(!orphan.exists())
        assertEquals(emptySet<String>(), vault.lastValidRefs)
    }

    private fun extension(enabled: Boolean) = ExtensionEntity(
        id = "ext_test",
        name = "Docs",
        description = "",
        kind = "REMOTE_MCP",
        requiredLevel = ExtensionLevel.REMOTE_READ.value,
        enabled = enabled,
        status = "READY",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private fun card(name: String) = AgentCard(
        id = "card_test",
        name = name,
        icon = "codex",
        recipeId = "recipe_codex",
        templateId = "tpl_codex_ubuntu",
        profileId = null,
        termuxSessionName = "agentdeck-codex-test",
        workspaceNamespace = PathNamespace.UBUNTU,
        workspacePath = "/root/projects/default",
    )
}

private class TrackingExtensionVault : ExtensionCredentialVault {
    var lastValidRefs: Set<String>? = null
    override fun save(credentialRef: String, secret: ByteArray) = Unit
    override fun load(credentialRef: String): ByteArray? = null
    override fun contains(credentialRef: String) = false
    override fun delete(credentialRef: String) = Unit
    override fun pruneExcept(validCredentialRefs: Set<String>) {
        lastValidRefs = validCredentialRefs
    }
}
