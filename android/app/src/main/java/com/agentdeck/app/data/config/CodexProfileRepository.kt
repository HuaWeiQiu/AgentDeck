package com.agentdeck.app.data.config

import android.content.Context
import android.system.Os
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CodexProfileSnapshot(
    val content: String,
    val updatedAtEpochMs: Long?,
    val isDefault: Boolean,
)

/** A validated, immutable snapshot used for one native Codex connection. */
class CodexProfileRuntimeConfig private constructor(
    private val encodedJson: String,
    val configuredModel: String?,
    val configuredProvider: String?,
) {
    val usesCustomProvider: Boolean
        get() = configuredProvider != null && configuredProvider != "openai"

    fun sessionConfig(managedProvider: Boolean): JSONObject = JSONObject(encodedJson).apply {
        if (managedProvider) {
            // The card's verified provider and credential broker own these values.
            remove("model")
            remove("model_provider")
            remove("model_providers")
        }
    }

    companion object {
        val EMPTY = CodexProfileRuntimeConfig("{}", null, null)

        internal fun fromValidatedToml(content: String): CodexProfileRuntimeConfig {
            val parsed = Toml.parse(content)
            check(!parsed.hasErrors()) { "Codex TOML 配置快照无效" }
            return CodexProfileRuntimeConfig(
                encodedJson = parsed.toJson(),
                configuredModel = parsed.getString("model"),
                configuredProvider = parsed.getString("model_provider"),
            )
        }
    }
}

fun interface CodexProfileSynchronizer {
    suspend fun synchronize(distro: String): Result<CodexProfileRuntimeConfig>

    companion object {
        val NONE = CodexProfileSynchronizer { Result.success(CodexProfileRuntimeConfig.EMPTY) }
    }
}

