package com.agentdeck.app.data.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionLevel
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.domain.extensions.SkillExtensionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class SkillPackageInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun validInstructionSkillInstallsAtomicallyAndInvalidMetadataIsRejected() {
        val paths = EmbeddedRuntimePaths(context)
        val installer = SkillPackageInstaller(paths)
        val id = "ext_dddddddddddddddddddddddddddddddd"
        val source = """
            ---
            name: review-loop
            description: Review, test, and fix a change.
            ---
            Inspect the current change, run tests, and fix verified defects.
        """.trimIndent().toByteArray()

        val installed = installer.install(id, ByteArrayInputStream(source))
        try {
            assertEquals("review-loop", installed.name)
            assertTrue(installed.manifestHash.matches(Regex("[a-f0-9]{64}")))
            assertTrue(File(installed.path, "SKILL.md").isFile)
            assertTrue(
                runCatching {
                    installer.install(
                        "ext_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        ByteArrayInputStream(
                            "---\nname: Bad Name\ndescription: nope\n---\nBody\n".toByteArray(),
                        ),
                    )
                }.isFailure,
            )
        } finally {
            installer.delete(installed.path)
        }
    }

    @Test
    fun sessionSnapshotsLiveOutsideTheSharedRuntimeBindAndRemainIndependent() {
        val paths = EmbeddedRuntimePaths(context)
        val installer = SkillPackageInstaller(paths)
        val id = "ext_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val installed = installer.install(
            id,
            ByteArrayInputStream(
                "---\nname: isolated-skill\ndescription: Isolated test skill.\n---\nBody\n".toByteArray(),
            ),
        )
        val extension = ManagedExtension(
            id = id,
            name = installed.name,
            description = installed.description,
            kind = ExtensionKind.SKILL,
            requiredLevel = ExtensionLevel.SKILL,
            enabled = true,
            status = ExtensionStatus.READY,
            skill = SkillExtensionConfig(installed.path, null, installed.manifestHash),
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )
        val first = SkillSnapshot.create(paths, "aaa111", listOf(extension))
        val second = SkillSnapshot.create(paths, "bbb222", listOf(extension))
        try {
            val firstFile = File(paths.extensionSessionSnapshots, "skills.aaa111/$id/SKILL.md")
            val secondFile = File(paths.extensionSessionSnapshots, "skills.bbb222/$id/SKILL.md")
            assertEquals(paths.extensionSessionSnapshots, firstFile.parentFile?.parentFile?.parentFile)
            assertTrue(!firstFile.canonicalPath.startsWith(paths.stateDir.canonicalPath + File.separator))

            firstFile.writeText("changed")
            assertTrue(secondFile.readText().contains("isolated-skill"))
        } finally {
            first.close()
            second.close()
            installer.delete(installed.path)
        }
    }
}
