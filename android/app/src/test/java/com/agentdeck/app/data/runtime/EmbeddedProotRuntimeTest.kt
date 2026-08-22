package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedProotRuntimeTest {
    @Test
    fun `owned process marker requires exact environment argument`() {
        val marker = "agentdeck-app-server-0123456789abcdef"

        assertTrue(hasOwnedMarker(listOf("codex", "AGENTDECK_INSTANCE=$marker"), marker))
        assertFalse(hasOwnedMarker(listOf("codex", marker), marker))
        assertFalse(hasOwnedMarker(listOf("codex", "AGENTDECK_INSTANCE=${marker}extra"), marker))
    }

    @Test
    fun `parent pid parser reads proc status`() {
        assertEquals(34, parseParentPid("Name:\tcodex\nPid:\t35\nPPid:\t34\n"))
        assertNull(parseParentPid("Name:\tcodex\nPid:\t35\n"))
    }

    @Test
    fun `app server options keep workspace and provider values structured`() {
        val options = EmbeddedProotRuntime.AppServerOptions.parse(
            listOf(
                "--distro", "ubuntu",
                "--cwd", "/root/project with spaces",
                "--instance-key", "abc123",
                "--provider-id", "agentdeck_0123456789abcdef",
                "--base-url", "https://api.example.com/v1",
                "--model", "gpt-5.4",
                "--credential-ref", "cred_test",
                "--credential-broker-port", "45678",
                "--skill-snapshot-key", "abc123",
            ),
        )

        assertEquals("/root/project with spaces", options.cwd)
        assertEquals("abc123", options.instanceKey)
        assertEquals("gpt-5.4", options.provider?.model)
        assertEquals(45_678, options.provider?.credentialBrokerPort)
        assertEquals("abc123", options.skillSnapshotKey)
    }

    @Test
    fun `app server options reject partial providers and injected paths`() {
        assertTrue(
            runCatching {
                EmbeddedProotRuntime.AppServerOptions.parse(
                    listOf("--cwd", "/root", "--instance-key", "abc", "--model", "gpt-5"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                EmbeddedProotRuntime.AppServerOptions.parse(
                    listOf("--cwd", "/root\nnope", "--instance-key", "abc"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                EmbeddedProotRuntime.AppServerOptions.parse(
                    listOf(
                        "--cwd", "/root",
                        "--instance-key", "abc",
                        "--skill-snapshot-key", "different",
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun `stop request needs no provider`() {
        val options = EmbeddedProotRuntime.AppServerOptions.parse(
            listOf("--instance-key", "abc123", "--stop"),
        )

        assertTrue(options.stop)
        assertNull(options.provider)
    }

    @Test
    fun `workspace under guest projects maps to host bind source segments`() {
        assertEquals(emptyList<String>(), workspaceRelativeSegments("/root/projects"))
        assertEquals(listOf("default"), workspaceRelativeSegments("/root/projects/default"))
        assertEquals(
            listOf("team", "app with spaces"),
            workspaceRelativeSegments("/root/projects/team/app with spaces"),
        )
    }

    @Test
    fun `workspace outside projects or with traversal is not host creatable`() {
        assertNull(workspaceRelativeSegments("/root"))
        assertNull(workspaceRelativeSegments("/home/user/project"))
        assertNull(workspaceRelativeSegments("/root/projects/../etc"))
        assertNull(workspaceRelativeSegments("/root/projects/safe/../../escape"))
    }
}
