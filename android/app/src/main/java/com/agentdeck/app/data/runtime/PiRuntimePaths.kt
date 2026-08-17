package com.agentdeck.app.data.runtime

import android.content.Context
import com.agentdeck.app.domain.runtime.RuntimeCliCatalog
import com.agentdeck.app.domain.runtime.RuntimeLayoutContract
import java.io.File

/**
 * On-disk layout for pi under the shared agentdeck-runtime root.
 *
 * ```
 * runtimes/pi/
 *   node/                 # official Node linux tarball (may be copied from dsh)
 *   node_modules/         # npm install @earendil-works/pi-coding-agent
 *   downloads/
 *   .agentdeck-pi
 * pi-home/                # PI home / credentials — outside CLI delete by default
 * ```
 */
internal class PiRuntimePaths(
    context: Context,
) {
    private val app = context.applicationContext
    private val root = File(app.noBackupFilesDir, RuntimeLayoutContract.RUNTIME_ROOT_NAME)

    val cliRoot: File = File(root, RuntimeLayoutContract.cliRootRelative(RuntimeCliCatalog.PI))
    val downloads: File = File(root, RuntimeLayoutContract.downloadsRelative(RuntimeCliCatalog.PI))
    val piHome: File = File(root, "pi-home")
    val installMarker: File = File(cliRoot, ".agentdeck-pi")
    val nodeHome: File = File(cliRoot, "node")
    val nodeBinary: File = File(nodeHome, "bin/node")
    val npmBinary: File = File(nodeHome, "bin/npm")
    /** Official package bin → dist/cli.js */
    val piEntry: File = File(cliRoot, "node_modules/@earendil-works/pi-coding-agent/dist/cli.js")
    val stagingNode: File = File(cliRoot, ".node.staging")

    /** Node V8 compile cache (host); bound into guest as [NodeStartupSupport.GUEST_PI_CACHE]. */
    val nodeCompileCache: File = File(cliRoot, NodeStartupSupport.CACHE_DIR_NAME)

    fun ensureLayout() {
        listOf(cliRoot, downloads, piHome, nodeHome, nodeCompileCache).forEach { dir ->
            check(dir.mkdirs() || dir.isDirectory) { "无法创建 pi 运行目录" }
        }
    }

    fun isReady(): Boolean {
        if (!installMarker.isFile) return false
        if (!nodeBinary.isFile) return false
        if (!piEntry.isFile) return false
        return markerMatchesCurrentRelease(installMarker.readText())
    }

    fun usedBytes(): Long {
        if (!cliRoot.exists()) return 0L
        return sequenceOf(cliRoot, downloads).filter { it.exists() }.sumOf(::directorySize)
    }

    fun removeRuntime(includeUserHome: Boolean) {
        if (cliRoot.exists()) deleteTreeWithoutFollowingLinks(cliRoot.toPath())
        if (downloads.exists()) deleteTreeWithoutFollowingLinks(downloads.toPath())
        if (includeUserHome && piHome.exists()) {
            deleteTreeWithoutFollowingLinks(piHome.toPath())
        }
    }

    fun writeInstallMarker(target: PiRuntimeTarget) {
        ensureLayout()
        installMarker.writeText(
            buildString {
                appendLine("schema=${PiRuntimeManifest.SCHEMA_VERSION}")
                appendLine("cli=${RuntimeCliCatalog.PI}")
                appendLine("release=${target.releaseId}")
                appendLine("node=${target.nodeVersion}")
                appendLine("pi=${target.piVersion}")
                appendLine("abi=${target.androidAbi}")
            },
        )
    }

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

    /**
     * Optional one-shot note so users know chat gateways (dots) go here, not Codex.
     * Does not write secrets.
     */
    fun ensureDotsHintFile() {
        ensureLayout()
        val note = File(piHome, "AGENTDECK-OPENAI-COMPAT.txt")
        if (note.isFile) return
        note.writeText(
            """
            AgentDeck · pi 与 OpenAI 兼容网关（如小红书 dots）
            =================================================
            pi 不走 Codex Responses。在 pi 里用 /login 或环境变量配置 API Key，
            并通过 pi 的 models/providers 选择 OpenAI-compatible 端点。

            示例（需你在 pi 会话内完成，密钥不要写进本文件）：
              base URL: https://note3-prev-api.askdiandian.com/v1
              model id: dots3-note-prev
              协议: Chat Completions（不是 /v1/responses）

            官方: https://github.com/earendil-works/pi
            """.trimIndent() + "\n",
        )
    }

    private fun markerMatchesCurrentRelease(content: String): Boolean {
        val values = content.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.take(separator) to line.drop(separator + 1).trim()
        }.toMap()
        return values["schema"] == PiRuntimeManifest.SCHEMA_VERSION.toString() &&
            values["release"] == PiRuntimeManifest.RELEASE_ID
    }

    companion object {
        @Volatile private var sharedInstance: PiRuntimePaths? = null
        private val LOCK = Any()

        fun shared(context: Context): PiRuntimePaths {
            sharedInstance?.let { return it }
            return synchronized(LOCK) {
                sharedInstance ?: PiRuntimePaths(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }
}

