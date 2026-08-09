package com.agentdeck.app.data.runtime

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption

internal object SecureTarExtractor {
    private const val MAX_ENTRIES = 200_000
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    fun extractGzipTar(archive: File, target: File) {
        check(target.mkdirs() || target.isDirectory) { "无法创建解压目录" }
        val directoryModes = mutableListOf<Pair<File, Int>>()
        val pendingHardLinks = mutableListOf<Pair<File, String>>()
        var entries = 0
        var totalBytes = 0L

        TarArchiveInputStream(
            GzipCompressorInputStream(
                BufferedInputStream(FileInputStream(archive)),
            ),
        ).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                entries += 1
                require(entries <= MAX_ENTRIES) { "运行环境归档文件数量异常" }
                totalBytes += entry.size.coerceAtLeast(0)
                require(totalBytes <= MAX_TOTAL_BYTES) { "运行环境解压大小异常" }
                extractEntry(input, entry, target, directoryModes, pendingHardLinks)
                entry = input.nextEntry
            }
        }

        pendingHardLinks.forEach { (output, linkName) ->
            val source = secureTarget(target, linkName)
            require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
                "运行环境归档包含无效硬链接"
            }
            ensureParentIsSafe(target, output)
            source.copyTo(output, overwrite = true)
        }
        directoryModes.asReversed().forEach { (directory, mode) -> chmod(directory, mode) }
    }

    private fun extractEntry(
        input: TarArchiveInputStream,
        entry: TarArchiveEntry,
        target: File,
        directoryModes: MutableList<Pair<File, Int>>,
        pendingHardLinks: MutableList<Pair<File, String>>,
    ) {
        val output = secureTarget(target, entry.name)
        ensureParentIsSafe(target, output)
        when {
            entry.isDirectory -> {
                check(output.mkdirs() || output.isDirectory) { "无法创建运行环境目录" }
                directoryModes += output to entry.mode
            }
            entry.isSymbolicLink -> {
                validateSymbolicLink(target, output, entry.linkName)
                output.parentFile?.mkdirs()
                Files.deleteIfExists(output.toPath())
                Files.createSymbolicLink(output.toPath(), File(entry.linkName).toPath())
            }
            entry.isLink -> pendingHardLinks += output to entry.linkName
            entry.isFile -> {
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { stream -> input.copyTo(stream) }
                chmod(output, entry.mode)
            }
            else -> Unit
        }
    }

    private fun validateSymbolicLink(root: File, output: File, linkName: String) {
        require(linkName.isNotBlank() && '\u0000' !in linkName) { "运行环境归档包含无效符号链接" }
        if (linkName.startsWith('/')) {
            require(linkName.split('/').none { it == ".." }) { "运行环境归档符号链接越界" }
            return
        }
        val rootPath = root.canonicalFile.toPath()
        val resolved = requireNotNull(output.parentFile).toPath().resolve(linkName).normalize()
        require(resolved.startsWith(rootPath)) { "运行环境归档符号链接越界" }
    }

    internal fun secureTarget(root: File, entryName: String): File {
        val normalizedName = entryName.removePrefix("./").trimEnd('/')
        require(normalizedName.isNotBlank() && !normalizedName.startsWith('/')) {
            "运行环境归档包含绝对路径"
        }
        val components = normalizedName.split('/')
        require(components.none { it == ".." || it.isEmpty() }) {
            "运行环境归档包含越界路径"
        }
        val rootPath = root.canonicalFile.toPath()
        val output = rootPath.resolve(normalizedName).normalize()
        require(output.startsWith(rootPath)) { "运行环境归档路径越界" }
        return output.toFile()
    }

    private fun ensureParentIsSafe(root: File, output: File) {
        val rootPath = root.canonicalFile.toPath()
        var current = output.parentFile?.toPath()
        while (current != null && current != rootPath) {
            require(!Files.isSymbolicLink(current)) { "运行环境归档尝试穿过符号链接" }
            require(
                !Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(current),
            ) { "运行环境归档父路径不是目录" }
            current = current.parent
        }
        require(current == rootPath) { "运行环境归档父路径越界" }
    }

    private fun chmod(file: File, mode: Int) {
        Os.chmod(file.absolutePath, mode and 0x1ff)
    }
}
