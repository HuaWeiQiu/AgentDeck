package com.agentdeck.app.data.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxContractTest {
    @Test
    fun `named session constants match RUN_COMMAND contract`() {
        assertEquals(
            "com.termux.RUN_COMMAND_SHELL_NAME",
            AndroidTermuxGateway.EXTRA_SHELL_NAME,
        )
        assertEquals(
            "com.termux.RUN_COMMAND_SHELL_CREATE_MODE",
            AndroidTermuxGateway.EXTRA_SHELL_CREATE_MODE,
        )
        assertEquals("0", AndroidTermuxGateway.SESSION_ACTION_SWITCH_TO_SESSION_AND_OPEN)
        assertEquals(
            "no-shell-with-name",
            AndroidTermuxGateway.SHELL_CREATE_MODE_NO_SHELL_WITH_NAME,
        )
        assertEquals("app-shell", AndroidTermuxGateway.RUNNER_APP_SHELL)
        assertEquals(
            "com.termux.RUN_COMMAND_PENDING_INTENT",
            AndroidTermuxGateway.EXTRA_PENDING_INTENT,
        )
    }

    @Test
    fun `invalid session name is rejected before sending intent`() {
        val result = runCatching {
            TermuxCommand(
                sessionName = "bad session name",
                executable = "/data/data/com.termux/files/usr/bin/true",
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `executable outside Termux private files is rejected`() {
        val result = runCatching {
            TermuxCommand(
                sessionName = "agentdeck-test",
                executable = "/system/bin/sh",
            )
        }

        assertTrue(result.isFailure)
    }
}
