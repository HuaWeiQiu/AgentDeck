package com.agentdeck.app.data.repo

import com.agentdeck.app.domain.recipe.RecipeDependencyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeParserTest {
    private val repoRoot: File by lazy {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        generateSequence(File(userDir).canonicalFile) { it.parentFile }
            .first { File(it, "README.md").isFile && File(it, "android/app").isDirectory }
    }

    @Test
    fun `repository recipes match packaged assets and parse strictly`() {
        val sourceDirectory = File(repoRoot, "recipes")
        val assetDirectory = File(repoRoot, "android/app/src/main/assets/recipes")
        val sourceNames = sourceDirectory.listFiles().orEmpty().map { it.name }.sorted()
        val assetNames = assetDirectory.listFiles().orEmpty().map { it.name }.sorted()

        assertEquals(sourceNames, assetNames)
        val parsed = sourceNames.map { name ->
            val source = File(sourceDirectory, name)
            val asset = File(assetDirectory, name)
            assertEquals(source.readText(), asset.readText())
            source.inputStream().use { RecipeParser.parse(it, name) }
        }

        assertEquals(parsed.size, parsed.map { it.id }.distinct().size)
        val ids = parsed.map { it.id }.toSet()
        assertTrue(parsed.flatMap { it.dependsOn }.all(ids::contains))
        parsed.forEach { recipe ->
            RecipeDependencyResolver.resolve(parsed, recipe.id).getOrThrow()
        }
    }

    @Test
    fun `codex recipe preserves usable installs and pins fresh install binaries`() {
        val codex = File(repoRoot, "recipes/codex.yaml").inputStream().use {
            RecipeParser.parse(it, "codex.yaml")
        }
        val script = requireNotNull(codex.install).script
        val verifyScript = requireNotNull(codex.verify).script

        assertEquals("0.147.0", codex.version)
        assertEquals(30, codex.timeoutMinutes)
        assertEquals("codex-ubuntu.sh", codex.wrapperAsset)
        assertEquals(
            listOf("codex-app-server-start.sh", "codex-provider-token.py"),
            codex.additionalWrapperAssets,
        )
        assertTrue(codex.description.contains("版本过低时安装"))
        assertTrue(script.contains("eb677c80f666b1ab8b4b1d083b66e8d614b1281d960bb6f9fd8ca98f58b38b90"))
        assertTrue(script.contains("0246e2e773834e07f0fb5249ed6ebad12e4591e608f8c7bb97dd6a9690544c36"))
        assertTrue(script.contains("expected_size=91607658"))
        assertTrue(script.contains("expected_size=98970270"))
        assertTrue(script.contains("sha256sum --check --status"))
        assertTrue(script.contains("--connect-timeout 15"))
        assertTrue(script.contains("--range \"${'$'}{start}-${'$'}{end}\""))
        assertTrue(script.contains("network_args=(--ipv4)"))
        assertTrue(script.contains("actual_chunk_size != expected_chunk_size"))
        assertTrue(script.indexOf("curl \"${'$'}{curl_options[@]}\"") < script.indexOf("network_args=(--ipv4)"))
        assertTrue(script.indexOf("command -v codex") < script.indexOf("download_chunk()"))
        assertTrue(script.contains("保留现有版本"))
        assertTrue(script.contains("minor > 147"))
        assertTrue(script.contains("安装或升级"))
        assertTrue(verifyScript.contains("command -v codex"))
        assertTrue(verifyScript.contains("codex --version >/dev/null"))
        assertTrue(verifyScript.contains("codex-app-server-start.sh"))
        assertTrue(verifyScript.contains("check_for_update_on_startup=false"))
        assertTrue(verifyScript.contains("--listen ws://127.0.0.1:0"))
        assertTrue(verifyScript.contains("--ws-auth capability-token"))
        assertTrue(verifyScript.contains("START_CONTRACT_VERSION=7"))
        assertFalse(verifyScript.contains("0[.]147[.]0"))
        assertFalse(script.contains("npm install"))
    }

    @Test
    fun `codex dependency prepares ubuntu packages before cli detection`() {
        val base = File(repoRoot, "recipes/base-ubuntu.yaml").inputStream().use {
            RecipeParser.parse(it, "base-ubuntu.yaml")
        }
        val codex = File(repoRoot, "recipes/codex.yaml").inputStream().use {
            RecipeParser.parse(it, "codex.yaml")
        }
        val installScript = requireNotNull(base.install).script
        val verifyScript = requireNotNull(base.verify).script

        assertEquals(listOf(base.id), codex.dependsOn)
        assertTrue(installScript.indexOf("apt-get update") < installScript.indexOf("apt-get install"))
        assertTrue(installScript.contains("pkg install -y proot-distro"))
        assertTrue(installScript.contains("ca-certificates coreutils curl git gzip python3"))
        assertTrue(verifyScript.contains("ca-certificates.crt"))
        assertTrue(verifyScript.contains("command -v gzip"))
        assertTrue(verifyScript.contains("command -v python3"))
        assertTrue(verifyScript.contains("command -v timeout"))
    }

    @Test
    fun `available recipe scripts pass bash syntax validation`() {
        File(repoRoot, "recipes").listFiles().orEmpty()
            .filter { it.extension == "yaml" || it.extension == "yml" }
            .forEach { file ->
                val recipe = file.inputStream().use { RecipeParser.parse(it, file.name) }
                listOfNotNull(recipe.install, recipe.verify).forEach { command ->
                    val process = ProcessBuilder("/bin/bash", "-n")
                        .redirectErrorStream(true)
                        .start()
                    process.outputStream.bufferedWriter().use { it.write(command.script) }
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals("${file.name}: $output", 0, process.waitFor())
                }
            }
    }

    @Test
    fun `duplicate and unknown fields are rejected`() {
        val duplicate = validRecipe().replace("name: Test", "name: Test\nname: Duplicate")
        val unknown = validRecipe() + "\ninstalll: typo\n"

        assertTrue(parseFailure(duplicate).contains("duplicate", ignoreCase = true))
        assertTrue(parseFailure(unknown).contains("未知字段"))
    }

    @Test
    fun `available recipe requires install and verify commands`() {
        val missingVerify = validRecipe().substringBefore("verify:").trimEnd()

        assertTrue(parseFailure(missingVerify).contains("缺少 verify"))
    }

    @Test
    fun `integer fields reject fractional values`() {
        val fractional = validRecipe().replace("timeout_minutes: 5", "timeout_minutes: 1.5")

        assertTrue(parseFailure(fractional).contains("整数型字段"))
    }

    private fun parseFailure(yaml: String): String {
        return runCatching {
            yaml.byteInputStream().use { RecipeParser.parse(it, "test.yaml") }
        }.exceptionOrNull()?.message.orEmpty()
    }

    private fun validRecipe() = """
        schema_version: 1
        id: recipe_test
        name: Test
        description: Test recipe
        priority: p0
        version: "1.0.0"
        available: true
        depends_on: []
        timeout_minutes: 5
        install:
          runtime: termux
          script: echo install
        verify:
          runtime: termux
          script: echo verify
    """.trimIndent()
}
