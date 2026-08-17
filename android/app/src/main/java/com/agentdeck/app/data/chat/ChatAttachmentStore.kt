package com.agentdeck.app.data.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.domain.chat.ChatAttachment
import com.agentdeck.app.domain.chat.ChatAttachmentFormat
import com.agentdeck.app.domain.chat.ChatAttachmentKind
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeCommand
import com.agentdeck.app.domain.runtime.RuntimeProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ChatAttachmentStore(
    context: Context,
    private val runtime: AgentRuntime,
) {
    private val app = context.applicationContext
    private val paths = EmbeddedRuntimePaths.shared(app)

    suspend fun import(cardId: String, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        paths.ensureHostLayout()
        val metadata = queryMetadata(uri)
        require(metadata.sizeBytes == null || metadata.sizeBytes <= MAX_ATTACHMENT_BYTES) {
            "附件不能超过 20 MiB"
        }
        val format = requireNotNull(attachmentFormat(metadata.mimeType, metadata.displayName)) {
            "不支持此文件类型；可添加 PNG、JPEG、文本/代码、PDF、DOCX 或 XLSX"
        }
        val kind = if (format == ChatAttachmentFormat.IMAGE) {
            ChatAttachmentKind.IMAGE
        } else {
            ChatAttachmentKind.FILE
        }
        val extension = storageExtension(metadata.displayName, metadata.mimeType)
        val instanceKey = CodexBridgeLauncher.instanceKey(cardId)
        val directory = File(paths.projectsHome, ".agentdeck-attachments/$instanceKey")
        check(directory.mkdirs() || directory.isDirectory) { "无法创建附件目录" }
        Os.chmod(directory.absolutePath, DIRECTORY_MODE)
        val storageName = UUID.randomUUID().toString() + extension
        val destination = File(directory, storageName)
        val temporary = File(directory, ".$storageName.part")
        try {
            val input = requireNotNull(app.contentResolver.openInputStream(uri)) { "无法读取所选文件" }
            val copied = input.buffered().use { source ->
                temporary.outputStream().buffered().use { target ->
                    copyBounded(source, target, MAX_ATTACHMENT_BYTES)
                }
            }
            check(temporary.renameTo(destination)) { "无法保存附件" }
            Os.chmod(destination.absolutePath, FILE_MODE)
            val imported = ChatAttachment(
                id = UUID.randomUUID().toString(),
                name = safeDisplayName(metadata.displayName),
                mimeType = metadata.mimeType,
                sizeBytes = copied,
                guestPath = "/root/projects/.agentdeck-attachments/$instanceKey/$storageName",
                kind = kind,
                format = format,
            )
            if (kind == ChatAttachmentKind.FILE) prepare(imported) else imported
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    suspend fun remove(attachment: ChatAttachment) = withContext(Dispatchers.IO) {
        deletePrivateGuestFile(attachment.guestPath)
        attachment.preparedGuestPath?.let(::deletePrivateGuestFile)
    }

    private suspend fun prepare(attachment: ChatAttachment): ChatAttachment {
        val adapterId = requireNotNull(attachment.format.adapterId)
        require(isPrivateGuestPath(attachment.guestPath)) { "附件路径无效" }
        val preparedPath = attachment.guestPath + ".agentdeck.txt"
        require(isPrivateGuestPath(preparedPath)) { "附件解析路径无效" }
        val command = RuntimeCommand(
            instanceId = "file-adapter-${attachment.id.take(12)}",
            program = RuntimeProgram.HOST_SHELL,
            script = "python3 /usr/local/lib/agentdeck/agentdeck-file-adapter.py " +
                "--kind $adapterId --source ${shellQuote(attachment.guestPath)} " +
                "--output ${shellQuote(preparedPath)}",
            workDir = attachment.guestPath.substringBeforeLast('/'),
        )
        return try {
            val result = runtime.runCommandForResult(command, ADAPTER_TIMEOUT_MILLIS).getOrThrow()
            check(result.commandSucceeded) {
                "文件解析失败：" + result.stderr.ifBlank { result.stdout }.trim().takeLast(240)
            }
            val metadata = JSONObject(result.stdout.trim())
            check(metadata.optString("kind") == adapterId && metadata.optString("output") == preparedPath) {
                "文件解析器返回了无效结果"
            }
            attachment.copy(
                preparedGuestPath = preparedPath,
                wasTruncated = metadata.optBoolean("truncated", false),
            )
        } catch (error: Exception) {
            deletePrivateGuestFile(preparedPath)
            throw error
        }
    }

    private fun deletePrivateGuestFile(guestPath: String) {
        if (!isPrivateGuestPath(guestPath)) return
        val relative = guestPath.removePrefix("/root/projects/")
        val file = File(paths.projectsHome, relative).canonicalFile
        val root = File(paths.projectsHome, ".agentdeck-attachments").canonicalFile
        if (file.toPath().startsWith(root.toPath())) file.delete()
    }

    private fun queryMetadata(uri: Uri): AttachmentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        app.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { sizeBytes = cursor.getLong(it).takeIf { size -> size >= 0 } }
            }
        }
        return AttachmentMetadata(
            displayName = displayName ?: uri.lastPathSegment ?: "attachment",
            mimeType = app.contentResolver.getType(uri)?.lowercase() ?: "application/octet-stream",
            sizeBytes = sizeBytes,
        )
    }

    companion object {
        const val MAX_ATTACHMENTS = 4
        const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
        private const val DIRECTORY_MODE = 448 // 0700
        private const val FILE_MODE = 384 // 0600
        private const val ADAPTER_TIMEOUT_MILLIS = 30_000L
    }
}

