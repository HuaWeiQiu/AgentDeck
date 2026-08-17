package com.agentdeck.app.data.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.util.Log
import com.agentdeck.app.domain.install.InstallPhase
import com.agentdeck.app.domain.install.RecipeInstallProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs pi coding agent under `runtimes/pi/`.
 *
 * - Reuses an already-extracted dsh Node tree when the version matches (copy, not share-delete).
 * - Otherwise downloads the same official Node linux tarball as dsh.
 * - `npm install --ignore-scripts @earendil-works/pi-coding-agent@…` inside Codex PRoot.
 * - Smoke: `node …/cli.js --help` must succeed.
 *
 * Requires Codex runtime (PRoot + rootfs). Does not download a second Ubuntu.
 */
internal class PiRuntimeInstaller(
    context: Context,
    private val piPaths: PiRuntimePaths = PiRuntimePaths.shared(context),
    private val dshPaths: DshRuntimePaths = DshRuntimePaths.shared(context),
    private val codexPaths: EmbeddedRuntimePaths = EmbeddedRuntimePaths.shared(context),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val regionDetector: NetworkRegionDetector = NetworkRegionDetector(context, client),
) {
    private val installMutex = Mutex()

    suspend fun install(
        onProgress: (RecipeInstallProgress) -> Unit = {},
    ): Result<String> = try {
        val message = installMutex.withLock {
            if (piPaths.isReady()) return@withLock "pi 已可用"
            progress(InstallPhase.PROBING, onProgress)
            require(codexPaths.isReady()) {
                "请先准备 Codex 运行环境。pi 复用同一套本机 Linux，不会再下一份系统。"
            }
            val target = requireNotNull(PiRuntimeManifest.forDevice()) {
                "当前版本仅支持 ARM64 或 x86_64 Android 设备"
            }
            checkFreeSpace()
            piPaths.ensureLayout()
            val region = regionDetector.detect()

            if (!piNodeLooksHealthy()) {
                if (canReuseDshNode()) {
                    progress(InstallPhase.EXTRACTING, onProgress)
                    Log.i(TAG, "reusing Node from dsh install via proot cp -a")
                    copyNodeFromDshViaProot()
                } else {
                    progress(
                        InstallPhase.DOWNLOADING,
                        onProgress,
                        prefersDomestic = region == NetworkRegion.CHINA,
                    )
                    val nodeArchive = downloadArtifact(
                        cacheDir = piPaths.downloads,
                        artifact = target.node,
                        client = client,
                        region = region,
                        onBytes = { done ->
                            progress(
                                InstallPhase.DOWNLOADING,
                                onProgress,
                                bytesDone = done,
                                bytesTotal = target.node.sizeBytes,
                                prefersDomestic = region == NetworkRegion.CHINA,
                            )
                        },
                    )
                    progress(InstallPhase.VERIFYING_ARTIFACTS, onProgress)
                    verifyArtifact(nodeArchive, target.node)
                    progress(InstallPhase.EXTRACTING, onProgress)
                    extractNode(nodeArchive)
                }
            }

            // Always re-apply execute bits (partial installs / bad copies).
            ensureNodeTreeExecutable()
            progress(
                InstallPhase.INSTALLING_TOOLS,
                onProgress,
                prefersDomestic = region == NetworkRegion.CHINA,
            )
            npmInstallPi(target, region)

            progress(InstallPhase.VERIFYING_RUNTIME, onProgress)
            require(piPaths.nodeBinary.isFile) { "Node 未正确解压" }
            require(piPaths.piEntry.isFile) { "pi 包未安装完整" }
            val help = smokePiHelp()
            runCatching { Os.chmod(piPaths.nodeBinary.absolutePath, 0b111_101_101) }
            piPaths.cleanupAfterInstall()
            piPaths.writeInstallMarker(target)
            piPaths.ensureDotsHintFile()
            progress(InstallPhase.COMPLETE, onProgress)
            "pi 已安装并验证（${target.piVersion}）。冒烟：${help.take(80)}"
        }
        Result.success(message)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun uninstall(includeUserHome: Boolean = false): Result<String> = runCatching {
        piPaths.removeRuntime(includeUserHome = includeUserHome)
        if (includeUserHome) {
            "已删除 pi 组件与 pi-home 配置目录"
        } else {
            "已删除 pi 组件；pi-home 配置仍保留"
        }
    }

    /** Run `pi --help` (via node entry) under proot; for UI verify button. */
    suspend fun smokeHelp(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(piPaths.isReady()) { "请先安装 pi" }
            smokePiHelp()
        }
    }

    private fun canReuseDshNode(): Boolean {
        if (!dshPaths.nodeBinary.isFile) return false
        // dsh marker carries node=v24.19.0 when current release.
        val marker = dshPaths.installMarker
        if (!marker.isFile) return dshPaths.nodeBinary.isFile
        val nodeLine = marker.readText().lineSequence()
            .firstOrNull { it.startsWith("node=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
        return nodeLine.isEmpty() || nodeLine == PiRuntimeManifest.NODE_VERSION ||
            nodeLine == PiRuntimeManifest.NODE_VERSION.removePrefix("v")
    }

    /** True when node + npm entry exist and look usable (not a broken host-side copy). */
    private fun piNodeLooksHealthy(): Boolean {
        if (!piPaths.nodeBinary.isFile) return false
        // Official linux tarball: npm is a shell/node script next to node, with
        // lib/node_modules/npm present. Broken Android copies often keep npm but drop lib.
        val npmCli = File(piPaths.nodeHome, "lib/node_modules/npm/bin/npm-cli.js")
        return npmCli.isFile
    }

    /**
     * Host [File.copyRecursively] breaks Node's bin → lib symlinks and execute bits.
     * Copy inside PRoot with `cp -a` so the tree matches the official tarball layout.
     */
    private suspend fun copyNodeFromDshViaProot() = withContext(Dispatchers.IO) {
        if (piPaths.nodeHome.exists()) {
            deleteTreeWithoutFollowingLinks(piPaths.nodeHome.toPath())
        }
        check(piPaths.nodeHome.mkdirs() || piPaths.nodeHome.isDirectory) {
            "无法创建 pi Node 目录"
        }
        val script = """
            set -euo pipefail
            test -x /opt/agentdeck-dsh/node/bin/node
            test -f /opt/agentdeck-dsh/node/lib/node_modules/npm/bin/npm-cli.js
            rm -rf /opt/agentdeck-pi/node
            mkdir -p /opt/agentdeck-pi
            cp -a /opt/agentdeck-dsh/node /opt/agentdeck-pi/node
            test -x /opt/agentdeck-pi/node/bin/node
            test -f /opt/agentdeck-pi/node/lib/node_modules/npm/bin/npm-cli.js
            # Force +x in case bind mount bits are odd
            chmod -R a+rX /opt/agentdeck-pi/node/bin || true
            chmod a+x /opt/agentdeck-pi/node/bin/node || true
            echo "node-copy-ok"
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = 5L * 60L * 1000L,
            workingDirectory = "/opt/agentdeck-pi",
            extraBinds = listOf(
                piPaths.cliRoot.absolutePath to "/opt/agentdeck-pi",
                dshPaths.cliRoot.absolutePath to "/opt/agentdeck-dsh",
            ),
        ).getOrThrow()
        require(result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
            "从 dsh 复制 Node 失败：$detail"
        }
        require(piNodeLooksHealthy()) { "复制后 Node 树仍不完整" }
        ensureNodeTreeExecutable()
    }

    private fun ensureNodeTreeExecutable() {
        val binDir = File(piPaths.nodeHome, "bin")
        if (!binDir.isDirectory) return
        binDir.listFiles()?.forEach { file ->
            // chmod the path even if it's a symlink target
            runCatching { Os.chmod(file.absolutePath, 0b111_101_101) } // 0755
        }
        runCatching { Os.chmod(piPaths.nodeBinary.absolutePath, 0b111_101_101) }
        if (piPaths.npmBinary.isFile) {
            runCatching { Os.chmod(piPaths.npmBinary.absolutePath, 0b111_101_101) }
        }
    }

    private fun checkFreeSpace() {
        val probe = sequenceOf(
            piPaths.cliRoot,
            piPaths.cliRoot.parentFile,
            piPaths.cliRoot.parentFile?.parentFile,
        ).firstOrNull { it != null && it.isDirectory } ?: piPaths.cliRoot
        if (!probe.exists()) probe.mkdirs()
        val available = try {
            StatFs(probe.absolutePath).availableBytes
        } catch (error: Exception) {
            throw IllegalStateException(
                "无法检查存储空间：" + (error.message ?: error.javaClass.simpleName),
                error,
            )
        }
        require(available >= MIN_FREE_SPACE_BYTES) {
            "存储空间不足；准备 pi 大约需要 500 MB 可用空间"
        }
    }

    private fun extractNode(archive: File) {
        val staging = piPaths.stagingNode
        if (staging.exists()) deleteTreeWithoutFollowingLinks(staging.toPath())
        check(staging.mkdirs()) { "无法创建 Node 解压目录" }
        try {
            SecureTarExtractor.extractGzipTar(archive, staging)
            val extracted = staging.listFiles()?.singleOrNull { it.isDirectory }
                ?: error("Node 归档结构异常")
            if (piPaths.nodeHome.exists()) {
                deleteTreeWithoutFollowingLinks(piPaths.nodeHome.toPath())
            }
            if (!extracted.renameTo(piPaths.nodeHome)) {
                extracted.copyRecursively(piPaths.nodeHome, overwrite = true)
                deleteTreeWithoutFollowingLinks(extracted.toPath())
            }
            require(piPaths.nodeBinary.isFile) { "Node 归档缺少 bin/node" }
            ensureNodeTreeExecutable()
        } finally {
            if (staging.exists()) deleteTreeWithoutFollowingLinks(staging.toPath())
        }
    }

    private suspend fun npmInstallPi(
        target: PiRuntimeTarget,
        region: NetworkRegion,
    ) = withContext(Dispatchers.IO) {
        val primaryRegistry = npmRegistryForRegion(region)
        val fallbackRegistry = when (region) {
            NetworkRegion.CHINA -> "https://registry.npmjs.org"
            NetworkRegion.OVERSEAS -> "https://registry.npmmirror.com"
        }
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-pi/node/bin:${'$'}PATH"
            export npm_config_fund=false
            export npm_config_audit=false
            export npm_config_update_notifier=false
            export npm_config_prefer_offline=false
            export npm_config_fetch_retries=3
            export npm_config_fetch_retry_mintimeout=10000
            export npm_config_fetch_retry_maxtimeout=60000
            export npm_config_cache="/opt/agentdeck-pi/.npm-cache"
            mkdir -p /opt/agentdeck-pi/.npm-cache
            cd /opt/agentdeck-pi
            command -v npm >/dev/null
            install_with_registry() {
              local registry="${'$'}1"
              echo "npm-registry ${'$'}registry"
              # Official pi install uses --ignore-scripts.
              npm install --ignore-scripts --omit=dev --no-package-lock \
                --prefer-online \
                --registry="${'$'}registry" \
                ${PiRuntimeManifest.PI_NPM_SPEC}
            }
            if ! install_with_registry "$primaryRegistry"; then
              echo "npm primary registry failed, trying fallback"
              rm -rf node_modules/@earendil-works
              install_with_registry "$fallbackRegistry"
            fi
            test -f /opt/agentdeck-pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js
            echo "pi-npm-ok ${target.piVersion}"
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = NPM_INSTALL_TIMEOUT_MS,
            workingDirectory = "/opt/agentdeck-pi",
            extraBinds = listOf(piPaths.cliRoot.absolutePath to "/opt/agentdeck-pi"),
        ).getOrThrow()
        require(result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
            "npm 安装 pi 失败（退出码 ${result.exitCode}）：$detail"
        }
    }

    private suspend fun smokePiHelp(): String = withContext(Dispatchers.IO) {
        piPaths.ensureLayout()
        // Install smoke also seeds NODE_COMPILE_CACHE so the first chat start is warmer.
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-pi/node/bin:${'$'}PATH"
            export HOME="/opt/agentdeck-pi-home"
            export PI_HOME="/opt/agentdeck-pi-home"
            ${NodeStartupSupport.nodeOptionsExport(160)}
            ${NodeStartupSupport.shellExports(NodeStartupSupport.GUEST_PI_CACHE)}
            mkdir -p "${'$'}HOME"
            cd /opt/agentdeck-pi
            node /opt/agentdeck-pi/node_modules/@earendil-works/pi-coding-agent/dist/cli.js --help
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = SMOKE_TIMEOUT_MS,
            workingDirectory = "/opt/agentdeck-pi",
            extraBinds = listOf(
                piPaths.cliRoot.absolutePath to "/opt/agentdeck-pi",
                piPaths.piHome.absolutePath to "/opt/agentdeck-pi-home",
            ),
        ).getOrThrow()
        require(result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
            "pi --help 失败：$detail"
        }
        val out = result.stdout.ifBlank { result.stderr }.trim()
        require(out.isNotBlank()) { "pi --help 无输出" }
        // Keep a short proof line for logs/UI.
        out.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
            ?: out.take(120)
    }

    private fun progress(
        phase: InstallPhase,
        callback: (RecipeInstallProgress) -> Unit,
        bytesDone: Long? = null,
        bytesTotal: Long? = null,
        prefersDomestic: Boolean? = null,
    ) {
        callback(
            RecipeInstallProgress(
                recipeId = RECIPE_ID,
                recipeName = "pi",
                recipeIndex = 0,
                recipeCount = 1,
                phase = phase,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                prefersDomesticSources = prefersDomestic,
            ),
        )
    }

    companion object {
        const val RECIPE_ID = "recipe_pi"
        private const val TAG = "AgentDeckRuntime"
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024
        private const val NPM_INSTALL_TIMEOUT_MS = 15L * 60L * 1000L
        private const val SMOKE_TIMEOUT_MS = 90_000L
    }
}
