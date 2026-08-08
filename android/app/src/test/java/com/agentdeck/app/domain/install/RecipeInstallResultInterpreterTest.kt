package com.agentdeck.app.domain.install

import com.agentdeck.app.data.termux.TermuxCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeInstallResultInterpreterTest {
    @Test
    fun `nonzero exit is reported as install failure`() {
        val result = RecipeInstallResultInterpreter.interpret(
            commandResult(exitCode = 2, stderr = "package failed"),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("退出码 2"))
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("package failed"))
    }

    @Test
    fun `zero exit returns final nonblank output line`() {
        val result = RecipeInstallResultInterpreter.interpret(
            commandResult(stdout = "downloading\ninstall finished\n"),
        ).getOrThrow()

        assertEquals("install finished", result)
    }

    @Test
    fun `successful truncated result is disclosed`() {
        val result = RecipeInstallResultInterpreter.interpret(
            commandResult(stdout = "install finished", stdoutOriginalLength = 2_000),
        ).getOrThrow()

        assertTrue(result.contains("已截断"))
    }

    @Test
    fun `successful output detail is bounded for UI`() {
        val result = RecipeInstallResultInterpreter.interpret(
            commandResult(stdout = "x".repeat(2_000)),
        ).getOrThrow()

        assertEquals(240, result.length)
    }

    private fun commandResult(
        stdout: String = "",
        stderr: String = "",
        exitCode: Int = 0,
        stdoutOriginalLength: Int? = stdout.length,
    ) = TermuxCommandResult(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        stdoutOriginalLength = stdoutOriginalLength,
        stderrOriginalLength = stderr.length,
    )
}
