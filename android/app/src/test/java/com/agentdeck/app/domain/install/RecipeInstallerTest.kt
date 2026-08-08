package com.agentdeck.app.domain.install

import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxCommandResult
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.AgentRecipe
import com.agentdeck.app.domain.model.RecipeCommand
import com.agentdeck.app.domain.model.RecipeRuntime
import com.agentdeck.app.domain.recipe.RecipeCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeInstallerTest {
    @Test
    fun `dependency chain installs and verifies in order`() = runBlocking {
        val gateway = FakeTermuxGateway(
            ArrayDeque(listOf(exit(1), exit(0), exit(0), exit(1), exit(0), exit(0))),
        )
        val catalog = FakeRecipeCatalog(
            listOf(recipe("base"), recipe("target", listOf("base"), wrapper = "wrapper.sh")),
        )

        val result = RecipeInstaller(gateway, catalog).install("target")

        assertEquals("target 1.0.0 已安装并验证", result.getOrThrow())
        assertEquals(6, gateway.commands.size)
        assertEquals(
            listOf("echo verify-base", "echo install-base", "echo verify-base"),
            gateway.commands.take(3).map { it.args.last() },
        )
        val targetInstall = gateway.commands[4].args.last()
        assertTrue(targetInstall.contains("echo install-target"))
        assertTrue(targetInstall.contains("#!/bin/bash"))
        assertTrue(targetInstall.contains("chmod 700"))
    }

    @Test
    fun `ready recipes skip all install commands`() = runBlocking {
        val gateway = FakeTermuxGateway(ArrayDeque(listOf(exit(0), exit(0))))
        val catalog = FakeRecipeCatalog(listOf(recipe("base"), recipe("target", listOf("base"))))

        val result = RecipeInstaller(gateway, catalog).install("target")

        assertTrue(result.isSuccess)
        assertEquals(2, gateway.commands.size)
        assertTrue(gateway.commands.all { it.args.last().startsWith("echo verify-") })
    }

    @Test
    fun `failed post install verification is not reported as success`() = runBlocking {
        val gateway = FakeTermuxGateway(ArrayDeque(listOf(exit(1), exit(0), exit(7, "wrong version"))))
        val catalog = FakeRecipeCatalog(listOf(recipe("target")))

        val result = RecipeInstaller(gateway, catalog).install("target")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("安装后验证失败"))
    }

    @Test
    fun `unavailable recipe cannot start Termux`() = runBlocking {
        val gateway = FakeTermuxGateway(ArrayDeque())
        val catalog = FakeRecipeCatalog(listOf(recipe("planned", available = false)))

        val result = RecipeInstaller(gateway, catalog).install("planned")

        assertTrue(result.isFailure)
        assertFalse(gateway.commands.isNotEmpty())
    }

    private fun recipe(
        id: String,
        dependencies: List<String> = emptyList(),
        wrapper: String? = null,
        available: Boolean = true,
    ) = AgentRecipe(
        schemaVersion = 1,
        id = id,
        name = id,
        description = id,
        priority = "p0",
        version = "1.0.0",
        available = available,
        dependsOn = dependencies,
        timeoutMinutes = 5,
        install = if (available) RecipeCommand(RecipeRuntime.TERMUX, "echo install-$id") else null,
        verify = if (available) RecipeCommand(RecipeRuntime.TERMUX, "echo verify-$id") else null,
        wrapperAsset = wrapper,
    )

    private fun exit(code: Int, stderr: String = "") = Result.success(
        TermuxCommandResult("", stderr, code, 0, stderr.length),
    )

    private class FakeRecipeCatalog(
        private val recipes: List<AgentRecipe>,
    ) : RecipeCatalog {
        override fun loadRecipes(): List<AgentRecipe> = recipes
        override fun readWrapperAsset(name: String): String = "#!/bin/bash\necho wrapper\n"
    }

    private class FakeTermuxGateway(
        private val results: ArrayDeque<Result<TermuxCommandResult>>,
    ) : TermuxGateway {
        val commands = mutableListOf<TermuxCommand>()

        override fun isTermuxInstalled() = true
        override fun hasRunCommandPermission() = true
        override fun openTermux() = true
        override fun openTermuxInstallPage() = true
        override fun runCommand(command: TermuxCommand) = Result.success(Unit)

        override suspend fun runCommandForResult(
            command: TermuxCommand,
            timeoutMillis: Long,
        ): Result<TermuxCommandResult> {
            commands += command
            return results.removeFirst()
        }
    }
}
