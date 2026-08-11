package com.agentdeck.app.data.host

import com.agentdeck.app.domain.host.HostPathGuard
import com.agentdeck.app.domain.host.WorkspaceDocumentStore
import com.agentdeck.app.domain.host.WorkspaceEntry
import com.agentdeck.app.domain.host.WorkspaceRead

/**
 * 纯内存工作区，供单测与无 SAF 时的策略验证。不是生产存储。
 */
class InMemoryWorkspaceDocumentStore : WorkspaceDocumentStore {
    private data class Node(
        var isDirectory: Boolean,
        var bytes: ByteArray? = null,
        val children: MutableMap<String, Node> = linkedMapOf(),
    )

    private val root = Node(isDirectory = true)

    override fun list(relativePath: String, maxDepth: Int, maxEntries: Int): Result<List<WorkspaceEntry>> =
        runCatching {
            val path = HostPathGuard.normalizeRelative(relativePath)
                ?: error("路径无效")
            val node = navigate(path) ?: error("路径不存在")
            check(node.isDirectory) { "不是目录" }
            val out = ArrayList<WorkspaceEntry>()
            fun walk(current: Node, prefix: String, depth: Int) {
                if (out.size >= maxEntries || depth > maxDepth) return
                current.children.forEach { (name, child) ->
                    if (out.size >= maxEntries) return
                    val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                    out.add(
                        WorkspaceEntry(
                            name = name,
                            relativePath = rel,
                            isDirectory = child.isDirectory,
                            sizeBytes = if (child.isDirectory) null else child.bytes?.size?.toLong(),
                        ),
                    )
                    if (child.isDirectory && depth < maxDepth) walk(child, rel, depth + 1)
                }
            }
            walk(node, path, 1)
            out
        }

    override fun read(relativePath: String, maxBytes: Long): Result<WorkspaceRead> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "不能读取工作区根为文件" }
        val node = navigate(path) ?: error("文件不存在")
        check(!node.isDirectory) { "目标是目录" }
        val data = node.bytes ?: ByteArray(0)
        val truncated = data.size > maxBytes
        val slice = if (truncated) data.copyOf(maxBytes.toInt()) else data
        WorkspaceRead(bytes = slice, truncated = truncated, contentTypeHint = "application/octet-stream")
    }

    override fun write(relativePath: String, bytes: ByteArray, maxBytes: Long): Result<Unit> = runCatching {
        require(bytes.size <= maxBytes) { "文件超过大小限制" }
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "不能覆盖工作区根" }
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        val parent = ensureDirectory(parentPath)
        val existing = parent.children[name]
        check(existing == null || !existing.isDirectory) { "目标是目录" }
        parent.children[name] = Node(isDirectory = false, bytes = bytes.copyOf())
    }

    override fun mkdir(relativePath: String): Result<Unit> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "工作区根已存在" }
        ensureDirectory(path)
        Unit
    }

    override fun removeFile(relativePath: String): Result<Unit> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        check(path.isNotEmpty()) { "不能删除工作区根" }
        val parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
        val name = path.substringAfterLast('/')
        val parent = navigate(parentPath) ?: error("文件不存在")
        val node = parent.children[name] ?: error("文件不存在")
        check(!node.isDirectory) { "拒绝删除目录；仅允许删除文件" }
        parent.children.remove(name)
        Unit
    }

    override fun stat(relativePath: String): Result<WorkspaceEntry> = runCatching {
        val path = HostPathGuard.normalizeRelative(relativePath) ?: error("路径无效")
        if (path.isEmpty()) {
            return@runCatching WorkspaceEntry(name = "", relativePath = "", isDirectory = true)
        }
        val node = navigate(path) ?: error("路径不存在")
        WorkspaceEntry(
            name = path.substringAfterLast('/'),
            relativePath = path,
            isDirectory = node.isDirectory,
            sizeBytes = if (node.isDirectory) null else node.bytes?.size?.toLong(),
        )
    }

    private fun navigate(relativePath: String): Node? {
        if (relativePath.isEmpty()) return root
        var current = root
        relativePath.split('/').forEach { part ->
            current = current.children[part] ?: return null
        }
        return current
    }

    private fun ensureDirectory(relativePath: String): Node {
        if (relativePath.isEmpty()) return root
        var current = root
        relativePath.split('/').forEach { part ->
            val next = current.children.getOrPut(part) { Node(isDirectory = true) }
            check(next.isDirectory) { "路径组件不是目录" }
            current = next
        }
        return current
    }
}
