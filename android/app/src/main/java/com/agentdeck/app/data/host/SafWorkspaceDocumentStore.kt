package com.agentdeck.app.data.host

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.agentdeck.app.domain.host.HostPathGuard
import com.agentdeck.app.domain.host.WorkspaceDocumentStore
import com.agentdeck.app.domain.host.WorkspaceEntry
import com.agentdeck.app.domain.host.WorkspaceRead

/**
 * 基于 SAF tree URI 的工作区访问。所有相对路径必须先通过 [HostPathGuard]。
 */
class SafWorkspaceDocumentStore(
    context: Context,
    private val treeUri: Uri,
) : WorkspaceDocumentStore {
    private val app = context.applicationContext

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(app, treeUri)
            ?: error("工作区授权不可用")

    private fun resolve(relativePath: String): DocumentFile? {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: return null
        if (path.isEmpty()) return root()
        var current = root()
        path.split('/').forEach { name ->
            current = current.findFile(name) ?: return null
        }
        return current
    }

    override fun list(relativePath: String, maxDepth: Int, maxEntries: Int): Result<List<WorkspaceEntry>> =
        runCatching {
            val startPath = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
            val start = resolve(startPath) ?: error("路径不存在")
            check(start.isDirectory) { "不是目录" }
            val out = ArrayList<WorkspaceEntry>()
            fun walk(dir: DocumentFile, prefix: String, depth: Int) {
                if (out.size >= maxEntries || depth > maxDepth) return
                dir.listFiles().forEach { child ->
                    if (out.size >= maxEntries) return
                    val name = child.name ?: return@forEach
                    if (name == "." || name == "..") return@forEach
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    // 再校验拼接后的相对路径
                    if (HostPathGuard.normalizeRelative(rel) != rel) return@forEach
                    out.add(
                        WorkspaceEntry(
                            name = name,
                            relativePath = rel,
                            isDirectory = child.isDirectory,
                            sizeBytes = if (child.isFile) child.length() else null,
                        ),
                    )
                    if (child.isDirectory && depth < maxDepth) walk(child, rel, depth + 1)
                }
            }
            walk(start, startPath, 1)
            out
        }

    override fun read(relativePath: String, maxBytes: Long): Result<WorkspaceRead> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "不能读取工作区根为文件" }
        val file = resolve(path) ?: error("文件不存在")
        check(file.isFile) { "目标不是文件" }
        val total = file.length().coerceAtLeast(0L)
        val truncated = total > maxBytes
        val toRead = if (truncated) maxBytes else total
        val bytes = app.contentResolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(toRead.toInt())
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset == buffer.size) buffer else buffer.copyOf(offset)
        } ?: error("无法打开文件")
        WorkspaceRead(
            bytes = bytes,
            truncated = truncated,
            contentTypeHint = file.type ?: "application/octet-stream",
        )
    }

    override fun write(relativePath: String, bytes: ByteArray, maxBytes: Long): Result<Unit> = runCatching {
        require(bytes.size <= maxBytes) { "文件超过大小限制" }
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "不能覆盖工作区根" }
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        val parent = if (parentPath.isEmpty()) {
            root()
        } else {
            // create intermediate dirs
            var current = root()
            parentPath.split('/').forEach { part ->
                val next = current.findFile(part) ?: current.createDirectory(part)
                    ?: error("无法创建目录 $part")
                check(next.isDirectory) { "路径组件不是目录" }
                current = next
            }
            current
        }
        val existing = parent.findFile(name)
        check(existing == null || existing.isFile) { "目标是目录" }
        val target = existing ?: parent.createFile("application/octet-stream", name)
            ?: error("无法创建文件")
        app.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("无法写入文件")
        Unit
    }

    override fun mkdir(relativePath: String): Result<Unit> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "工作区根已存在" }
        var current = root()
        path.split('/').forEach { part ->
            val next = current.findFile(part) ?: current.createDirectory(part)
                ?: error("无法创建目录")
            check(next.isDirectory) { "路径组件不是目录" }
            current = next
        }
        Unit
    }

    override fun removeFile(relativePath: String): Result<Unit> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "不能删除工作区根" }
        val file = resolve(path) ?: error("文件不存在")
        check(file.isFile) { "拒绝删除目录；仅允许删除文件" }
        check(file.delete()) { "删除失败" }
        Unit
    }

    override fun stat(relativePath: String): Result<WorkspaceEntry> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        if (path.isEmpty()) {
            return@runCatching WorkspaceEntry("", "", isDirectory = true)
        }
        val file = resolve(path) ?: error("路径不存在")
        WorkspaceEntry(
            name = file.name ?: path.substringAfterLast('/'),
            relativePath = path,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isFile) file.length() else null,
        )
    }
}
