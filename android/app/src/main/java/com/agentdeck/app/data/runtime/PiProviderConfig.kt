package com.agentdeck.app.data.runtime

import com.agentdeck.app.data.secure.ProviderCredentialVault
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Projects an AgentDeck **Chat Completions** [ProviderProfile] into pi-home
 * (`~/.pi/agent/models.json` + settings). API key stays in the shared vault
 * and is injected as env [ENV_API_KEY] when starting `pi --mode rpc`.
 */
internal object PiProviderConfig {
    const val PROVIDER_ID = "agentdeck"
    const val ENV_API_KEY = "AGENTDECK_PI_API_KEY"

    data class Applied(
        val baseUrl: String,
        val modelId: String,
        val profileName: String,
        val credentialRef: String,
    )

    fun agentDir(piHome: File): File = File(piHome, ".pi/agent")

    fun modelsFile(piHome: File): File = File(agentDir(piHome), "models.json")

    fun settingsFile(piHome: File): File = File(agentDir(piHome), "settings.json")

    fun ensureLayout(piHome: File) {
        val dir = agentDir(piHome)
        check(dir.mkdirs() || dir.isDirectory) { "无法创建 pi agent 配置目录" }
    }

    /**
     * Write models.json / settings from a verified chat profile.
     * Returns the applied binding for process env + CLI flags.
     */
    fun applyProfile(
        piHome: File,
        profile: ProviderProfile,
        modelId: String,
        vault: ProviderCredentialVault,
    ): Applied {
        require(profile.adapterId.isChatCompletionsCompatible()) {
            "pi 只能使用「Chat Completions」模型服务"
        }
        val credentialRef = requireNotNull(profile.credentialRef) { "模型服务缺少 API Key" }
        require(vault.contains(credentialRef)) { "模型服务的 API Key 不存在" }
        val base = profile.baseUrl.trim().trimEnd('/')
        val model = modelId.trim().ifBlank { profile.defaultModel }.ifBlank {
            error("请选择模型")
        }
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Base URL 必须以 http(s):// 开头"
        }
        require(model.matches(Regex("[A-Za-z0-9._:/-]{1,160}"))) { "模型 ID 无效" }

        ensureLayout(piHome)
        writeModelsJson(piHome, base, model, profile.name)
        writeSettingsJson(piHome, model)
        return Applied(
            baseUrl = base,
            modelId = model,
            profileName = profile.name,
            credentialRef = credentialRef,
        )
    }

    fun loadApiKey(vault: ProviderCredentialVault, credentialRef: String): String {
        val bytes = vault.load(credentialRef)
            ?: error("模型服务的 API Key 不存在")
        return try {
            bytes.toString(Charsets.UTF_8).trim().also {
                require(it.isNotEmpty()) { "API Key 为空" }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeModelsJson(piHome: File, baseUrl: String, modelId: String, profileName: String) {
        val model = JSONObject()
            .put("id", modelId)
            .put("name", "$profileName · $modelId")
            .put("reasoning", false)
            .put("input", JSONArray().put("text"))
            .put("contextWindow", 32_768)
            .put("maxTokens", 4_096)
            .put(
                "cost",
                JSONObject()
                    .put("input", 0)
                    .put("output", 0)
                    .put("cacheRead", 0)
                    .put("cacheWrite", 0),
            )
        val provider = JSONObject()
            .put("baseUrl", baseUrl)
            .put("api", "openai-completions")
            .put("apiKey", "\$$ENV_API_KEY")
            .put("authHeader", true)
            .put(
                "compat",
                JSONObject()
                    .put("supportsDeveloperRole", false)
                    .put("supportsReasoningEffort", false),
            )
            .put("models", JSONArray().put(model))
        val root = JSONObject()
            .put("providers", JSONObject().put(PROVIDER_ID, provider))
        NodeStartupSupport.writeTextIfChanged(modelsFile(piHome), root.toString(2) + "\n")
    }

    private fun writeSettingsJson(piHome: File, modelId: String) {
        val existing = runCatching {
            settingsFile(piHome).takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        }.getOrNull() ?: JSONObject()
        existing.put("defaultProvider", PROVIDER_ID)
        existing.put("defaultModel", modelId)
        if (!existing.has("enableInstallTelemetry")) {
            existing.put("enableInstallTelemetry", false)
        }
        NodeStartupSupport.writeTextIfChanged(settingsFile(piHome), existing.toString(2) + "\n")
    }
}
