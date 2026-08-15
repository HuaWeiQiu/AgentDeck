package com.agentdeck.app.data.runtime

import android.content.Context
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.runtime.RuntimeCliCatalog
import com.agentdeck.app.domain.runtime.RuntimeCliStatus

internal class RuntimeInventory(
    context: Context,
    private val paths: EmbeddedRuntimePaths = EmbeddedRuntimePaths(context),
    private val installer: RecipeInstallation,
) {
    fun statuses(): List<RuntimeCliStatus> {
        val target = paths.runtimeTarget
        val downloadBytes = (target?.rootfs?.sizeBytes ?: 0L) + (target?.codex?.sizeBytes ?: 0L)
        val installed = runCatching { paths.isReady() }.getOrDefault(false)
        val used = if (installed) runCatching { paths.usedBytes() }.getOrDefault(0L) else 0L
        return RuntimeCliCatalog.kinds(
            codexVersion = target?.codexVersion ?: EmbeddedRuntimeManifest.supportedTargets().first().codexVersion,
            downloadBytes = downloadBytes,
        ).map { kind ->
            if (kind.id == RuntimeCliCatalog.CODEX) {
                RuntimeCliStatus(
                    kind = kind,
                    installed = installed,
                    installedVersionLabel = if (installed) "Codex " + (target?.codexVersion ?: "") else null,
                    selectedVersion = kind.versions.first(),
                    usedBytes = used,
                    canDelete = installed,
                )
            } else {
                RuntimeCliStatus(
                    kind = kind,
                    installed = false,
                    installedVersionLabel = null,
                    selectedVersion = kind.versions.firstOrNull() ?: com.agentdeck.app.domain.runtime.RuntimeCliVersion(
                        id = kind.id + "-planned",
                        label = "即将支持",
                        selected = false,
                        downloadBytes = 0L,
                        notes = "不会占用下载或磁盘",
                    ),
                    usedBytes = 0L,
                    canDelete = false,
                )
            }
        }
    }

    fun deleteCodex(): Result<String> = installer.uninstall("recipe_codex")
}
