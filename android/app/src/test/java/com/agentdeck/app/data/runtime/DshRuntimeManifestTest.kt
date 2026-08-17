package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshRuntimeManifestTest {
    @Test
    fun pins_official_node_and_dsh_versions() {
        assertEquals("v24.19.0", DshRuntimeManifest.NODE_VERSION)
        assertEquals("0.1.0-rc.6", DshRuntimeManifest.DSH_NPM_VERSION)
        assertEquals("@deepseek-ai/dsh@0.1.0-rc.6", DshRuntimeManifest.DSH_NPM_SPEC)
    }

    @Test
    fun arm64_and_x64_artifacts_match_published_sizes() {
        val arm = DshRuntimeManifest.forDevice(arrayOf("arm64-v8a"))!!
        assertEquals(57_128_466L, arm.node.sizeBytes)
        assertEquals(
            "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f",
            arm.node.sha256,
        )
        assertTrue(arm.node.urls.any { it.contains("nodejs.org") })
        assertTrue(arm.node.urls.any { it.contains("npmmirror.com") })

        val x64 = DshRuntimeManifest.forDevice(arrayOf("x86_64"))!!
        assertEquals(57_409_532L, x64.node.sizeBytes)
        assertEquals(
            "f625d97cd707df4ff96254916fbc5ff014f09c09effe5a1e0ca8f6d41a8789d4",
            x64.node.sha256,
        )
    }

    @Test
    fun estimated_download_is_node_tarball_only() {
        val arm = DshRuntimeManifest.forDevice(arrayOf("arm64-v8a"))!!
        assertEquals(arm.node.sizeBytes, DshRuntimeManifest.estimatedDownloadBytes(arm))
    }
}
