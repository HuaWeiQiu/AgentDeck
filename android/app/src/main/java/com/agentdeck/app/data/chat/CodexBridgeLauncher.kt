package com.agentdeck.app.data.chat

import android.annotation.SuppressLint
import com.agentdeck.app.data.termux.TermuxCommand
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.model.AgentCard
import org.json.JSONObject

data class CodexBridgeEndpoint(
    val port: Int,
    val token: String,
    val instanceKey: String,
)

interface CodexBridgeLaunch {
    suspend fun launch(card: AgentCard): Result<CodexBridgeEndpoint>
    fun stop(endpoint: CodexBridgeEndpoint): Result<Unit>
}

@SuppressLint("SdCardPath")
class CodexBridgeLauncher(
    private val termux: TermuxGateway,
) : CodexBridgeLaunch {
    override suspend fun launch(card: AgentCard): Result<CodexBridgeEndpoint> = runCatching {
        require(card.recipeId == "recipe_codex") { "该对话不支持 Codex 原生聊天" }
        val instanceKey = card.id.hashCode().toUInt().toString(16)
        val command = TermuxCommand(
            sessionName = "agentdeck-chat-$instanceKey",
            executable = START_WRAPPER,
            args = listOf(
                "--distro",
                card.distro,
                "--cwd",
                card.workspacePath,
                "--instance-key",
                instanceKey,
            ),
            background = true,
            reuseExistingSession = false,
        )
        val result = termux.runCommandForResult(command, START_TIMEOUT_MILLIS).getOrThrow()
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
        parseEndpoint(payload, instanceKey)
    }

    override fun stop(endpoint: CodexBridgeEndpoint): Result<Unit> = termux.runCommand(
        TermuxCommand(
            sessionName = "agentdeck-chat-stop-${endpoint.instanceKey}",
            executable = START_WRAPPER,
            args = listOf("--instance-key", endpoint.instanceKey, "--stop"),
            background = true,
            reuseExistingSession = false,
        ),
    )

    companion object {
        private const val START_WRAPPER =
            "/data/data/com.termux/files/home/.agentdeck/wrappers/codex-app-server-start.sh"
        private const val START_TIMEOUT_MILLIS = 30_000L

        internal fun parseEndpoint(payload: String, instanceKey: String): CodexBridgeEndpoint {
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
            return CodexBridgeEndpoint(port, token, instanceKey)
        }
    }
}
