package com.agentdeck.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.agentdeck.app.data.extensions.ExtensionRepository
import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.domain.backup.ConversationBackupCodec
import com.agentdeck.app.domain.backup.ConversationBackupDocument
import com.agentdeck.app.domain.backup.ConversationBackupItem
import com.agentdeck.app.domain.backup.ConversationBackupPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class ConversationBackupRepository(
    private val app: Context,
    private val cards: CardRepository,
    private val extensions: ExtensionRepository,
) {
    private val preferences = app.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun exportDocument(): ConversationBackupDocument {
        val allCards = cards.observeCards().first()
        val selections = extensions.observeCardSelections().first()
        return ConversationBackupDocument(
            exportedAtEpochMs = System.currentTimeMillis(),
            conversations = allCards.map { card ->
                ConversationBackupItem(
                    id = card.id,
                    name = card.name,
                    customTitle = card.customTitle,
                    recipeId = card.recipeId,
                    profileId = card.profileId,
                    modelId = card.modelId,
                    permissionLevel = card.permissionLevel?.name,
                    workspacePath = card.workspacePath,
                    pinned = card.pinned,
                    archived = card.archived,
                    identity = card.identity,
                    selectedExtensionIds = selections[card.id].orEmpty().sorted(),
                )
            },
        )
    }

    suspend fun writeExport(context: Context, uri: Uri): ConversationBackupPreview {
        val document = exportDocument()
        val text = ConversationBackupCodec.encode(document)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入备份文件")
        }
        preferences.edit { putLong(LAST_EXPORT_KEY, document.exportedAtEpochMs) }
        return previewOf(document)
    }

    suspend fun importFrom(context: Context, uri: Uri): ConversationBackupPreview {
        val document = readDocument(context, uri)
        val existing = cards.observeCards().first().associateBy { it.id }
        val knownExtensions = extensions.getAll().map { it.id }.toSet()
        document.conversations.forEach { item ->
            val card = ConversationBackupCodec.toCard(item, existing[item.id])
            val allowed = item.selectedExtensionIds.filter { it in knownExtensions }.toSet()
            extensions.saveCardWithSelections(card, allowed)
        }
        return previewOf(document)
    }

    fun lastExportAtEpochMs(): Long? =
        preferences.getLong(LAST_EXPORT_KEY, 0L).takeIf { it > 0L }

    private suspend fun readDocument(context: Context, uri: Uri): ConversationBackupDocument {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("无法读取备份文件")
        }
        return ConversationBackupCodec.decode(text)
    }

    private fun previewOf(document: ConversationBackupDocument) = ConversationBackupPreview(
        conversationCount = document.conversations.size,
        identityCount = document.conversations.count { it.identity != null },
        names = document.conversations.map { it.customTitle ?: it.name }.take(5),
    )

    companion object {
        private const val PREFERENCES_NAME = "agentdeck_backup"
        private const val LAST_EXPORT_KEY = "conversation_backup_exported_at"
    }
}
