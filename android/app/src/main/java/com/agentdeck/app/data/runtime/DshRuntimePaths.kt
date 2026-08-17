package com.agentdeck.app.data.runtime

import android.content.Context
import com.agentdeck.app.domain.runtime.RuntimeCliCatalog
import com.agentdeck.app.domain.runtime.RuntimeLayoutContract
import java.io.File

/**
 * On-disk layout for DeepSeek Harness under the shared agentdeck-runtime root.
 *
 * ```
 * runtimes/deepseek-harness/
 *   node/                 # extracted official Node linux tarball
 *   node_modules/         # npm install tree (includes @deepseek-ai/dsh)
 *   downloads/
 *   .agentdeck-dsh
 * dsh-home/               # DSH_HOME (credentials) — outside CLI delete by default
 * ```
 */
internal class DshRuntimePaths(
    context: Context,
) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, RuntimeLayoutContract.RUNTIME_ROOT_NAME)

    val cliRoot: File = File(root, RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.DEEPSEEK_HARNESS))
    val downloads: File = File(root, RuntimeLayoutContract.downloadsRelative(RuntimeCliCatalog.DEEPSEEK_HARNESS))
    val dshHome: File = File(root, "dsh-home")
    val installMarker: File = File(cliRoot, ".agentdeck-dsh")
    val nodeHome: File = File(cliRoot, "node")
    val nodeBinary: File = File(nodeHome, "bin/node")
    val npmBinary: File = File(nodeHome, "bin/npm")
    /** Official package bin maps to lib/bin.js (npm @deepseek-ai/dsh). */
    val dshEntry: File = File(cliRoot, "node_modules/@deepseek-ai/dsh/lib/bin.js")
    val stagingNode: File = File(cliRoot, ".node.staging")
    private val nodePtyRoot: File = File(cliRoot, "node_modules/node-pty")

    /** Node V8 compile cache (host); bound into guest as [NodeStartupSupport.GUEST_DSH_CACHE]. */
    val nodeCompileCache: File = File(cliRoot, NodeStartupSupport.CACHE_DIR_NAME)

    fun ensureLayout() {
        listOf(cliRoot, downloads, dshHome, nodeHome, nodeCompileCache).forEach { dir ->
            check(dir.mkdirs() || dir.isDirectory) { "无法创建 dsh 运行目录" }
        }
    }

    /**
     * dsh web loads `@deepseek-ai/dsh-subprocess-local` → `node-pty`. Without a
     * linux native binary the process exits immediately (connection refused).
     *
     * Accepts either a compiled `build/Release/pty.node` or Homebridge multiarch
     * prebuilds (`prebuilds/linux-arm64/node.abi*.node`).
     */
    fun hasNodePtyNative(): Boolean {
        if (!nodePtyRoot.isDirectory) return false
        val release = File(nodePtyRoot, "build/Release/pty.node")
        if (release.isFile && release.length() > 0L) return true
        val stockPre = listOf(
            File(nodePtyRoot, "prebuilds/linux-arm64/pty.node"),
            File(nodePtyRoot, "prebuilds/linux-x64/pty.node"),
        )
        if (stockPre.any { it.isFile && it.length() > 0L }) return true
        val multiarchDirs = listOf(
            File(nodePtyRoot, "prebuilds/linux-arm64"),
            File(nodePtyRoot, "prebuilds/linux-x64"),
        )
        return multiarchDirs.any { dir ->
            dir.isDirectory &&
                dir.listFiles()?.any { f ->
                    f.isFile && f.name.startsWith("node.abi") && f.name.endsWith(".node") && f.length() > 0L
                } == true
        }
    }

    fun isReady(): Boolean {
        if (!installMarker.isFile) return false
        if (!nodeBinary.isFile) return false
        if (!dshEntry.isFile) return false
        if (!hasNodePtyNative()) return false
        return markerMatchesCurrentRelease(installMarker.readText())
    }

    fun usedBytes(): Long {
        if (!cliRoot.exists()) return 0L
        return sequenceOf(cliRoot, downloads).filter { it.exists() }.sumOf(::directorySize)
    }

    fun removeRuntime(includeUserHome: Boolean) {
        if (cliRoot.exists()) deleteTreeWithoutFollowingLinks(cliRoot.toPath())
        if (downloads.exists()) deleteTreeWithoutFollowingLinks(downloads.toPath())
        if (includeUserHome && dshHome.exists()) {
            deleteTreeWithoutFollowingLinks(dshHome.toPath())
        }
    }

    fun writeInstallMarker(target: DshRuntimeTarget) {
        ensureLayout()
        installMarker.writeText(
            buildString {
                appendLine("schema=${DshRuntimeManifest.SCHEMA_VERSION}")
                appendLine("cli=${RuntimeCliCatalog.DEEPSEEK_HARNESS}")
                appendLine("release=${target.releaseId}")
                appendLine("node=${target.nodeVersion}")
                appendLine("dsh=${target.dshVersion}")
                appendLine("abi=${target.androidAbi}")
            },
        )
    }

    /** Remove Node tarball + npm cache after a successful install to reclaim ~200 MB. */
    fun cleanupAfterInstall() {
        if (downloads.exists()) {
            deleteTreeWithoutFollowingLinks(downloads.toPath())
            downloads.mkdirs()
        }
        val npmCache = File(cliRoot, ".npm-cache")
        if (npmCache.exists()) {
            deleteTreeWithoutFollowingLinks(npmCache.toPath())
        }
        if (stagingNode.exists()) {
            deleteTreeWithoutFollowingLinks(stagingNode.toPath())
        }
    }

    private fun markerMatchesCurrentRelease(content: String): Boolean {
        val values = content.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.take(separator) to line.drop(separator + 1).trim()
        }.toMap()
        return values["schema"] == DshRuntimeManifest.SCHEMA_VERSION.toString() &&
            values["release"] == DshRuntimeManifest.RELEASE_ID
    }

    companion object {
        @Volatile private var sharedInstance: DshRuntimePaths? = null
        private val LOCK = Any()

        fun shared(context: Context): DshRuntimePaths {
            sharedInstance?.let { return it }
            return synchronized(LOCK) {
                sharedInstance ?: DshRuntimePaths(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }
}