class CodexProfileRepository(
    context: Context,
    private val allowUnmanagedMcp: Boolean = false,
) : CodexProfileSynchronizer {
    private val paths = EmbeddedRuntimePaths.shared(context)
    private val mutex = Mutex()
    private val profileFile = File(paths.codexHome, PROFILE_FILE_NAME)

    suspend fun load(): Result<CodexProfileSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                paths.ensureHostLayout()
                snapshot(readOrCreateProfile())
            }
        }
    }

    suspend fun save(content: String): Result<CodexProfileSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = CodexProfilePolicy.validate(content, allowUnmanagedMcp)
            mutex.withLock {
                paths.ensureHostLayout()
                writeAtomically(normalized)
                snapshot(normalized)
            }
        }
    }

    suspend fun reset(): Result<CodexProfileSnapshot> = save(DEFAULT_CONTENT)

    override suspend fun synchronize(
        distro: String,
    ): Result<CodexProfileRuntimeConfig> = withContext(Dispatchers.IO) {
        runCatching {
            require(DISTRO_PATTERN.matches(distro)) { "Codex Runtime 名称无效" }
            mutex.withLock {
                paths.ensureHostLayout()
                val content = CodexProfilePolicy.validate(
                    readOrCreateProfile(),
                    allowUnmanagedMcp,
                )
                Os.chmod(profileFile.absolutePath, 0b110000000)
                CodexProfileRuntimeConfig.fromValidatedToml(content)
            }
        }
    }

    private fun snapshot(content: String) = CodexProfileSnapshot(
        content = content,
        updatedAtEpochMs = profileFile.takeIf(File::isFile)?.lastModified(),
        isDefault = content == DEFAULT_CONTENT,
    )

    private fun readOrCreateProfile(): String {
        if (!profileFile.isFile) {
            writeAtomically(DEFAULT_CONTENT)
            return DEFAULT_CONTENT
        }
        val content = profileFile.readText(StandardCharsets.UTF_8)
        val upgraded = upgradeLegacyDefault(content)
        if (upgraded != content) {
            writeAtomically(upgraded)
        }
        return upgraded
    }

    private fun writeAtomically(content: String) {
        check(paths.codexHome.mkdirs() || paths.codexHome.isDirectory) {
            "无法创建 Codex 配置目录"
        }
        val temporary = File(paths.codexHome, ".$PROFILE_FILE_NAME.tmp")
        temporary.writeText(content, StandardCharsets.UTF_8)
        Os.chmod(temporary.absolutePath, 0b110000000)
        try {
            Files.move(
                temporary.toPath(),
                profileFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                profileFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        Os.chmod(profileFile.absolutePath, 0b110000000)
    }

    companion object {
        const val PROFILE_NAME = "agentdeck"
        const val PROFILE_FILE_NAME = "$PROFILE_NAME.config.toml"
        private const val LEGACY_DEFAULT_CONTENT = "check_for_update_on_startup = false\n"
        private val PREVIOUS_GENERATED_TEMPLATE_PREFIX = """
            # AgentDeck Codex 配置层
            # 无必填项：未设置的参数会继承 Runtime 中现有的 config.toml 和 Codex 默认值。
        """.trimIndent()
        val DEFAULT_CONTENT = """
            # AgentDeck Codex 配置层
            # AgentDeck 模板版本：2
            # 无必填项：全部保持注释也能运行，未设置的参数使用 Codex 默认值。
            # 去掉可选项行首的 # 即可启用；保存后会在下一次会话启动前自动同步。
            # 此文件只保存运行参数，不保存登录授权：
            # - ChatGPT / OpenAI API 登录由 Codex 保存在同一内嵌环境的 auth.json。
            # - 第三方服务 API Key 由 AgentDeck 保存在 Android Keystore。
            # 不要在这里粘贴 API Key、Token 或密码。

            # AgentDeck 统一管理 Codex 更新，保留此项即可。
            check_for_update_on_startup = false

            # --- 常用可选项 ---
            # 模型 ID 也可以直接在聊天页选择；留空时使用当前 Provider 的默认模型。
            # model = "gpt-5.6"
            # model_provider = "openai"

            # 推理强度：minimal | low | medium | high | xhigh（取决于模型支持）。
            # model_reasoning_effort = "medium"
            # 推理摘要：auto | concise | detailed | none。
            # model_reasoning_summary = "auto"
            # 回答详细度：low | medium | high。
            # model_verbosity = "medium"
            # 表达风格：none | friendly | pragmatic。
            # personality = "pragmatic"
            # 联网搜索：disabled | cached | indexed | live。
            # web_search = "cached"
            # 注入到所有对话的全局附加说明；单个对话的角色身份请在“编辑对话”中设置。
            # developer_instructions = "优先运行测试，并用中文总结结果。"

            # AgentDeck 原生聊天以界面中的权限选择为准；直接运行 CLI 时可使用以下参数。
            # approval_policy = "on-request" # untrusted | on-request | never
            # sandbox_mode = "workspace-write" # read-only | workspace-write | danger-full-access

            # --- workspace-write 沙箱示例 ---
            # [sandbox_workspace_write]
            # network_access = true
            # writable_roots = ["/root/projects/shared"]

            # --- HTTP MCP 示例（仅 AgentDeck Lab）---
            # 安全版请从“设置 > 扩展”添加，凭据会进入 Android Keystore。
            # [mcp_servers.example]
            # url = "https://mcp.example.com/mcp"
            # enabled = true
            # required = false
            # bearer_token_env_var = "EXAMPLE_MCP_TOKEN"
            # startup_timeout_sec = 10
            # tool_timeout_sec = 60

            # --- 自定义 Responses Provider 示例 ---
            # 推荐优先使用 AgentDeck“模型服务”；以下仅适用于 Runtime 已提供环境变量的场景。
            # 启用此示例时，还需把上方 model/model_provider 改为实际值，例如：
            # model = "example-model-id"
            # model_provider = "example"
            # [model_providers.example]
            # name = "Example Responses"
            # base_url = "https://api.example.com/v1"
            # wire_api = "responses"
            # env_key = "EXAMPLE_API_KEY"

            # --- 稳定功能开关示例 ---
            # [features]
            # multi_agent = true
            # shell_snapshot = true
            # unified_exec = true
        """.trimIndent() + "\n"

        internal fun upgradeLegacyDefault(content: String): String = when {
            content == LEGACY_DEFAULT_CONTENT -> DEFAULT_CONTENT
            content.startsWith(PREVIOUS_GENERATED_TEMPLATE_PREFIX) &&
                hasSameEffectiveValues(content, DEFAULT_CONTENT) -> DEFAULT_CONTENT
            else -> content
        }

        private fun hasSameEffectiveValues(first: String, second: String): Boolean = runCatching {
            val firstResult = Toml.parse(first)
            val secondResult = Toml.parse(second)
            !firstResult.hasErrors() &&
                !secondResult.hasErrors() &&
                firstResult.toJson() == secondResult.toJson()
        }.getOrDefault(false)

        private val DISTRO_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

internal object CodexProfilePolicy {
    private const val MAX_PROFILE_BYTES = 128 * 1024
    private val blockedKeys = setOf(
        "access_token",
        "access_key",
        "api_key",
        "authorization",
        "bearer_token",
        "client_secret",
        "experimental_bearer_token",
        "http_headers",
        "openai_api_key",
        "password",
        "refresh_token",
        "secret",
        "secret_access_key",
        "token",
    )

    fun validate(content: String, allowUnmanagedMcp: Boolean = false): String {
        require('\u0000' !in content) { "Codex 配置包含无效字符" }
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        require(normalized.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROFILE_BYTES) {
            "Codex 配置不能超过 128 KB"
        }
        val parsed = Toml.parse(normalized)
        require(!parsed.hasErrors()) {
            parsed.errors().firstOrNull()?.toString()?.take(240) ?: "Codex TOML 格式无效"
        }
        rejectInlineSecrets(parsed, emptyList())
        require(allowUnmanagedMcp || parsed.getTable("mcp_servers")?.isEmpty != false) {
            "安全版不允许在 Codex 配置中直接声明 MCP；请从“设置 > 扩展”添加"
        }
        return if (normalized.isEmpty() || normalized.endsWith('\n')) normalized else "$normalized\n"
    }

    private fun rejectInlineSecrets(value: Any?, path: List<String>) {
        when (value) {
            is TomlTable -> value.entrySet().forEach { (key, child) ->
                val normalizedKey = key.lowercase().replace('-', '_')
                val blocked = normalizedKey in blockedKeys ||
                    normalizedKey.endsWith("_api_key") ||
                    normalizedKey.endsWith("_access_key") ||
                    normalizedKey.endsWith("_access_key_id") ||
                    normalizedKey.endsWith("_access_token") ||
                    normalizedKey.endsWith("_refresh_token") ||
                    normalizedKey.endsWith("_token") ||
                    normalizedKey.endsWith("_secret") ||
                    normalizedKey.endsWith("_password")
                require(!blocked || child.isEmptyValue()) {
                    "配置项 ${(path + key).joinToString(".")} 不能保存明文凭据；请使用模型服务"
                }
                rejectInlineSecrets(child, path + key)
            }
            is TomlArray -> (0 until value.size()).forEach { index ->
                rejectInlineSecrets(value[index], path)
            }
            is Map<*, *> -> value.forEach { (rawKey, child) ->
                val key = rawKey?.toString().orEmpty()
                rejectInlineSecrets(child, path + key)
            }
            is Iterable<*> -> value.forEach { child -> rejectInlineSecrets(child, path) }
            is Array<*> -> value.forEach { child -> rejectInlineSecrets(child, path) }
        }
    }

    private fun Any?.isEmptyValue(): Boolean = when (this) {
        null -> true
        is String -> isBlank()
        is TomlTable -> isEmpty
        is TomlArray -> isEmpty
        is Map<*, *> -> isEmpty()
        is Collection<*> -> isEmpty()
        else -> false
    }
}
