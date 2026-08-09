package com.agentdeck.app.data.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import com.agentdeck.app.domain.install.InstallPhase
import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.install.RecipeInstallation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class EmbeddedRuntimeInstaller(
    context: Context,
    private val paths: EmbeddedRuntimePaths = EmbeddedRuntimePaths(context),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) : RecipeInstallation {
    private val app = context.applicationContext
    private val installMutex = Mutex()

    override suspend fun install(
        recipeId: String,
        onProgress: (RecipeInstallProgress) -> Unit,
    ): Result<String> = try {
        val message = installMutex.withLock {
            require(recipeId == CODEX_RECIPE_ID) { "内嵌运行环境只支持 Codex 配方" }
            if (paths.isReady()) return@withLock "Codex 本机运行环境已可用"
            progress(InstallPhase.PROBING, onProgress)
            require(EmbeddedRuntimeManifest.deviceSupported()) { "当前版本仅支持 ARM64 Android 设备" }
            paths.ensureHostLayout()
            verifyPackagedRuntime()
            checkFreeSpace()

            paths.stagingRootfs.deleteRecursively()
            check(paths.stagingRootfs.mkdirs()) { "无法创建运行环境临时目录" }
            try {
                progress(InstallPhase.DOWNLOADING, onProgress)
                val rootfsArchive = download(EmbeddedRuntimeManifest.rootfs)
                val codexArchive = download(EmbeddedRuntimeManifest.codex)

                progress(InstallPhase.VERIFYING, onProgress)
                verify(rootfsArchive, EmbeddedRuntimeManifest.rootfs)
                verify(codexArchive, EmbeddedRuntimeManifest.codex)

                progress(InstallPhase.EXTRACTING, onProgress)
                SecureTarExtractor.extractGzipTar(rootfsArchive, paths.stagingRootfs)
                configureRootfs()
                installCodex(codexArchive)
                installCredentialHelper()

                progress(InstallPhase.INSTALLING_TOOLS, onProgress)
                installBaseTools()

                progress(InstallPhase.VERIFYING, onProgress)
                verifyStagingRuntime()
                paths.writeStagingMarker()
                promoteStagingRuntime()
            } catch (error: CancellationException) {
                paths.stagingRootfs.deleteRecursively()
                throw error
            } catch (error: Exception) {
                paths.stagingRootfs.deleteRecursively()
                throw error
            }
            progress(InstallPhase.COMPLETE, onProgress)
            "Codex 本机运行环境已安装并验证"
        }
        Result.success(message)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun verifyPackagedRuntime() {
        val required = listOf(paths.proot, paths.prootLoader, paths.packagedTalloc)
        require(required.all(File::isFile)) { "APK 缺少 ARM64 PRoot 运行组件" }
        require(paths.proot.canExecute() && paths.prootLoader.canExecute()) {
            "Android 未解出可执行的 PRoot 运行组件"
        }
    }

    private fun checkFreeSpace() {
        val available = StatFs(paths.root.absolutePath).availableBytes
        require(available >= MIN_FREE_SPACE_BYTES) {
            "存储空间不足；准备 Codex 至少需要 700 MB 可用空间"
        }
    }

    private suspend fun download(artifact: VerifiedArtifact): File = withContext(Dispatchers.IO) {
        val target = File(paths.cacheDir, artifact.fileName)
        if (target.isFile && runCatching { verify(target, artifact) }.isSuccess) {
            return@withContext target
        }
        val part = File(paths.cacheDir, ".${artifact.fileName}.part")
        part.delete()
        val request = Request.Builder().url(artifact.url).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载 ${artifact.fileName} 失败（HTTP ${response.code}）" }
            val body = response.body ?: error("下载 ${artifact.fileName} 未返回内容")
            val declaredLength = body.contentLength()
            require(declaredLength == -1L || declaredLength == artifact.sizeBytes) {
                "${artifact.fileName} 服务端长度与清单不一致"
            }
            FileOutputStream(part).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= artifact.sizeBytes) {
                            "${artifact.fileName} 下载内容超过清单大小"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        verify(part, artifact)
        target.delete()
        check(part.renameTo(target)) { "无法保存已验证的运行组件" }
        target
    }

    private fun verify(file: File, artifact: VerifiedArtifact) {
        require(file.isFile && file.length() == artifact.sizeBytes) {
            "${artifact.fileName} 文件大小校验失败"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        require(actual == artifact.sha256) { "${artifact.fileName} SHA-256 校验失败" }
    }

    private fun configureRootfs() {
        listOf(
            "root/projects/default",
            "run/agentdeck",
            "tmp",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg/updates",
            "etc/dpkg/dpkg.cfg.d",
            "usr/local/bin",
            "usr/local/lib/agentdeck",
        ).forEach { relative -> check(File(paths.stagingRootfs, relative).mkdirs() || File(paths.stagingRootfs, relative).isDirectory) }

        replaceRegularFile(
            File(paths.stagingRootfs, "etc/resolv.conf"),
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n",
        )
        replaceRegularFile(
            File(paths.stagingRootfs, "etc/hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
        )
        File(paths.stagingRootfs, "etc/dpkg/dpkg.cfg.d/force-unsafe-io")
            .writeText("force-unsafe-io\n")
    }

    private fun replaceRegularFile(file: File, content: String) {
        Files.deleteIfExists(file.toPath())
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun installCodex(archive: File) {
        val extractDir = File(paths.root, ".codex-extract")
        extractDir.deleteRecursively()
        check(extractDir.mkdirs()) { "无法创建 Codex 解压目录" }
        try {
            SecureTarExtractor.extractGzipTar(archive, extractDir)
            val source = extractDir.walkTopDown().firstOrNull {
                it.isFile && it.name == "codex-aarch64-unknown-linux-musl"
            } ?: error("Codex 归档缺少 ARM64 可执行文件")
            val destination = File(paths.stagingRootfs, "usr/local/bin/codex")
            source.copyTo(destination, overwrite = true)
            Os.chmod(destination.absolutePath, 0b111101101)
        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun installCredentialHelper() {
        val destination = File(
            paths.stagingRootfs,
            "usr/local/lib/agentdeck/codex-provider-token.py",
        )
        app.assets.open("wrappers/codex-provider-token.py").use { input ->
            FileOutputStream(destination).use(input::copyTo)
        }
        Os.chmod(destination.absolutePath, 0b111000000)
    }

    private suspend fun installBaseTools() {
        val result = EmbeddedProotProcess(paths, paths.stagingRootfs).execute(
            script = "set -e; apt-get update; apt-get install -y ca-certificates git python3; " +
                "apt-get clean; mkdir -p /root/projects/default",
            timeoutMillis = TOOLS_TIMEOUT_MILLIS,
        ).getOrThrow()
        check(result.commandSucceeded) {
            "安装基础工具失败：" + result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
        }
    }

    private suspend fun verifyStagingRuntime() {
        val result = EmbeddedProotProcess(paths, paths.stagingRootfs).execute(
            script = "set -e; test \"$(. /etc/os-release && printf %s \"${'$'}VERSION_ID\")\" = 24.04; " +
                "test -s /etc/ssl/certs/ca-certificates.crt; command -v git; command -v python3; " +
                "codex --version | grep -Eq '0[.]147[.]0'",
            timeoutMillis = VERIFY_TIMEOUT_MILLIS,
        ).getOrThrow()
        check(result.commandSucceeded) {
            "内嵌运行环境验证失败：" + result.stderr.ifBlank { result.stdout }.trim().takeLast(400)
        }
    }

    private fun promoteStagingRuntime() {
        if (paths.activeRootfs.exists()) {
            require(paths.isReady()) { "已有运行环境状态异常，未覆盖用户数据" }
            paths.stagingRootfs.deleteRecursively()
            return
        }
        check(paths.stagingRootfs.renameTo(paths.activeRootfs)) { "无法启用已验证的运行环境" }
    }

    private fun progress(
        phase: InstallPhase,
        callback: (RecipeInstallProgress) -> Unit,
    ) {
        callback(
            RecipeInstallProgress(
                recipeId = CODEX_RECIPE_ID,
                recipeName = "Codex 本机运行环境",
                recipeIndex = 0,
                recipeCount = 1,
                phase = phase,
            ),
        )
    }

    companion object {
        private const val CODEX_RECIPE_ID = "recipe_codex"
        private const val MIN_FREE_SPACE_BYTES = 700L * 1024 * 1024
        private const val TOOLS_TIMEOUT_MILLIS = 20L * 60 * 1_000
        private const val VERIFY_TIMEOUT_MILLIS = 60_000L
    }
}
