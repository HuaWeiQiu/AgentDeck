package com.agentdeck.app.domain.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPathGuardTest {
    @Test
    fun `normalizes relative paths`() {
        assertEquals("", HostPathGuard.normalizeRelative(null))
        assertEquals("", HostPathGuard.normalizeRelative(""))
        assertEquals("", HostPathGuard.normalizeRelative("."))
        assertEquals("a/b/c", HostPathGuard.normalizeRelative("a//b/./c"))
        assertEquals("notes/todo.md", HostPathGuard.normalizeRelative("notes/todo.md"))
        assertEquals("notes/todo.md", HostPathGuard.normalizeRelative("./notes/todo.md"))
    }

    @Test
    fun `rejects traversal and absolute forms`() {
        assertNull(HostPathGuard.normalizeRelative("../secret"))
        assertNull(HostPathGuard.normalizeRelative("a/../../b"))
        assertNull(HostPathGuard.normalizeRelative("/etc/passwd"))
        assertNull(HostPathGuard.normalizeRelative("C:\\Windows"))
        assertNull(HostPathGuard.normalizeRelative("content://media/external"))
        assertNull(HostPathGuard.normalizeRelative("file:///sdcard/x"))
        assertNull(HostPathGuard.normalizeRelative("a\u0000b"))
    }

    @Test
    fun `child relative rejects separators`() {
        assertEquals("a/b", HostPathGuard.childRelative("a", "b"))
        assertNull(HostPathGuard.childRelative("a", ".."))
        assertNull(HostPathGuard.childRelative("a", "b/c"))
    }
}
