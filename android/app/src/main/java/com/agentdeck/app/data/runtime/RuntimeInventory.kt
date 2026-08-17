package com.agentdeck.app.data.runtime

import android.content.Context
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.runtime.RuntimeCliCatalog
import com.agentdeck.app.domain.runtime.RuntimeCliStatus
import com.agentdeck.app.domain.runtime.RuntimeCliVersion

internal class RuntimeInventory(
    context: Context,
    private val paths: EmbeddedRuntimePaths = EmbeddedRuntimePaths.shared(context),
    private val installer: RecipeInstallation,
    private val dshPaths: DshRuntimePaths = DshRuntimePaths.shared(context),
    private val dshInstaller: DshRuntimeInstaller = DshRuntimeInstaller(
        context = context,
        dshPaths = dshPaths,
        codexPaths = paths,
    ),
    private val dshWeb: DshWebSupervisor = DshWebSupervisor(
        context = context,
        paths = dshPaths,
        codexPaths = paths,
    ),
    private val piPaths: PiRuntimePaths = PiRuntimePaths.shared(context),
    private val piInstaller: PiRuntimeInstaller = PiRuntimeInstaller(
        context = context,
        piPaths = piPaths,
        dshPaths = dshPaths,
        codexPaths = paths,
    ),
) {
    /**
     * Lightweight status for UI. Avoids full tree walks of multi-hundred-MB
     * rootfs / node_modules — those allocate huge temporary structures and
     * thrash the page cache. usedBytes uses manifest estimates when installed.
     */
    fun statuses(): List<RuntimeCliStatus> {
        val target = paths.runtimeTarget
        val codexDownloadBytes = (target?.rootfs?.sizeBytes ?: 0L) + (target?.codex?.sizeBytes ?: 0L)
        val codexInstalled = runCatching { paths.isReady() }.getOrDefault(false)
        // Prefer manifest sizes over walkTopDown of the whole Ubuntu rootfs.
        val codexUsed = if (codexInstalled) codexDownloadBytes else 0L
        val dshInstalled = dshPaths.isReady()
        val dshPartial = !dshInstalled &&
            dshPaths.dshEntry.isFile &&
            dshPaths.nodeBinary.isFile
        val dshDownload = DshRuntimeManifest.estimatedDownloadBytes()
        val dshUsed = when {
            dshInstalled || dshPartial -> dshDownload
            dshPaths.cliRoot.exists() -> dshDownload / 2
            else -> 0L
        }
        val piInstalled = piPaths.isReady()
        val piDownload = if (dshPaths.nodeBinary.isFile) {
            // Node can be copied from dsh — only npm package traffic matters (unknown size).
            20L * 1024 * 1024
        } else {
            PiRuntimeManifest.estimatedDownloadBytes()
        }
        val piUsed = when {
            piInstalled -> piDownload
            piPaths.cliRoot.exists() -> piDownload / 2
            else -> 0L
        }
        return RuntimeCliCatalog.kinds(
            codexVersion = target?.codexVersion
                ?: EmbeddedRuntimeManifest.supportedTargets().first().codexVersion,
            codexDownloadBytes = codexDownloadBytes,
            dshDownloadBytes = dshDownload,
            dshVersionLabel = DshRuntimeManifest.DSH_PACKAGE_LABEL,
            piDownloadBytes = piDownload,
            piVersionLabel = PiRuntimeManifest.PI_PACKAGE_LABEL,
        ).map { kind ->
            when (kind.id) {
                RuntimeCliCatalog.CODEX -> RuntimeCliStatus(
                    kind = kind,
                    installed = codexInstalled,
                    installedVersionLabel = if (codexInstalled) {
                        "Codex " + (target?.codexVersion ?: "")
                    } else {
                        null
                    },
                    selectedVersion = kind.versions.first(),
                    usedBytes = codexUsed,
                    canDelete = codexInstalled,
                    canOpen = false,
                    canPrepare = !codexInstalled,
                )
                RuntimeCliCatalog.DEEPSEEK_HARNESS -> RuntimeCliStatus(
                    kind = kind,
                    installed = dshInstalled,
                    installedVersionLabel = if (dshInstalled) {
                        DshRuntimeManifest.DSH_PACKAGE_LABEL
                    } else if (dshPartial) {
                        "需补编译原生模块"
                    } else {
                        null
                    },
                    selectedVersion = kind.versions.first(),
                    usedBytes = dshUsed,
                    canDelete = dshInstalled || dshUsed > 0L || dshPaths.cliRoot.exists(),
                    canOpen = true,
                    canPrepare = !dshInstalled && codexInstalled,
                )
                RuntimeCliCatalog.PI -> RuntimeCliStatus(
                    kind = kind,
                    installed = piInstalled,
                    installedVersionLabel = if (piInstalled) {
                        PiRuntimeManifest.PI_PACKAGE_LABEL
                    } else {
                        null
                    },
                    selectedVersion = kind.versions.first(),
                    usedBytes = piUsed,
                    canDelete = piInstalled || piUsed > 0L || piPaths.cliRoot.exists(),
                    // Open = smoke --help for D2 (no full terminal shell yet).
                    canOpen = piInstalled,
                    canPrepare = !piInstalled && codexInstalled,
                )
                else -> RuntimeCliStatus(
                    kind = kind,
                    installed = false,
                    installedVersionLabel = null,
                    selectedVersion = kind.versions.firstOrNull() ?: plannedVersion(kind.id),
                    usedBytes = 0L,
                    canDelete = false,
                    canOpen = false,
                    canPrepare = false,
                )
            }
        }
    }

    fun deleteCodex(): Result<String> = installer.uninstall("recipe_codex")

    fun deleteDsh(includeUserHome: Boolean = false): Result<String> {
        dshWeb.stop()
        return dshInstaller.uninstall(includeUserHome = includeUserHome)
    }

    fun deletePi(includeUserHome: Boolean = false): Result<String> =
        piInstaller.uninstall(includeUserHome = includeUserHome)

    suspend fun installDsh(
        onProgress: (RecipeInstallProgress) -> Unit = {},
    ): Result<String> = dshInstaller.install(onProgress)

    suspend fun installPi(
        onProgress: (RecipeInstallProgress) -> Unit = {},
    ): Result<String> = piInstaller.install(onProgress)

    suspend fun smokePiHelp(): Result<String> = piInstaller.smokeHelp()

    fun dshSupervisor(): DshWebSupervisor = dshWeb

    private fun plannedVersion(id: String) = RuntimeCliVersion(
        id = id + "-planned",
        label = "即将支持",
        selected = false,
        downloadBytes = 0L,
        notes = "不会占用下载或磁盘",
    )
}
