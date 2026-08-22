package com.agentdeck.app.domain.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTextInputTest {

    @Test
    fun `ascii text is safe and space escaped`() {
        assertEquals("hello%s%sdroid", ShellTextInput.safeArgumentOrNull("hello  droid"))
        assertNull(ShellTextInput.safeArgumentOrNull(""))
        assertNull(ShellTextInput.safeArgumentOrNull("你好"))
        assertNull(ShellTextInput.safeArgumentOrNull("semi;danger"))
        assertNull(ShellTextInput.safeArgumentOrNull("pipe|here"))
        assertNull(ShellTextInput.safeArgumentOrNull("quote'here"))
    }

    @Test
    fun `isAsciiSafe rejects metacharacters and non-ascii`() {
        assertFalse(ShellTextInput.isAsciiSafe("a'b"))
        assertTrue(ShellTextInput.isAsciiSafe("plain-text_1.2"))
        assertFalse(ShellTextInput.isAsciiSafe("中文"))
        assertFalse(ShellTextInput.isAsciiSafe("tab\there"))
    }
}
