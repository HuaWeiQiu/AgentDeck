package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EmbeddedRuntimeManifestTest {
    @Test
    fun `catalog pins exact artifacts for both supported ABIs`() {
        val arm64 = requireNotNull(EmbeddedRuntimeManifest.forDevice(arrayOf("arm64-v8a")))
        val x86 = requireNotNull(EmbeddedRuntimeManifest.forDevice(arrayOf("x86_64")))

        assertEquals(29_870_567, arm64.rootfs.sizeBytes)
        assertEquals(91_607_658, arm64.codex.sizeBytes)
        assertEquals("codex-aarch64-unknown-linux-musl", arm64.codexBinaryName)
        assertEquals(29_989_394, x86.rootfs.sizeBytes)
        assertEquals(98_970_270, x86.codex.sizeBytes)
        assertEquals("codex-x86_64-unknown-linux-musl", x86.codexBinaryName)
        assertTrue(x86.rootfs.url.startsWith("https://cdimage.ubuntu.com/"))
        assertTrue(x86.codex.url.startsWith("https://github.com/openai/codex/"))
        assertTrue(EmbeddedRuntimeManifest.deviceSupported(arrayOf("arm64-v8a")))
        assertTrue(EmbeddedRuntimeManifest.deviceSupported(arrayOf("x86_64")))
        assertFalse(EmbeddedRuntimeManifest.deviceSupported(arrayOf("armeabi-v7a")))
        assertEquals(
            "x86_64",
            EmbeddedRuntimeManifest.forDevice(arrayOf("x86_64", "arm64-v8a"))?.androidAbi,
        )
    }

    @Test
    fun `runtime marker cannot cross ABI or release boundaries`() {
        val arm64 = requireNotNull(EmbeddedRuntimeManifest.forDevice(arrayOf("arm64-v8a")))
        val x86 = requireNotNull(EmbeddedRuntimeManifest.forDevice(arrayOf("x86_64")))
        val marker = runtimeMarkerContent(arm64)

        assertTrue(runtimeMarkerMatches(marker, arm64))
        assertFalse(runtimeMarkerMatches(marker, x86))
        assertFalse(runtimeMarkerMatches(marker.replace(arm64.releaseId, "other-release"), arm64))
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

    @Test
    fun `runtime install rejects storage below documented peak requirement`() {
        assertFalse(hasRequiredRuntimeSpace(1_100L * 1024 * 1024 - 1))
        assertTrue(hasRequiredRuntimeSpace(1_100L * 1024 * 1024))
    }

    @Test
    fun `runtime cleanup deletes symlinks without following them`() {
        val parent = Files.createTempDirectory("agentdeck-cleanup-test")
        val runtime = Files.createDirectory(parent.resolve("rootfs-old"))
        val outside = Files.write(parent.resolve("keep.txt"), "keep".toByteArray())
        Files.createDirectories(runtime.resolve("usr/bin"))
        Files.write(runtime.resolve("usr/bin/tool"), "tool".toByteArray())
        Files.createSymbolicLink(runtime.resolve("outside-link"), outside)

        deleteTreeWithoutFollowingLinks(runtime)

        assertFalse(Files.exists(runtime))
        assertTrue(Files.exists(outside))
        parent.toFile().deleteRecursively()
    }
}
