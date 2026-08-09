package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedRuntimeManifestTest {
    @Test
    fun `manifest pins exact arm64 artifacts`() {
        assertEquals(29_870_567, EmbeddedRuntimeManifest.rootfs.sizeBytes)
        assertEquals(91_607_658, EmbeddedRuntimeManifest.codex.sizeBytes)
        assertTrue(EmbeddedRuntimeManifest.rootfs.url.startsWith("https://cdimage.ubuntu.com/"))
        assertTrue(EmbeddedRuntimeManifest.codex.url.startsWith("https://github.com/openai/codex/"))
        assertTrue(EmbeddedRuntimeManifest.deviceSupported(arrayOf("arm64-v8a")))
        assertFalse(EmbeddedRuntimeManifest.deviceSupported(arrayOf("x86_64")))
    }

    @Test
    fun `tar paths cannot escape target`() {
        val root = File(System.getProperty("java.io.tmpdir"), "agentdeck-safe-root")

        assertEquals(
            File(root, "usr/bin/bash").canonicalPath,
            SecureTarExtractor.secureTarget(root, "./usr/bin/bash").canonicalPath,
        )
        assertTrue(runCatching { SecureTarExtractor.secureTarget(root, "../../outside") }.isFailure)
        assertTrue(runCatching { SecureTarExtractor.secureTarget(root, "/absolute") }.isFailure)
        assertTrue(runCatching { SecureTarExtractor.secureTarget(root, "usr//bin") }.isFailure)
    }
}
