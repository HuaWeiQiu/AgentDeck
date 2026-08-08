package com.agentdeck.app.data.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxResultInterpreterTest {
    @Test
    fun `missing result bundle is rejected`() {
        val result = TermuxResultInterpreter.interpret(null)

        assertTrue(result.isFailure)
        assertEquals("Termux 回调缺少 result Bundle", result.exceptionOrNull()?.message)
    }

    @Test
    fun `Termux internal error is a transport failure`() {
        val result = TermuxResultInterpreter.interpret(
            payload(internalErrorCode = 5, internalErrorMessage = "permission denied"),
        )

        assertTrue(result.isFailure)
        assertEquals("permission denied", result.exceptionOrNull()?.message)
    }

    @Test
    fun `nonzero command exit remains an observable command result`() {
        val result = TermuxResultInterpreter.interpret(
            payload(exitCode = 127, stderr = "not found"),
        ).getOrThrow()

        assertEquals(127, result.exitCode)
        assertEquals("not found", result.stderr)
        assertFalse(result.commandSucceeded)
    }

    @Test
    fun `reported original lengths expose truncated output`() {
        val result = TermuxResultInterpreter.interpret(
            payload(stdout = "tail", stdoutOriginalLength = 120),
        ).getOrThrow()

        assertTrue(result.outputWasTruncated)
    }

    private fun payload(
        stdout: String = "ok",
        stderr: String = "",
        exitCode: Int? = 0,
        internalErrorCode: Int? = -1,
        internalErrorMessage: String = "",
        stdoutOriginalLength: Int? = stdout.length,
        stderrOriginalLength: Int? = stderr.length,
    ) = TermuxResultPayload(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        internalErrorCode = internalErrorCode,
        internalErrorMessage = internalErrorMessage,
        stdoutOriginalLength = stdoutOriginalLength,
        stderrOriginalLength = stderrOriginalLength,
    )
}
