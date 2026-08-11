package com.agentdeck.app.data.extensions

import android.system.Os
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.data.runtime.deleteTreeWithoutFollowingLinks
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class InstalledSkillPackage(
    val name: String,
    val description: String,
    val path: String,
    val manifestHash: String,
)

internal class SkillPackageInstaller(
    private val paths: EmbeddedRuntimePaths,
) {
    fun install(extensionId: String, input: InputStream): InstalledSkillPackage {
        require(extensionId.matches(EXTENSION_ID_PATTERN)) { "Skill 扩展标识无效" }
        paths.ensureHostLayout()
        val bytes = readBounded(input)
        require(bytes.size in 1..MAX_SKILL_BYTES) { "SKILL.md 不能为空且不能超过 256 KB" }
        val content: String
        val metadata: SkillMetadata
        val digest: String
        try {
            content = decodeUtf8(bytes)
            metadata = parseMetadata(content)
            digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        } finally {
            bytes.fill(0)
        }

        val target = File(paths.extensionPackages, extensionId)
        val staging = File(paths.extensionPackages, ".$extensionId.staging")
        deleteTreeWithoutFollowingLinks(staging.toPath())
        check(staging.mkdirs()) { "无法创建 Skill 暂存目录" }
        try {
            val skillFile = File(staging, "SKILL.md")
            skillFile.writeText(content, StandardCharsets.UTF_8)
            Os.chmod(skillFile.absolutePath, 0b100000000)
            deleteTreeWithoutFollowingLinks(target.toPath())
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            deleteTreeWithoutFollowingLinks(staging.toPath())
        }
        return InstalledSkillPackage(
            name = metadata.name,
            description = metadata.description,
            path = target.absolutePath,
            manifestHash = digest,
        )
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (total <= MAX_SKILL_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_SKILL_BYTES + 1 - total))
            if (count < 0) break
            require(count != 0) { "读取 SKILL.md 时没有取得数据" }
            output.write(buffer, 0, count)
            total += count
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    fun delete(installedPath: String) {
        val target = File(installedPath).canonicalFile
        val root = paths.extensionPackages.canonicalFile
        require(target.parentFile == root && EXTENSION_ID_PATTERN.matches(target.name)) {
            "Skill 安装路径无效"
        }
        deleteTreeWithoutFollowingLinks(target.toPath())
    }

    fun pruneExcept(validInstalledPaths: Set<String>) {
        paths.ensureHostLayout()
        val root = paths.extensionPackages.canonicalFile
        val validNames = validInstalledPaths.mapTo(hashSetOf()) { installedPath ->
            val target = File(installedPath).canonicalFile
            require(target.parentFile == root && EXTENSION_ID_PATTERN.matches(target.name)) {
                "Skill 安装路径无效"
            }
            target.name
        }
        root.listFiles().orEmpty().forEach { candidate ->
            val isPackage = EXTENSION_ID_PATTERN.matches(candidate.name)
            val isStaging = candidate.name.matches(STAGING_NAME_PATTERN)
            if (isStaging || isPackage && candidate.name !in validNames) {
                deleteTreeWithoutFollowingLinks(candidate.toPath())
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .also { require('\u0000' !in it) { "SKILL.md 包含无效字符" } }

    private fun parseMetadata(content: String): SkillMetadata {
        val lines = content.lineSequence().toList()
        require(lines.firstOrNull()?.trim() == "---") { "SKILL.md 缺少 YAML 元数据" }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.let { index ->
            require(index >= 0) { "SKILL.md 元数据没有结束标记" }
            index + 1
        }
        val yamlText = lines.subList(1, end).joinToString("\n")
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            codePointLimit = MAX_SKILL_BYTES
            nestingDepthLimit = 8
        }
        val raw = Yaml(SafeConstructor(options)).load<Any?>(yamlText) as? Map<*, *>
            ?: error("SKILL.md 元数据必须是对象")
        val metadata = raw.entries.associate { (key, value) ->
            (key as? String ?: error("SKILL.md 元数据字段名无效")) to value
        }
        val unknown = metadata.keys - ALLOWED_METADATA_KEYS
        require(unknown.isEmpty()) { "SKILL.md 包含不支持的元数据: ${unknown.sorted().joinToString()}" }
        val name = (metadata["name"] as? String)?.trim().orEmpty()
        val description = (metadata["description"] as? String)?.trim().orEmpty()
        require(name.matches(SKILL_NAME_PATTERN)) { "Skill name 必须使用小写字母、数字和连字符" }
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_LENGTH) {
            "Skill description 不能为空且不能超过 500 字符"
        }
        require(lines.drop(end + 1).any { it.isNotBlank() }) { "SKILL.md 缺少说明正文" }
        return SkillMetadata(name, description)
    }

    private data class SkillMetadata(val name: String, val description: String)

    companion object {
        private val EXTENSION_ID_PATTERN = Regex("ext_[a-f0-9]{32}")
        private val STAGING_NAME_PATTERN = Regex("\\.ext_[a-f0-9]{32}\\.staging")
        private val SKILL_NAME_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        private val ALLOWED_METADATA_KEYS = setOf("name", "description", "license", "compatibility", "metadata")
        private const val MAX_SKILL_BYTES = 256 * 1_024
        private const val MAX_DESCRIPTION_LENGTH = 500
    }
}
