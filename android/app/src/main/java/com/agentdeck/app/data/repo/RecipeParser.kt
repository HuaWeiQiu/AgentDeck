package com.agentdeck.app.data.repo

import com.agentdeck.app.domain.model.AgentRecipe
import com.agentdeck.app.domain.model.RecipeCommand
import com.agentdeck.app.domain.model.RecipeRuntime
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream

object RecipeParser {
    fun parse(input: InputStream, sourceName: String): AgentRecipe {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            codePointLimit = MAX_RECIPE_LENGTH
            nestingDepthLimit = MAX_NESTING_DEPTH
        }
        val root = Yaml(SafeConstructor(options)).load<Any?>(input).asStringMap(sourceName)
        root.requireOnlyKeys(ROOT_KEYS, sourceName)

        val schemaVersion = root.requiredInt("schema_version", sourceName)
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "$sourceName 使用不支持的 schema_version: $schemaVersion"
        }
        val id = root.requiredString("id", sourceName)
        require(id.matches(ID_PATTERN)) { "$sourceName 的 id 无效: $id" }
        val priority = root.requiredString("priority", sourceName)
        require(priority.matches(PRIORITY_PATTERN)) { "$sourceName 的 priority 无效: $priority" }
        val version = root.requiredString("version", sourceName)
        require(version.length <= MAX_VERSION_LENGTH && '\n' !in version) {
            "$sourceName 的 version 无效"
        }
        val available = root.requiredBoolean("available", sourceName)
        val dependsOn = root.optionalStringList("depends_on", sourceName)
        require(dependsOn.distinct().size == dependsOn.size) { "$sourceName 包含重复依赖" }
        dependsOn.forEach { dependency ->
            require(dependency.matches(ID_PATTERN)) { "$sourceName 的依赖 ID 无效: $dependency" }
            require(dependency != id) { "$sourceName 不能依赖自身" }
        }

        val install = root.optionalCommand("install", sourceName)
        val verify = root.optionalCommand("verify", sourceName)
        val wrapperAsset = root.optionalString("wrapper_asset", sourceName)?.also { asset ->
            require(asset.matches(ASSET_PATTERN)) { "$sourceName 的 wrapper_asset 无效" }
        }
        if (available) {
            requireNotNull(install) { "$sourceName 可安装但缺少 install" }
            requireNotNull(verify) { "$sourceName 可安装但缺少 verify" }
        } else {
            require(install == null && verify == null && wrapperAsset == null) {
                "$sourceName 不可安装时不能声明 install、verify 或 wrapper_asset"
            }
        }

        return AgentRecipe(
            schemaVersion = schemaVersion,
            id = id,
            name = root.requiredString("name", sourceName),
            description = root.requiredString("description", sourceName),
            priority = priority,
            version = version,
            available = available,
            dependsOn = dependsOn,
            timeoutMinutes = root.requiredInt("timeout_minutes", sourceName).also { timeout ->
                require(timeout in MIN_TIMEOUT_MINUTES..MAX_TIMEOUT_MINUTES) {
                    "$sourceName 的 timeout_minutes 必须在 $MIN_TIMEOUT_MINUTES..$MAX_TIMEOUT_MINUTES"
                }
            },
            install = install,
            verify = verify,
            wrapperAsset = wrapperAsset,
        )
    }

    private fun Any?.asStringMap(sourceName: String): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: error("$sourceName 顶层必须是对象")
        return raw.entries.associate { (key, value) ->
            val stringKey = key as? String ?: error("$sourceName 包含非字符串字段名")
            stringKey to value
        }
    }

    private fun Map<String, Any?>.requireOnlyKeys(allowed: Set<String>, context: String) {
        val unknown = keys - allowed
        require(unknown.isEmpty()) { "$context 包含未知字段: ${unknown.sorted().joinToString()}" }
    }

    private fun Map<String, Any?>.requiredString(key: String, context: String): String {
        return (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("$context 缺少字符串字段 $key")
    }

    private fun Map<String, Any?>.optionalString(key: String, context: String): String? {
        val value = this[key] ?: return null
        return (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("$context 的 $key 必须是非空字符串")
    }

    private fun Map<String, Any?>.requiredInt(key: String, context: String): Int {
        val value = this[key]
        require(value is Byte || value is Short || value is Int || value is Long) {
            "$context 缺少整数型字段 $key"
        }
        val longValue = (value as Number).toLong()
        require(longValue in Int.MIN_VALUE..Int.MAX_VALUE) { "$context 的 $key 超出整数范围" }
        return longValue.toInt()
    }

    private fun Map<String, Any?>.requiredBoolean(key: String, context: String): Boolean {
        return this[key] as? Boolean ?: error("$context 缺少布尔型字段 $key")
    }

    private fun Map<String, Any?>.optionalStringList(key: String, context: String): List<String> {
        val values = this[key] ?: return emptyList()
        val list = values as? List<*> ?: error("$context 的 $key 必须是列表")
        return list.mapIndexed { index, value ->
            (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("$context 的 $key[$index] 必须是非空字符串")
        }
    }

    private fun Map<String, Any?>.optionalCommand(key: String, context: String): RecipeCommand? {
        val value = this[key] ?: return null
        val command = value.asStringMap("$context.$key")
        command.requireOnlyKeys(COMMAND_KEYS, "$context.$key")
        val runtime = when (command.requiredString("runtime", "$context.$key")) {
            "termux" -> RecipeRuntime.TERMUX
            else -> error("$context.$key 只支持 termux runtime")
        }
        val script = command.requiredString("script", "$context.$key")
        require(script.length <= MAX_SCRIPT_LENGTH) { "$context.$key 脚本过长" }
        return RecipeCommand(runtime = runtime, script = script)
    }

    private val ROOT_KEYS = setOf(
        "schema_version",
        "id",
        "name",
        "description",
        "priority",
        "version",
        "available",
        "depends_on",
        "timeout_minutes",
        "install",
        "verify",
        "wrapper_asset",
    )
    private val COMMAND_KEYS = setOf("runtime", "script")
    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val PRIORITY_PATTERN = Regex("p[0-9]")
    private val ASSET_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MIN_TIMEOUT_MINUTES = 1
    private const val MAX_TIMEOUT_MINUTES = 30
    private const val MAX_VERSION_LENGTH = 64
    private const val MAX_SCRIPT_LENGTH = 50_000
    private const val MAX_RECIPE_LENGTH = 100_000
    private const val MAX_NESTING_DEPTH = 12
}
