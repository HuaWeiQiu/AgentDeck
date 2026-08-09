package com.agentdeck.app.data.provider

import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.secure.ProviderCredentialVault
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.profiles.ProviderEndpointNormalizer
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeProgram
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class ImportedCodexProvider(
    val profile: ProviderProfile,
    val modelId: String,
)

class ExistingCodexProviderImporter(
    private val termuxRuntime: AgentRuntime,
    private val profiles: ProfileRepository,
    private val cards: CardRepository,
    private val credentials: ProviderCredentialVault,
    private val discovery: ProviderModelDiscovery,
) {
    suspend fun importCurrent(): Result<ImportedCodexProvider> = runCatching {
        require(termuxRuntime.status().ready) {
            "未检测到可读取的 Termux 兼容环境"
        }
        val result = termuxRuntime.runCommandForResult(
            RuntimeCommand(
                instanceId = "agentdeck-import-codex-provider",
                program = RuntimeProgram.HOST_SHELL,
                script = ExistingCodexProviderExport.command(),
                background = true,
                reuseExistingInstance = false,
            ),
            IMPORT_TIMEOUT_MILLIS,
        ).getOrThrow()
        check(result.commandSucceeded) {
            result.stderr.ifBlank { result.stdout }.trim().takeLast(240)
                .ifBlank { "无法读取现有 Codex 配置" }
        }
        check(!result.outputWasTruncated) { "现有 Codex 配置读取结果被截断" }
        val imported = ExistingCodexProviderExport.parse(result.stdout)
        val endpoint = ProviderEndpointNormalizer.normalize(imported.baseUrl).getOrThrow()
        val existing = profiles.getProfileByBaseUrl(endpoint.apiBaseUrl)
        val credentialRef = existing?.credentialRef ?: credentialRef(endpoint.apiBaseUrl)
        val defaultCard = requireNotNull(cards.getCard(DEFAULT_CODEX_CARD_ID)) {
            "默认 Codex 对话不存在"
        }
        val previous = existing?.credentialRef?.let(credentials::load)
        var credentialSaved = false
        try {
            credentials.save(credentialRef, imported.apiKey)
            credentialSaved = true
            val checkedAt = System.currentTimeMillis()
            val preview = ProviderProfile(
                id = existing?.id ?: "preview_import",
                name = imported.name,
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = endpoint.apiBaseUrl,
                defaultModel = imported.model,
                adapterId = ProviderAdapterId.OPENAI_RESPONSES,
                credentialRef = credentialRef,
            )
            val discovered = try {
                discovery.discover(preview, imported.apiKey, checkedAt)
            } catch (error: ProviderDiscoveryException) {
                if (error.status != ProviderConnectionStatus.DISCOVERY_UNSUPPORTED) throw error
                emptyList()
            }
            val models = discovered.ensureModel(imported.model, checkedAt)
            val status = if (discovered.isEmpty()) {
                ProviderConnectionStatus.DISCOVERY_UNSUPPORTED
            } else {
                ProviderConnectionStatus.READY
            }
            val profile = profiles.saveProfileAndModels(
                existingId = existing?.id,
                name = imported.name,
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = endpoint.apiBaseUrl,
                defaultModel = imported.model,
                adapterId = ProviderAdapterId.OPENAI_RESPONSES,
                credentialRef = credentialRef,
                connectionStatus = status,
                lastCheckedAtEpochMs = checkedAt,
                models = models,
            )
            cards.saveCard(
                defaultCard.copy(
                    profileId = profile.id,
                    modelId = imported.model,
                ),
            )
            ImportedCodexProvider(profile, imported.model)
        } catch (error: Exception) {
            if (credentialSaved) {
                runCatching {
                    if (previous != null) {
                        credentials.save(credentialRef, previous)
                    } else {
                        credentials.delete(credentialRef)
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        } finally {
            previous?.fill(0)
            imported.apiKey.fill(0)
        }
    }

    private fun List<ProviderModel>.ensureModel(
        modelId: String,
        discoveredAtEpochMs: Long,
    ): List<ProviderModel> = if (any { it.id == modelId }) {
        this
    } else {
        this + ProviderModel(
            providerId = firstOrNull()?.providerId ?: "preview_import",
            id = modelId,
            displayName = modelId,
            discoveredAtEpochMs = discoveredAtEpochMs,
        )
    }

    private fun credentialRef(baseUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(baseUrl.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "cred_import_$digest"
    }

    companion object {
        private const val DEFAULT_CODEX_CARD_ID = "card_codex_default"
        private const val IMPORT_TIMEOUT_MILLIS = 30_000L
    }
}

internal data class ExistingCodexProviderConfig(
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: ByteArray,
)

internal object ExistingCodexProviderExport {
    private const val MARKER = "AGENTDECK_PROVIDER_V1\t"
    private const val MAX_PAYLOAD_BYTES = 16 * 1_024

    fun command(): String {
        val program = Base64.getEncoder().encodeToString(PYTHON_PROGRAM.toByteArray(StandardCharsets.UTF_8))
        return """
            set -e
            command -v proot-distro >/dev/null 2>&1 || {
              printf '%s\n' 'proot-distro 未安装' >&2
              exit 20
            }
            timeout --kill-after=2s 20s proot-distro login ubuntu -- \
              /usr/bin/python3 -c 'import base64;exec(base64.b64decode("$program"))'
        """.trimIndent()
    }

    fun parse(output: String): ExistingCodexProviderConfig {
        val encoded = output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith(MARKER) }
            ?.removePrefix(MARKER)
            ?: error("现有 Codex 配置中没有可导入的模型服务")
        require(encoded.length in 4..MAX_PAYLOAD_BYTES * 2) { "现有 Codex 配置过大" }
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("现有 Codex 配置格式无效", it) }
        require(bytes.size in 2..MAX_PAYLOAD_BYTES) { "现有 Codex 配置大小无效" }
        return try {
            val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
            require(json.optInt("version") == 1) { "现有 Codex 配置版本不受支持" }
            val providerId = json.requireSafeText("provider", 80)
            require(providerId != "openai") { "当前是 ChatGPT 登录，不是可导入的 CLI Provider" }
            val wireApi = json.optString("wire_api", "responses").trim().lowercase()
            require(wireApi == "responses") { "当前 CLI Provider 不支持 Codex Responses" }
            val name = json.optString("name").trim().takeIf(String::isNotBlank) ?: providerId
            val baseUrl = json.requireSafeText("base_url", 2_048)
            val model = json.requireSafeText("model", 160)
            val key = json.requireSafeText("api_key", 8 * 1_024)
                .toByteArray(StandardCharsets.UTF_8)
            ExistingCodexProviderConfig(name.take(120), baseUrl, model, key)
        } finally {
            bytes.fill(0)
        }
    }

    private fun JSONObject.requireSafeText(key: String, maxLength: Int): String {
        val value = optString(key).trim()
        require(value.isNotEmpty() && value.length <= maxLength && value.none(Char::isISOControl)) {
            "现有 Codex 配置缺少有效的 $key"
        }
        return value
    }

    private val PYTHON_PROGRAM = """
        import base64
        import json
        import os
        import pathlib
        import tomllib

        home = pathlib.Path(os.environ.get("CODEX_HOME", pathlib.Path.home() / ".codex"))
        path = home / "config.toml"
        if not path.is_file() or path.stat().st_size > 262144:
            raise SystemExit("未找到可读取的 Codex config.toml")
        data = tomllib.loads(path.read_text(encoding="utf-8"))
        provider_id = str(data.get("model_provider", "openai")).strip()
        providers = data.get("model_providers", {})
        info = providers.get(provider_id, {}) if isinstance(providers, dict) else {}
        if not isinstance(info, dict):
            info = {}
        env_key = info.get("env_key")
        api_key = info.get("experimental_bearer_token") or data.get("experimental_bearer_token")
        if not api_key and isinstance(env_key, str):
            api_key = os.environ.get(env_key)
        payload = {
            "version": 1,
            "provider": provider_id,
            "name": str(info.get("name") or provider_id),
            "base_url": str(info.get("base_url") or ""),
            "model": str(data.get("model") or info.get("model") or ""),
            "wire_api": str(info.get("wire_api") or "responses"),
            "api_key": str(api_key or ""),
        }
        raw = json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        if len(raw) > 16384:
            raise SystemExit("Codex Provider 配置过大")
        print("AGENTDECK_PROVIDER_V1\t" + base64.b64encode(raw).decode("ascii"))
    """.trimIndent()
}
