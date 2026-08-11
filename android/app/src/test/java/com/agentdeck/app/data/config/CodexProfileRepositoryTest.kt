package com.agentdeck.app.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProfileRepositoryTest {
    @Test
    fun `default profile is a valid guided template`() {
        val template = CodexProfileRepository.DEFAULT_CONTENT

        assertTrue(template.contains("无必填项"))
        assertTrue(template.contains("模板版本：2"))
        assertTrue(template.contains("# model_reasoning_effort = \"medium\""))
        assertTrue(template.contains("# [mcp_servers.example]"))
        assertTrue(template.contains("# model_provider = \"example\""))
        assertTrue(template.contains("# [model_providers.example]"))
        assertTrue(template.contains("# [features]"))
        assertTrue(template.contains("check_for_update_on_startup = false"))
        assertTrue(CodexProfilePolicy.validate(template) == template)
    }

    @Test
    fun `legacy generated default upgrades without replacing user content`() {
        assertEquals(
            CodexProfileRepository.DEFAULT_CONTENT,
            CodexProfileRepository.upgradeLegacyDefault("check_for_update_on_startup = false\n"),
        )
        val custom = "model = \"custom-model\"\n"
        assertEquals(custom, CodexProfileRepository.upgradeLegacyDefault(custom))
    }

    @Test
    fun `previous guided template upgrades only when effective values remain untouched`() {
        val oldHeader = """
            # AgentDeck Codex 配置层
            # 无必填项：未设置的参数会继承 Runtime 中现有的 config.toml 和 Codex 默认值。
        """.trimIndent()
        val untouched = "$oldHeader\n# 旧版帮助内容\ncheck_for_update_on_startup = false\n"
        assertEquals(
            CodexProfileRepository.DEFAULT_CONTENT,
            CodexProfileRepository.upgradeLegacyDefault(untouched),
        )

        val customized = "$untouched\nmodel = \"custom-model\"\n"
        assertEquals(customized, CodexProfileRepository.upgradeLegacyDefault(customized))
    }

    @Test
    fun `valid profile is normalized and keeps provider metadata`() {
        val profile = """
            model = "gpt-5.6-sol"
            model_reasoning_effort = "high"

            [model_providers.gateway]
            name = "Gateway"
            base_url = "https://api.example.com/v1"
            wire_api = "responses"
            env_key = "OPENAI_API_KEY"
        """.trimIndent()

        val normalized = CodexProfilePolicy.validate(profile)

        assertTrue(normalized.endsWith("\n"))
        assertTrue(normalized.contains("model_providers.gateway"))
        assertTrue(normalized.contains("env_key = \"OPENAI_API_KEY\""))
    }

    @Test
    fun `validated profile becomes nested native session config`() {
        val snapshot = CodexProfileRuntimeConfig.fromValidatedToml(
            """
            model = "custom-model"
            model_provider = "gateway"
            model_reasoning_effort = "high"

            [mcp_servers.docs]
            url = "https://mcp.example.com/mcp"
            enabled = true

            [features]
            multi_agent = true
            """.trimIndent(),
        )
        val config = snapshot.sessionConfig(managedProvider = false)

        assertEquals("custom-model", snapshot.configuredModel)
        assertEquals("gateway", snapshot.configuredProvider)
        assertTrue(snapshot.usesCustomProvider)
        assertEquals("custom-model", config.getString("model"))
        assertEquals("gateway", config.getString("model_provider"))
        assertEquals(
            "https://mcp.example.com/mcp",
            config.getJSONObject("mcp_servers").getJSONObject("docs").getString("url"),
        )
        assertTrue(config.getJSONObject("features").getBoolean("multi_agent"))
    }

    @Test
    fun `managed provider owns model connection while keeping behavioral settings`() {
        val config = CodexProfileRuntimeConfig.fromValidatedToml(
            """
            model = "profile-model"
            model_provider = "profile-provider"
            model_reasoning_effort = "high"

            [model_providers.profile-provider]
            base_url = "https://api.example.com/v1"

            [features]
            shell_snapshot = true
            """.trimIndent(),
        ).sessionConfig(managedProvider = true)

        assertFalse(config.has("model"))
        assertFalse(config.has("model_provider"))
        assertFalse(config.has("model_providers"))
        assertEquals("high", config.getString("model_reasoning_effort"))
        assertTrue(config.getJSONObject("features").getBoolean("shell_snapshot"))
    }

    @Test
    fun `invalid toml and inline credentials are rejected`() {
        assertTrue(runCatching { CodexProfilePolicy.validate("model = \"unterminated\n") }.isFailure)
        assertTrue(
            runCatching {
                CodexProfilePolicy.validate(
                    """
                    [model_providers.gateway]
                    experimental_bearer_token = "sk-secret"
                    """.trimIndent(),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                CodexProfilePolicy.validate(
                    """
                    [mcp_servers.private.http_headers]
                    Authorization = "Bearer secret"
                    """.trimIndent(),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                CodexProfilePolicy.validate(
                    """
                    [mcp_servers.private.env]
                    SERVICE_TOKEN = "secret"
                    """.trimIndent(),
                )
            }.isFailure,
        )
        assertTrue(
            CodexProfilePolicy.validate(
                """
                [mcp_servers.private]
                bearer_token_env_var = "SERVICE_TOKEN"
                """.trimIndent(),
                allowUnmanagedMcp = true,
            ).contains("bearer_token_env_var"),
        )
    }

    @Test
    fun `secure profile rejects unmanaged mcp while lab may keep credential-free config`() {
        val mcp = """
            [mcp_servers.docs]
            url = "https://mcp.example.com/mcp"
            enabled = true
        """.trimIndent()

        val secure = runCatching { CodexProfilePolicy.validate(mcp) }
        assertTrue(secure.isFailure)
        assertTrue(secure.exceptionOrNull()?.message.orEmpty().contains("设置 > 扩展"))

        val lab = CodexProfilePolicy.validate(mcp, allowUnmanagedMcp = true)
        assertTrue(lab.contains("mcp_servers.docs"))
    }

}
