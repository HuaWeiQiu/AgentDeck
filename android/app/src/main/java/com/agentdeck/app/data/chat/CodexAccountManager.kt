package com.agentdeck.app.data.chat

import com.agentdeck.app.BuildConfig
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

enum class CodexAccountType {
    CHATGPT,
    API_KEY,
    OTHER,
}

data class CodexAccount(
    val type: CodexAccountType,
    val email: String? = null,
    val planType: String? = null,
    val rawType: String,
)

data class CodexAccountSnapshot(
    val account: CodexAccount?,
    val requiresOpenAiAuth: Boolean,
)

data class CodexDeviceLogin(
    val loginId: String,
    val verificationUrl: String,
    val userCode: String,
)

data class CodexLoginCompletion(
    val success: Boolean,
    val error: String?,
)

internal object CodexAccountProtocol {
    fun accountReadParams(refreshToken: Boolean = false): JSONObject =
        JSONObject().put("refreshToken", refreshToken)

    fun apiKeyLoginParams(apiKey: String): JSONObject = JSONObject()
        .put("type", "apiKey")
        .put("apiKey", apiKey)

    fun deviceCodeLoginParams(): JSONObject =
        JSONObject().put("type", "chatgptDeviceCode")

    fun parseAccountRead(result: JSONObject): CodexAccountSnapshot {
        val raw = result.optJSONObject("account")
        val account = raw?.let { value ->
            val rawType = value.optString("type").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Codex 账号响应缺少类型")
            CodexAccount(
                type = when (rawType) {
                    "chatgpt" -> CodexAccountType.CHATGPT
                    "apiKey" -> CodexAccountType.API_KEY
                    else -> CodexAccountType.OTHER
                },
                email = value.optionalString("email"),
                planType = value.optionalString("planType"),
                rawType = rawType,
            )
        }
        return CodexAccountSnapshot(
            account = account,
            requiresOpenAiAuth = result.optBoolean("requiresOpenaiAuth", true),
        )
    }

    fun parseDeviceLogin(result: JSONObject): CodexDeviceLogin {
        val type = result.optString("type")
        require(type == "chatgptDeviceCode") { "Codex 返回了不支持的登录方式" }
        val loginId = result.requiredString("loginId", "Codex 登录响应缺少 loginId")
        val verificationUrl = result.requiredString(
            "verificationUrl",
            "Codex 登录响应缺少验证地址",
        )
        val verificationUri = runCatching { URI(verificationUrl) }.getOrNull()
        require(
            verificationUri?.scheme.equals("https", ignoreCase = true) &&
                !verificationUri?.host.isNullOrBlank() &&
                verificationUri?.userInfo == null,
        ) { "Codex 登录验证地址不安全" }
        val userCode = result.requiredString("userCode", "Codex 登录响应缺少用户代码")
        return CodexDeviceLogin(loginId, verificationUrl, userCode)
    }

    fun parseLoginCompletion(
        params: JSONObject,
        expectedLoginId: String,
    ): CodexLoginCompletion? {
        if (params.optionalString("loginId") != expectedLoginId) return null
        return CodexLoginCompletion(
            success = params.optBoolean("success", false),
            error = params.optionalString("error"),
        )
    }

    private fun JSONObject.requiredString(key: String, message: String): String =
        optionalString(key) ?: throw IllegalStateException(message)

    private fun JSONObject.optionalString(key: String): String? =
        opt(key)?.takeUnless { it == JSONObject.NULL }
            ?.let { it as? String }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
}

/**
 * Owns short-lived app-server processes used only for account operations. Codex
 * persists successful login state under the embedded CODEX_HOME; AgentDeck never
 * copies account tokens into Room, SharedPreferences, or its TOML profile.
 */
class CodexAccountManager(
    private val bridge: CodexBridgeLauncher,
) {
    suspend fun readAccount(): Result<CodexAccountSnapshot> = accountResult {
        openSession().use { session -> session.readAccount() }
    }

    suspend fun loginWithApiKey(apiKey: String): Result<CodexAccountSnapshot> = accountResult {
        require(apiKey.isNotBlank()) { "请输入 OpenAI API Key" }
        openSession().use { session ->
            session.client.request(
                "account/login/start",
                CodexAccountProtocol.apiKeyLoginParams(apiKey),
            )
            session.readAccount()
        }
    }

    suspend fun startDeviceLogin(): Result<DeviceLoginSession> = accountResult {
        val session = openSession()
        try {
            val result = session.client.request(
                "account/login/start",
                CodexAccountProtocol.deviceCodeLoginParams(),
            )
            DeviceLoginSession(session, CodexAccountProtocol.parseDeviceLogin(result))
        } catch (error: Exception) {
            session.close()
            throw error
        }
    }

    suspend fun logout(): Result<CodexAccountSnapshot> = accountResult {
        openSession().use { session ->
            session.client.request("account/logout")
            session.readAccount()
        }
    }

    private suspend fun openSession(): AccountSession {
        val endpoint = bridge.launchForAccount(ACCOUNT_CARD).getOrThrow()
        return try {
            val client = CodexRpcClient.connect(endpoint)
            client.initialize(BuildConfig.VERSION_NAME)
            AccountSession(client, endpoint, bridge)
        } catch (error: Exception) {
            bridge.stop(endpoint)
            throw error
        }
    }

    private suspend fun <T> accountResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    class DeviceLoginSession internal constructor(
        private val session: AccountSession,
        val login: CodexDeviceLogin,
    ) : AutoCloseable {
        val events: Flow<CodexInbound>
            get() = session.client.events

        suspend fun readAccount(): CodexAccountSnapshot = session.readAccount()

        suspend fun cancel() {
            session.client.request(
                "account/login/cancel",
                JSONObject().put("loginId", login.loginId),
            )
        }

        override fun close() = session.close()
    }

    internal class AccountSession(
        val client: CodexRpcClient,
        private val endpoint: CodexBridgeEndpoint,
        private val bridge: CodexBridgeLauncher,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        suspend fun readAccount(): CodexAccountSnapshot = CodexAccountProtocol.parseAccountRead(
            client.request("account/read", CodexAccountProtocol.accountReadParams()),
        )

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            client.close()
            bridge.stop(endpoint)
        }
    }

    companion object {
        private val ACCOUNT_CARD = AgentCard(
            id = "card_codex_account_setup",
            name = "Codex Account",
            icon = "codex",
            recipeId = "recipe_codex",
            templateId = "tpl_codex_ubuntu",
            profileId = null,
            termuxSessionName = "agentdeck-codex-account",
            workspaceNamespace = PathNamespace.UBUNTU,
            workspacePath = "/root/projects/default",
            distro = "ubuntu",
            innerBin = "codex",
        )
    }
}
