package com.agentdeck.app.data.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import com.agentdeck.app.domain.host.WorkspaceGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 持久化 SAF tree URI 授权元数据。不把 token 或文件正文写入此处。
 */
class WorkspaceGrantRepository(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableGrants = MutableStateFlow(load())

    val grants: StateFlow<List<WorkspaceGrant>> = mutableGrants.asStateFlow()

    fun primaryGrant(): WorkspaceGrant? = mutableGrants.value.firstOrNull()

    fun addGrant(treeUri: Uri, displayName: String, takePersistablePermission: Boolean = true): WorkspaceGrant {
        if (takePersistablePermission) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                app.contentResolver.takePersistableUriPermission(treeUri, flags)
            }
        }
        val grant = WorkspaceGrant(
            id = UUID.randomUUID().toString(),
            treeUri = treeUri.toString(),
            displayName = displayName.ifBlank { treeUri.lastPathSegment ?: "工作区" },
            createdAtEpochMs = System.currentTimeMillis(),
        )
        // L1 首期只保留一个主工作区，新授权替换旧授权
        val previous = mutableGrants.value
        previous.forEach { old -> releasePersistable(old.treeUri) }
        persist(listOf(grant))
        return grant
    }

    fun revoke(grantId: String) {
        val remaining = mutableGrants.value.filterNot { it.id == grantId }
        mutableGrants.value.firstOrNull { it.id == grantId }?.let { releasePersistable(it.treeUri) }
        persist(remaining)
    }

    fun revokeAll() {
        mutableGrants.value.forEach { releasePersistable(it.treeUri) }
        persist(emptyList())
    }

    private fun releasePersistable(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, flags) }
    }

    private fun persist(grants: List<WorkspaceGrant>) {
        val array = JSONArray()
        grants.forEach { grant ->
            array.put(
                JSONObject()
                    .put("id", grant.id)
                    .put("treeUri", grant.treeUri)
                    .put("displayName", grant.displayName)
                    .put("createdAtEpochMs", grant.createdAtEpochMs),
            )
        }
        preferences.edit { putString(KEY_GRANTS, array.toString()) }
        mutableGrants.value = grants
    }

    private fun load(): List<WorkspaceGrant> {
        val raw = preferences.getString(KEY_GRANTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        WorkspaceGrant(
                            id = obj.getString("id"),
                            treeUri = obj.getString("treeUri"),
                            displayName = obj.optString("displayName", "工作区"),
                            createdAtEpochMs = obj.optLong("createdAtEpochMs", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "agentdeck_host_workspace"
        private const val KEY_GRANTS = "grants_v1"
    }
}
