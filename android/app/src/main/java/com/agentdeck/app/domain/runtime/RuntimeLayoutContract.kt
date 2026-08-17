package com.agentdeck.app.domain.runtime

/**
 * On-disk layout for the embedded runtime under `noBackupFilesDir/agentdeck-runtime`.
 *
 * Per-CLI trees live under [RUNTIMES_DIR]/ shared user data stays at the root so deleting
 * one CLI never removes conversations, personas, projects, or extension snapshots.
 */
object RuntimeLayoutContract {
    const val RUNTIME_ROOT_NAME = "agentdeck-runtime"
    const val RUNTIMES_DIR = "runtimes"
    const val CODEX_HOME = "codex-home"
    const val PROJECTS = "projects"
    const val STATE = "state"
    const val TEMP = "tmp"
    const val EXTENSIONS = "extensions"
    const val EXTENSION_PACKAGES = "extensions/packages"
    const val EXTENSION_SESSIONS = "extensions/sessions"

    /** Relative path of a CLI's private tree: `runtimes/<cli-id>`. */
    fun cliRootRelative(cliId: String): String {
        require(cliId.matches(CLI_ID_PATTERN)) { "CLI id 无效: $cliId" }
        return "$RUNTIMES_DIR/$cliId"
    }

    fun rootfsRelative(cliId: String, releaseId: String): String =
        "${cliRootRelative(cliId)}/rootfs-$releaseId"

    fun stagingRootfsRelative(cliId: String, releaseId: String): String =
        "${cliRootRelative(cliId)}/.rootfs-$releaseId.staging"

    fun downloadsRelative(cliId: String): String = "${cliRootRelative(cliId)}/downloads"

    /** Paths that must survive `removeCodexRuntime` / per-CLI uninstall. */
    fun sharedUserDataRelatives(): Set<String> = setOf(
        CODEX_HOME,
        PROJECTS,
        STATE,
        EXTENSION_PACKAGES,
        EXTENSION_SESSIONS,
    )

    fun isUnderCliTree(relativePath: String, cliId: String): Boolean {
        val prefix = cliRootRelative(cliId)
        return relativePath == prefix || relativePath.startsWith("$prefix/")
    }

    fun isSharedUserData(relativePath: String): Boolean =
        sharedUserDataRelatives().any { shared ->
            relativePath == shared || relativePath.startsWith("$shared/")
        }

    private val CLI_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")
}
