package com.agentdeck.app.data.chat

import android.annotation.SuppressLint
import com.agentdeck.app.data.config.CodexProfileRuntimeConfig
import com.agentdeck.app.data.config.CodexProfileSynchronizer
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.extensions.ExtensionSessionPlan
import com.agentdeck.app.domain.profiles.ProviderEndpointNormalizer
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeProgram
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CodexBridgeEndpoint(
    val port: Int,
    val token: String,
    val instanceKey: String,
    val credentialToken: String? = null,
    val profileConfig: CodexProfileRuntimeConfig = CodexProfileRuntimeConfig.EMPTY,
)

data class ManagedProviderRuntime(
    val providerId: String,
    val baseUrl: String,
    val modelId: String,
    val credentialRef: String,
    val credentialBrokerPort: Int,
) {
    val conversationKey: String = "$providerId:$modelId"

    companion object {
        fun from(
            profile: ProviderProfile,
            modelId: String,
            credentialBrokerPort: Int,
        ): ManagedProviderRuntime {
            require(
                profile.type == ProviderType.OPENAI_COMPATIBLE &&
                    (profile.adapterId == ProviderAdapterId.SUB2API ||
                        profile.adapterId == ProviderAdapterId.OPENAI_RESPONSES),
            ) { "该模型服务不支持 Codex Responses" }
            val endpoint = ProviderEndpointNormalizer.normalize(profile.baseUrl).getOrThrow()
            val credentialRef = requireNotNull(profile.credentialRef) { "模型服务缺少 API Key" }
            require(modelId.isNotBlank() && modelId.length <= 160 && modelId.none(Char::isISOControl)) {
                "模型 ID 无效"
            }
            require(credentialBrokerPort in 1..65_535) { "凭据代理端口无效" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(profile.id.toByteArray(StandardCharsets.UTF_8))
                .take(8)
                .joinToString("") { byte -> "%02x".format(byte) }
            return ManagedProviderRuntime(
                providerId = "agentdeck_$digest",
                baseUrl = endpoint.apiBaseUrl,
                modelId = modelId,
                credentialRef = credentialRef,
                credentialBrokerPort = credentialBrokerPort,
            )
        }
    }
}

interface CodexBridgeLaunch {
    suspend fun launch(
        card: AgentCard,
        runtime: ManagedProviderRuntime? = null,
        extensionPlan: ExtensionSessionPlan = ExtensionSessionPlan(),
    ): Result<CodexBridgeEndpoint>
    fun stop(endpoint: CodexBridgeEndpoint): Result<Unit>
}

@SuppressLint("SdCardPath")
class CodexBridgeLauncher(
    private val agentRuntime: AgentRuntime,
    private val profileSynchronizer: CodexProfileSynchronizer = CodexProfileSynchronizer.NONE,
) : CodexBridgeLaunch {
    override suspend fun launch(
        card: AgentCard,
        runtime: ManagedProviderRuntime?,
        extensionPlan: ExtensionSessionPlan,
    ): Result<CodexBridgeEndpoint> = launchInternal(
        card,
        runtime,
        extensionPlan,
        synchronizeProfile = true,
    )

    suspend fun launchForAccount(card: AgentCard): Result<CodexBridgeEndpoint> =
        launchInternal(
            card,
            runtime = null,
            extensionPlan = ExtensionSessionPlan(),
            synchronizeProfile = false,
        )

    private suspend fun launchInternal(
        card: AgentCard,
        runtime: ManagedProviderRuntime?,
        extensionPlan: ExtensionSessionPlan,
        synchronizeProfile: Boolean,
    ): Result<CodexBridgeEndpoint> = runCatching {
        require(card.recipeId == "recipe_codex") { "该对话不支持 Codex 原生聊天" }
        val profileConfig = if (synchronizeProfile) {
            profileSynchronizer.synchronize(card.distro).getOrThrow()
        } else {
            CodexProfileRuntimeConfig.EMPTY
        }
        val instanceKey = instanceKey(card.id)
        val args = mutableListOf(
            "--distro",
            card.distro,
            "--cwd",
            card.workspacePath,
            "--instance-key",
            instanceKey,
        )
        runtime?.let { managed ->
            args += listOf(
                "--provider-id",
                managed.providerId,
                "--base-url",
                managed.baseUrl,
                "--model",
                managed.modelId,
                "--credential-ref",
                managed.credentialRef,
                "--credential-broker-port",
                managed.credentialBrokerPort.toString(),
            )
        }
        extensionPlan.skillSnapshotKey?.let { key ->
            require(key == instanceKey) { "Skill 快照与会话不匹配" }
            args += listOf("--skill-snapshot-key", key)
        }
        val command = RuntimeCommand(
            instanceId = "agentdeck-chat-$instanceKey",
            program = RuntimeProgram.CODEX_APP_SERVER,
            args = args,
            background = true,
            reuseExistingInstance = false,
        )
        val result = agentRuntime.runCommandForResult(command, START_TIMEOUT_MILLIS).getOrThrow()
        if (!result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }
                .trim()
                .takeLast(240)
                .ifBlank { "app-server 未返回错误信息" }
            error("无法启动 Codex app-server（退出码 ${result.exitCode}）：$detail")
        }
        val payload = result.stdout.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .lastOrNull()
            ?: error("Codex app-server 未返回连接信息")
        parseEndpoint(payload, instanceKey, runtime != null, profileConfig)
    }

    override fun stop(endpoint: CodexBridgeEndpoint): Result<Unit> = agentRuntime.runCommand(
        RuntimeCommand(
            instanceId = "agentdeck-chat-stop-${endpoint.instanceKey}",
            program = RuntimeProgram.CODEX_APP_SERVER,
            args = listOf("--instance-key", endpoint.instanceKey, "--stop"),
            background = true,
            reuseExistingInstance = false,
        ),
    )

    companion object {
        private const val START_TIMEOUT_MILLIS = 30_000L

        internal fun parseEndpoint(
            payload: String,
            instanceKey: String,
            expectsCredentialToken: Boolean = false,
            profileConfig: CodexProfileRuntimeConfig = CodexProfileRuntimeConfig.EMPTY,
        ): CodexBridgeEndpoint {
            val json = JSONObject(payload)
            val port = json.getInt("port")
            val token = json.getString("token")
            require(port in 1..65_535) { "Codex app-server 返回了无效端口" }
            require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
                "Codex app-server 返回了无效凭据"
            }
            require(instanceKey.matches(Regex("[a-f0-9]{1,16}"))) {
                "Codex app-server 返回了无效实例标识"
            }
            val credentialToken = json.optString("credential_token")
                .takeIf(String::isNotBlank)
            require(!expectsCredentialToken || credentialToken?.matches(CREDENTIAL_TOKEN_PATTERN) == true) {
                "Codex app-server 未返回有效的凭据授权"
            }
            require(expectsCredentialToken || credentialToken == null) {
                "Codex app-server 返回了意外的凭据授权"
            }
            return CodexBridgeEndpoint(
                port = port,
                token = token,
                instanceKey = instanceKey,
                credentialToken = credentialToken,
                profileConfig = profileConfig,
            )
        }

        private val CREDENTIAL_TOKEN_PATTERN = Regex("[a-f0-9]{64}")

        internal fun instanceKey(cardId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(cardId.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
