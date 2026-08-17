package com.agentdeck.app.data.runtime

import java.io.File

/**
 * Disk-backed Node startup helpers (compile cache paths + shell snippets).
 *
 * [NODE_COMPILE_CACHE] is supported by modern Node (v22+); modules compile once
 * onto disk so the next process start under PRoot is warmer without holding RAM.
 * See docs/plans/agent-startup-acceleration.md.
 */
internal object NodeStartupSupport {
    /** Host-side cache directory name under each CLI root. */
    const val CACHE_DIR_NAME = ".node-compile-cache"

    const val GUEST_PI_CACHE = "/opt/agentdeck-pi/$CACHE_DIR_NAME"
    const val GUEST_DSH_CACHE = "/opt/agentdeck-dsh/$CACHE_DIR_NAME"

    /**
     * Shell fragment: create cache dir and export env for Node under proot.
     * Safe to prepend after `set -euo pipefail`.
     */
    fun shellExports(guestCachePath: String): String = """
        mkdir -p ${shellSingleQuote(guestCachePath)}
        export NODE_COMPILE_CACHE=${shellSingleQuote(guestCachePath)}
    """.trimIndent()

    /** Common phone-side Node options (heap + quiet). */
    fun nodeOptionsExport(maxOldSpaceMb: Int = 160): String =
        """export NODE_OPTIONS="--max-old-space-size=$maxOldSpaceMb --no-warnings --no-deprecation""""

    fun ensureHostCacheDir(cliRoot: File): File {
        val dir = File(cliRoot, CACHE_DIR_NAME)
        check(dir.mkdirs() || dir.isDirectory) { "无法创建 Node compile cache 目录" }
        return dir
    }

    /** Write only when content actually changes (avoids fsync thrash on re-entry). */
    fun writeTextIfChanged(file: File, content: String): Boolean {
        if (file.isFile) {
            val existing = runCatching { file.readText() }.getOrNull()
            if (existing == content) return false
        }
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        file.writeText(content)
        return true
    }

    private fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
