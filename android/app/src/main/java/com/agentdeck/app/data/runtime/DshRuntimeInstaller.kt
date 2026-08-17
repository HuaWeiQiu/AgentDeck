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
 * Downloads official Node.js linux tarball, extracts under `runtimes/deepseek-harness/`,
 * then runs `npm install @deepseek-ai/dsh@…` inside the existing Codex PRoot rootfs
 * with the CLI tree bound at `/opt/agentdeck-dsh`.
 *
 * Requires Codex runtime already ready (PRoot + rootfs). Does not download a second Ubuntu.
 *
 * **Native modules:** stock `node-pty` has no linux-arm64 prebuild, and the shared Codex
 * rootfs cannot reliably `apt install build-essential` (dpkg incomplete). After the JS
 * tree is installed we **replace** `node_modules/node-pty` with
 * `@homebridge/node-pty-prebuilt-multiarch` (linux-arm64 ABI prebuilds, incl. Node 24).
 */
internal class DshRuntimeInstaller(
    context: Context,
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
            if (dshPaths.isReady()) return@withLock "DeepSeek Harness 已可用"
            progress(InstallPhase.PROBING, onProgress)
            require(codexPaths.isReady()) {
                "请先准备 Codex 运行环境。dsh 复用同一套本机 Linux，不会再下一份系统。"
            }
            val target = requireNotNull(DshRuntimeManifest.forDevice()) {
                "当前版本仅支持 ARM64 或 x86_64 Android 设备"
            }
            checkFreeSpace()
            dshPaths.ensureLayout()
            val region = regionDetector.detect()

            // Repair: JS tree present but node-pty native missing (older installs).
            if (dshPaths.dshEntry.isFile && dshPaths.nodeBinary.isFile && !dshPaths.hasNodePtyNative()) {
                Log.i(TAG, "repairing node-pty with prebuilt multiarch package")
                progress(
                    InstallPhase.INSTALLING_TOOLS,
                    onProgress,
                    prefersDomestic = region == NetworkRegion.CHINA,
                )
                installNodePtyPrebuilt(region)
            } else {
                progress(
                    InstallPhase.DOWNLOADING,
                    onProgress,
                    prefersDomestic = region == NetworkRegion.CHINA,
                )
                val nodeArchive = downloadArtifact(
                    cacheDir = dshPaths.downloads,
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

                progress(
                    InstallPhase.INSTALLING_TOOLS,
                    onProgress,
                    prefersDomestic = region == NetworkRegion.CHINA,
                )
                npmInstallDsh(target, region)
                installNodePtyPrebuilt(region)
            }

            progress(InstallPhase.VERIFYING_RUNTIME, onProgress)
            require(dshPaths.nodeBinary.isFile) { "Node 未正确解压" }
            require(dshPaths.dshEntry.isFile) { "dsh 包未安装完整" }
            require(dshPaths.hasNodePtyNative()) {
                "node-pty 原生模块未就绪。请重试安装。"
            }
            // Smoke-load native binding inside proot (catches ABI mismatch early).
            verifyNodePtyLoads()
            runCatching { Os.chmod(dshPaths.nodeBinary.absolutePath, 0b111_101_101) }
            dshPaths.cleanupAfterInstall()
            dshPaths.writeInstallMarker(target)
            progress(InstallPhase.COMPLETE, onProgress)
            "DeepSeek Harness 已安装并验证（Node ${target.nodeVersion} + dsh ${target.dshVersion}）"
        }
        Result.success(message)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    fun uninstall(includeUserHome: Boolean = false): Result<String> = runCatching {
        dshPaths.removeRuntime(includeUserHome = includeUserHome)
        if (includeUserHome) {
            "已删除 dsh 组件与本机 dsh 配置目录"
        } else {
            "已删除 dsh 组件；dsh-home 配置仍保留"
        }
    }

    private fun checkFreeSpace() {
        val probe = sequenceOf(
            dshPaths.cliRoot,
            dshPaths.cliRoot.parentFile,
            dshPaths.cliRoot.parentFile?.parentFile,
        ).firstOrNull { it != null && it.isDirectory } ?: dshPaths.cliRoot
        if (!probe.exists()) {
            probe.mkdirs()
        }
        val available = try {
            StatFs(probe.absolutePath).availableBytes
        } catch (error: Exception) {
            throw IllegalStateException(
                "无法检查存储空间：" + (error.message ?: error.javaClass.simpleName),
                error,
            )
        }
        require(available >= MIN_FREE_SPACE_BYTES) {
            "存储空间不足；准备 dsh 大约需要 500 MB 可用空间"
        }
    }

    private fun extractNode(archive: File) {
        val staging = dshPaths.stagingNode
        if (staging.exists()) deleteTreeWithoutFollowingLinks(staging.toPath())
        check(staging.mkdirs()) { "无法创建 Node 解压目录" }
        try {
            SecureTarExtractor.extractGzipTar(archive, staging)
            val extracted = staging.listFiles()?.singleOrNull { it.isDirectory }
                ?: error("Node 归档结构异常")
            if (dshPaths.nodeHome.exists()) {
                deleteTreeWithoutFollowingLinks(dshPaths.nodeHome.toPath())
            }
            if (!extracted.renameTo(dshPaths.nodeHome)) {
                extracted.copyRecursively(dshPaths.nodeHome, overwrite = true)
                deleteTreeWithoutFollowingLinks(extracted.toPath())
            }
            require(dshPaths.nodeBinary.isFile) { "Node 归档缺少 bin/node" }
        } finally {
            if (staging.exists()) deleteTreeWithoutFollowingLinks(staging.toPath())
        }
    }

    private suspend fun npmInstallDsh(
        target: DshRuntimeTarget,
        region: NetworkRegion,
    ) = withContext(Dispatchers.IO) {
        val primaryRegistry = npmRegistryForRegion(region)
        val fallbackRegistry = when (region) {
            NetworkRegion.CHINA -> "https://registry.npmjs.org"
            NetworkRegion.OVERSEAS -> "https://registry.npmmirror.com"
        }
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-dsh/node/bin:${'$'}PATH"
            export npm_config_fund=false
            export npm_config_audit=false
            export npm_config_update_notifier=false
            export npm_config_prefer_offline=false
            export npm_config_fetch_retries=3
            export npm_config_fetch_retry_mintimeout=10000
            export npm_config_fetch_retry_maxtimeout=60000
            export npm_config_cache="/opt/agentdeck-dsh/.npm-cache"
            mkdir -p /opt/agentdeck-dsh/.npm-cache
            cd /opt/agentdeck-dsh
            command -v npm >/dev/null
            install_with_registry() {
              local registry="${'$'}1"
              echo "npm-registry ${'$'}registry"
              npm install --ignore-scripts --omit=dev --no-package-lock \
                --prefer-online \
                --registry="${'$'}registry" \
                ${DshRuntimeManifest.DSH_NPM_SPEC}
            }
            if ! install_with_registry "$primaryRegistry"; then
              echo "npm primary registry failed, trying fallback"
              rm -rf node_modules
              install_with_registry "$fallbackRegistry"
            fi
            test -f /opt/agentdeck-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js
            echo "dsh-npm-ok ${target.dshVersion}"
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = NPM_INSTALL_TIMEOUT_MS,
            workingDirectory = "/opt/agentdeck-dsh",
            extraBinds = listOf(dshPaths.cliRoot.absolutePath to "/opt/agentdeck-dsh"),
        ).getOrThrow()
        require(result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
            "npm 安装 dsh 失败（退出码 ${result.exitCode}）：$detail"
        }
    }

    /**
     * Replace stock `node-pty` (no linux-arm64 prebuild) with the Homebridge multiarch
     * package that ships `prebuilds/linux-arm64/node.abi*.node` for current Node ABIs.
     * Exposed as `node_modules/node-pty` so dsh's `require("node-pty")` keeps working.
     */
    private suspend fun installNodePtyPrebuilt(region: NetworkRegion) = withContext(Dispatchers.IO) {
        val primaryRegistry = npmRegistryForRegion(region)
        val fallbackRegistry = when (region) {
            NetworkRegion.CHINA -> "https://registry.npmjs.org"
            NetworkRegion.OVERSEAS -> "https://registry.npmmirror.com"
        }
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-dsh/node/bin:${'$'}PATH"
            export npm_config_fund=false
            export npm_config_audit=false
            export npm_config_update_notifier=false
            export npm_config_cache="/opt/agentdeck-dsh/.npm-cache"
            mkdir -p /opt/agentdeck-dsh/.npm-cache
            cd /opt/agentdeck-dsh
            install_prebuilt() {
              local registry="${'$'}1"
              echo "node-pty-prebuilt-registry ${'$'}registry"
              npm install --ignore-scripts --omit=dev --no-package-lock --prefer-online \
                --registry="${'$'}registry" \
                ${NODE_PTY_PREBUILT_SPEC}
            }
            if ! install_prebuilt "$primaryRegistry"; then
              echo "node-pty prebuilt primary failed, trying fallback"
              install_prebuilt "$fallbackRegistry"
            fi
            test -d node_modules/@homebridge/node-pty-prebuilt-multiarch
            # Point require("node-pty") at the prebuilt package.
            rm -rf node_modules/node-pty
            # Prefer hard copy: some proot bind setups are awkward with deep symlinks.
            cp -a node_modules/@homebridge/node-pty-prebuilt-multiarch node_modules/node-pty
            # Keep scoped package too (optional deps / nested requires).
            abi="${'$'}(node -p "process.versions.modules")"
            arch="${'$'}(uname -m)"
            case "${'$'}arch" in
              aarch64|arm64) plat=linux-arm64 ;;
              x86_64|amd64) plat=linux-x64 ;;
              *) echo "unsupported arch ${'$'}arch"; exit 1 ;;
            esac
            pre="node_modules/node-pty/prebuilds/${'$'}plat/node.abi${'$'}{abi}.node"
            test -f "${'$'}pre"
            echo "node-pty-prebuilt-ok ${'$'}pre"
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = PREBUILT_INSTALL_TIMEOUT_MS,
            workingDirectory = "/opt/agentdeck-dsh",
            extraBinds = listOf(dshPaths.cliRoot.absolutePath to "/opt/agentdeck-dsh"),
        ).getOrThrow()
        require(result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(500)
            "安装预编译 node-pty 失败（退出码 ${result.exitCode}）：$detail"
        }
        Log.i(TAG, "node-pty prebuilt installed")
    }

    private suspend fun verifyNodePtyLoads() = withContext(Dispatchers.IO) {
        dshPaths.ensureLayout()
        // Seeds NODE_COMPILE_CACHE for node-pty + a cheap require of the dsh entry.
        val script = """
            set -euo pipefail
            export PATH="/opt/agentdeck-dsh/node/bin:${'$'}PATH"
            ${NodeStartupSupport.nodeOptionsExport(160)}
            ${NodeStartupSupport.shellExports(NodeStartupSupport.GUEST_DSH_CACHE)}
            node -e "require('node-pty'); console.log('node-pty-load-ok')"
            # Best-effort module graph touch (must not start the web server).
            node -e "require('/opt/agentdeck-dsh/node_modules/@deepseek-ai/dsh/lib/bin.js'); console.log('dsh-entry-touch-ok')" >/dev/null 2>&1 || true
        """.trimIndent()
        val result = EmbeddedProotProcess(codexPaths).executeWithExtraBinds(
            script = script,
            timeoutMillis = 60_000L,
            workingDirectory = "/opt/agentdeck-dsh",
            extraBinds = listOf(dshPaths.cliRoot.absolutePath to "/opt/agentdeck-dsh"),
        ).getOrThrow()
        require(result.commandSucceeded) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
            "node-pty 无法加载：$detail"
        }
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
                recipeName = "DeepSeek Harness",
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
        const val RECIPE_ID = "recipe_deepseek_harness"
        private const val TAG = "AgentDeckRuntime"
        private const val NODE_PTY_PREBUILT_SPEC = "@homebridge/node-pty-prebuilt-multiarch@0.14.1"
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024
        private const val NPM_INSTALL_TIMEOUT_MS = 15L * 60L * 1000L
        private const val PREBUILT_INSTALL_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
