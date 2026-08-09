package com.agentdeck.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexPermissionLevelTest {
    @Test
    fun `storage parsing fails to recommended default`() {
        assertEquals(CodexPermissionLevel.ASK_FIRST, CodexPermissionLevel.fromStorage(null))
        assertEquals(CodexPermissionLevel.ASK_FIRST, CodexPermissionLevel.fromStorage("unknown"))
        assertEquals(CodexPermissionLevel.READ_ONLY, CodexPermissionLevel.fromStorage("READ_ONLY"))
        assertNull(CodexPermissionLevel.overrideFromStorage("unknown"))
    }

    @Test
    fun `conversation override takes precedence over global default`() {
        assertEquals(
            CodexPermissionLevel.READ_ONLY,
            CodexPermissionLevel.effective(
                CodexPermissionLevel.READ_ONLY,
                CodexPermissionLevel.FULL_ACCESS,
            ),
        )
        assertEquals(
            CodexPermissionLevel.ASK_FIRST,
            CodexPermissionLevel.effective(null, CodexPermissionLevel.ASK_FIRST),
        )
    }
}
