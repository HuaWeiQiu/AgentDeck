package com.agentdeck.app.domain.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAutomationCoreTest {
    @Test
    fun sanitizer_keeps_interactive_nodes_and_drops_empty_containers() {
        val raw = listOf(
            RawUiNode(
                fingerprint = "empty",
                packageName = "com.example.app",
                className = "android.widget.FrameLayout",
            ),
            RawUiNode(
                fingerprint = "submit",
                packageName = "com.example.app",
                className = "android.widget.Button",
                text = "提交",
                clickable = true,
                bounds = intArrayOf(100, 800, 900, 900),
                windowWidth = 1000,
                windowHeight = 1000,
            ),
        )
        val snapshot = AccessibilityTreeSanitizer.sanitize(
            rawNodes = raw,
            packageName = "com.example.app",
            windowTitle = "Demo",
            snapshotId = "snap-1",
            sessionNonce = "nonce",
            capturedAtEpochMs = 1L,
            selfPackage = "com.agentdeck.app.lab.debug",
        )
        assertEquals(1, snapshot.nodes.size)
        assertEquals("button", snapshot.nodes.single().role)
        assertEquals("提交", snapshot.nodes.single().text)
        assertTrue(snapshot.nodes.single().actions.contains("click"))
        assertFalse(snapshot.sensitive)
    }

    @Test
    fun sensitive_password_screen_clears_nodes() {
        val raw = listOf(
            RawUiNode(
                fingerprint = "pwd",
                packageName = "com.bank.app",
                className = "android.widget.EditText",
                text = "密码",
                password = true,
                editable = true,
            ),
        )
        val snapshot = AccessibilityTreeSanitizer.sanitize(
            rawNodes = raw,
            packageName = "com.bank.app",
            windowTitle = "Login",
            snapshotId = "snap-2",
            sessionNonce = "nonce",
            capturedAtEpochMs = 1L,
            selfPackage = "com.agentdeck.app.lab.debug",
        )
        assertTrue(snapshot.sensitive)
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun node_ids_do_not_replay_across_snapshots() {
        val raw = RawUiNode(
            fingerprint = "same",
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "OK",
            clickable = true,
        )
        val first = AccessibilityTreeSanitizer.nodeId("nonce", "snap-a", raw.fingerprint)
        val second = AccessibilityTreeSanitizer.nodeId("nonce", "snap-b", raw.fingerprint)
        assertNotEquals(first, second)
    }

    @Test
    fun session_manager_is_single_owner_and_budgets_steps() {
        var now = 1_000L
        val sessions = UiAutomationSessionManager(nowMs = { now }, selfPackage = "com.agentdeck.app")
        val grant = sessions.start("c1", "i1", setOf("com.example.app"), steps = 5)
        assertEquals(5, grant.remainingSteps)
        repeat(5) { assertEquals(null, sessions.consumeStep()) }
        assertEquals("UI_SESSION_BUDGET", sessions.consumeStep()?.code)
        assertEquals(null, sessions.current())
    }

    @Test
    fun session_manager_rejects_self_and_denied_packages() {
        val sessions = UiAutomationSessionManager(selfPackage = "com.agentdeck.app.debug")
        try {
            sessions.start("c1", "i1", setOf("com.agentdeck.app.debug", "com.android.settings"))
            throw AssertionError("expected denial")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("没有可操作的应用"))
        }
    }
}