private data class AttachmentMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

internal fun attachmentKind(mimeType: String, displayName: String): ChatAttachmentKind {
    return if (attachmentFormat(mimeType, displayName) == ChatAttachmentFormat.IMAGE) {
        ChatAttachmentKind.IMAGE
    } else ChatAttachmentKind.FILE
}

internal fun attachmentFormat(mimeType: String, displayName: String): ChatAttachmentFormat? {
    val normalizedMime = mimeType.lowercase().substringBefore(';').trim()
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return when {
        normalizedMime in IMAGE_MIME_TYPES || extension in IMAGE_EXTENSIONS -> ChatAttachmentFormat.IMAGE
        normalizedMime == "application/pdf" || extension == "pdf" -> ChatAttachmentFormat.PDF
        normalizedMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            extension == "docx" -> ChatAttachmentFormat.DOCX
        normalizedMime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            extension == "xlsx" -> ChatAttachmentFormat.XLSX
        normalizedMime.startsWith("text/") || normalizedMime in TEXT_MIME_TYPES ||
            extension in TEXT_EXTENSIONS -> ChatAttachmentFormat.TEXT
        else -> null
    }
}

internal fun safeDisplayName(value: String): String = value
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .filterNot(Char::isISOControl)
    .trim()
    .take(120)
    .ifBlank { "attachment" }

private fun storageExtension(displayName: String, mimeType: String): String {
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
    val fallback = when (mimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        else -> null
    }
    return (extension ?: fallback)?.let { ".$it" }.orEmpty()
}

private fun copyBounded(
    source: java.io.InputStream,
    target: java.io.OutputStream,
    maxBytes: Long,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) return total
        if (read == 0) continue
        total += read
        require(total <= maxBytes) { "附件不能超过 20 MiB" }
        target.write(buffer, 0, read)
    }
}

private fun isPrivateGuestPath(value: String): Boolean =
    value.matches(Regex("/root/projects/[.]agentdeck-attachments/[a-f0-9]{1,16}/[a-f0-9-]{36}[.A-Za-z0-9-]{0,32}"))

private fun shellQuote(value: String): String {
    require(isPrivateGuestPath(value)) { "附件路径无效" }
    return "'" + value.replace("'", "'\\''") + "'"
}

private val IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")
private val TEXT_MIME_TYPES = setOf(
    "application/json",
    "application/ld+json",
    "application/toml",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
)
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "json", "jsonl", "yaml", "yml", "csv", "tsv",
    "xml", "html", "htm", "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py",
    "rb", "go", "rs", "c", "cc", "cpp", "h", "hpp", "cs", "swift", "sh", "bash",
    "zsh", "fish", "sql", "toml", "ini", "properties", "gradle",
)
