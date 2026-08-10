package com.agentdeck.app.data.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.domain.chat.ChatAttachment
import com.agentdeck.app.domain.chat.ChatAttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ChatAttachmentStore(context: Context) {
    private val app = context.applicationContext
    private val paths = EmbeddedRuntimePaths(app)

    suspend fun import(cardId: String, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        paths.ensureHostLayout()
        val metadata = queryMetadata(uri)
        require(metadata.sizeBytes == null || metadata.sizeBytes <= MAX_ATTACHMENT_BYTES) {
            "附件不能超过 20 MiB"
        }
        val kind = attachmentKind(metadata.mimeType, metadata.displayName)
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
            ChatAttachment(
                id = UUID.randomUUID().toString(),
                name = safeDisplayName(metadata.displayName),
                mimeType = metadata.mimeType,
                sizeBytes = copied,
                guestPath = "/root/projects/.agentdeck-attachments/$instanceKey/$storageName",
                kind = kind,
            )
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    suspend fun remove(attachment: ChatAttachment) = withContext(Dispatchers.IO) {
        val prefix = "/root/projects/.agentdeck-attachments/"
        if (!attachment.guestPath.startsWith(prefix)) return@withContext
        val relative = attachment.guestPath.removePrefix("/root/projects/")
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
    }
}

private data class AttachmentMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

internal fun attachmentKind(mimeType: String, displayName: String): ChatAttachmentKind {
    val normalizedMime = mimeType.lowercase()
    val extension = displayName.substringAfterLast('.', "").lowercase()
    return if (normalizedMime == "image/png" || normalizedMime == "image/jpeg" ||
        extension == "png" || extension == "jpg" || extension == "jpeg"
    ) {
        ChatAttachmentKind.IMAGE
    } else {
        ChatAttachmentKind.FILE
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
