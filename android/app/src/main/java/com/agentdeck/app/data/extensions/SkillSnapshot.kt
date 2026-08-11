package com.agentdeck.app.data.extensions

import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.data.runtime.deleteTreeWithoutFollowingLinks
import com.agentdeck.app.domain.extensions.ManagedExtension
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

internal class SkillSnapshot private constructor(
    private val directory: File,
) : AutoCloseable {
    val key: String = directory.name.removePrefix("skills.")

    override fun close() {
        deleteTreeWithoutFollowingLinks(directory.toPath())
    }

    companion object {
        fun create(
            paths: EmbeddedRuntimePaths,
            key: String,
            skills: List<ManagedExtension>,
        ): SkillSnapshot {
            require(key.matches(Regex("[a-f0-9]{1,16}"))) { "Skill 快照标识无效" }
            paths.ensureHostLayout()
            val target = File(paths.extensionSessionSnapshots, "skills.$key")
            deleteTreeWithoutFollowingLinks(target.toPath())
            check(target.mkdirs()) { "无法创建 Skill 会话快照" }
            try {
                skills.forEach { extension ->
                    val config = requireNotNull(extension.skill) { "Skill 配置不存在" }
                    val sourceRoot = File(config.installedPath).canonicalFile
                    require(sourceRoot.parentFile == paths.extensionPackages.canonicalFile) {
                        "Skill 安装路径越界"
                    }
                    val source = File(sourceRoot, "SKILL.md")
                    require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "Skill 文件不存在"
                    }
                    val bytes = source.readBytes()
                    val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { byte -> "%02x".format(byte) }
                    require(actual == config.manifestHash) { "Skill 文件校验失败，请重新导入" }
                    val skillTarget = File(target, extension.id)
                    check(skillTarget.mkdir()) { "无法创建 Skill 快照目录" }
                    File(skillTarget, "SKILL.md").writeBytes(bytes)
                    bytes.fill(0)
                }
                return SkillSnapshot(target)
            } catch (error: Exception) {
                deleteTreeWithoutFollowingLinks(target.toPath())
                throw error
            }
        }
    }
}
