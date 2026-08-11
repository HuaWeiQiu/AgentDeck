package com.agentdeck.app.domain.host

/**
 * 已授权工作区上的文档操作。实现必须先经 [HostPathGuard] 规范化路径。
 */
interface WorkspaceDocumentStore {
    fun list(relativePath: String, maxDepth: Int, maxEntries: Int): Result<List<WorkspaceEntry>>
    fun read(relativePath: String, maxBytes: Long): Result<WorkspaceRead>
    fun write(relativePath: String, bytes: ByteArray, maxBytes: Long): Result<Unit>
    fun mkdir(relativePath: String): Result<Unit>
    fun removeFile(relativePath: String): Result<Unit>
    fun stat(relativePath: String): Result<WorkspaceEntry>
}

data class WorkspaceRead(
    val bytes: ByteArray,
    val truncated: Boolean,
    val contentTypeHint: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkspaceRead) return false
        return truncated == other.truncated &&
            contentTypeHint == other.contentTypeHint &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + truncated.hashCode()
        result = 31 * result + contentTypeHint.hashCode()
        return result
    }
}
