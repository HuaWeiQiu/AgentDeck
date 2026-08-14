package com.agentdeck.app.data.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import com.agentdeck.app.domain.install.InstallPhase
import com.agentdeck.app.domain.install.RecipeInstallProgress
import com.agentdeck.app.domain.install.RecipeInstallation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
    private val regionDetector: NetworkRegionDetector = NetworkRegionDetector(context, client),
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
            val runtime = requireNotNull(paths.runtimeTarget) {
                "当前版本仅支持 ARM64 或 x86_64 Android 设备"
            }
            // 按出口 IP 判定国内外源顺序；探测失败时软回退（locale/时区）。
            val region = regionDetector.detect()
            paths.ensureHostLayout()
            verifyPackagedRuntime()
            checkFreeSpace()

            paths.stagingRootfs.deleteRecursively()
            check(paths.stagingRootfs.mkdirs()) { "无法创建运行环境临时目录" }
            try {
                progress(InstallPhase.DOWNLOADING, onProgress)
                val totalBytes = runtime.rootfs.sizeBytes + runtime.codex.sizeBytes
                val rootfsArchive = download(
                    runtime.rootfs,
                    completedBytes = 0L,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                    region = region,
                )
                val codexArchive = download(
                    runtime.codex,
                    completedBytes = runtime.rootfs.sizeBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                    region = region,
                )

                progress(InstallPhase.VERIFYING_ARTIFACTS, onProgress)
                verify(rootfsArchive, runtime.rootfs)
                verify(codexArchive, runtime.codex)

                progress(InstallPhase.EXTRACTING, onProgress)
                SecureTarExtractor.extractGzipTar(rootfsArchive, paths.stagingRootfs)
                configureRootfs(runtime.androidAbi, region)
                installCodex(codexArchive, runtime)
                installRuntimeHelpers()

                progress(InstallPhase.INSTALLING_TOOLS, onProgress)
                installBaseTools(runtime.androidAbi, region)

                progress(InstallPhase.VERIFYING_RUNTIME, onProgress)
                verifyStagingRuntime()
                paths.writeStagingMarker()
                promoteStagingRuntime()
                runCatching { paths.removeObsoleteRuntimeRoots() }
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
        require(required.all(File::isFile)) { "APK 缺少当前架构的 PRoot 运行组件" }
        require(paths.proot.canExecute() && paths.prootLoader.canExecute()) {
            "Android 未解出可执行的 PRoot 运行组件"
        }
    }

    private fun checkFreeSpace() {
        val available = StatFs(paths.root.absolutePath).availableBytes
        require(hasRequiredRuntimeSpace(available)) {
            "存储空间不足；准备 Codex 至少需要 1.1 GB 可用空间"
        }
    }

    private suspend fun download(
        artifact: VerifiedArtifact,
        completedBytes: Long,
        totalBytes: Long,
        onProgress: (RecipeInstallProgress) -> Unit,
        region: NetworkRegion,
    ): File = withContext(Dispatchers.IO) {
        var lastReportedBytes = -PROGRESS_MIN_BYTES
        var lastReportedAtNanos = 0L
        downloadArtifact(paths.cacheDir, artifact, client, region) { artifactBytesDone ->
            val now = System.nanoTime()
            val shouldReport = artifactBytesDone == artifact.sizeBytes ||
                artifactBytesDone - lastReportedBytes >= PROGRESS_MIN_BYTES ||
                now - lastReportedAtNanos >= PROGRESS_MIN_INTERVAL_NANOS
            if (shouldReport) {
                progress(
                    InstallPhase.DOWNLOADING,
                    onProgress,
                    bytesDone = completedBytes + artifactBytesDone,
                    bytesTotal = totalBytes,
                )
                lastReportedBytes = artifactBytesDone
                lastReportedAtNanos = now
            }
        }
    }

    private fun verify(file: File, artifact: VerifiedArtifact) = verifyArtifact(file, artifact)

    private fun configureRootfs(androidAbi: String, region: NetworkRegion) {
        listOf(
            "root/projects/default",
            "root/.codex",
            "run/agentdeck",
            "tmp",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg/updates",
            "etc/dpkg/dpkg.cfg.d",
            "etc/apt/apt.conf.d",
            "etc/apt/sources.list.d",
            "usr/local/bin",
            "usr/local/lib/agentdeck",
        ).forEach { relative -> check(File(paths.stagingRootfs, relative).mkdirs() || File(paths.stagingRootfs, relative).isDirectory) }

        replaceRegularFile(
            File(paths.stagingRootfs, "etc/resolv.conf"),
            embeddedRuntimeResolvConf(region),
        )
        replaceRegularFile(
            File(paths.stagingRootfs, "etc/hosts"),
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
        )
        // Ubuntu Base 可能自带 ports.ubuntu.com / deb822 sources；清掉后按区域写入镜像列表。
        File(paths.stagingRootfs, "etc/apt/sources.list.d").listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".list") || it.name.endsWith(".sources")) }
            ?.forEach { source -> check(source.delete()) { "无法清理默认软件源 ${source.name}" } }
        replaceRegularFile(
            File(paths.stagingRootfs, "etc/apt/sources.list"),
            embeddedAptSourcesList(androidAbi, region = region),
        )
        replaceRegularFile(
            File(paths.stagingRootfs, "etc/apt/apt.conf.d/80-agentdeck"),
            embeddedAptConf(),
        )
        File(paths.stagingRootfs, "etc/dpkg/dpkg.cfg.d/force-unsafe-io")
            .writeText("force-unsafe-io\n")
    }

    private fun replaceRegularFile(file: File, content: String) {
        Files.deleteIfExists(file.toPath())
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun installCodex(archive: File, runtime: EmbeddedRuntimeTarget) {
        val extractDir = File(paths.root, ".codex-extract")
        extractDir.deleteRecursively()
        check(extractDir.mkdirs()) { "无法创建 Codex 解压目录" }
        try {
            SecureTarExtractor.extractGzipTar(archive, extractDir)
            val source = extractDir.walkTopDown().firstOrNull {
                it.isFile && it.name == runtime.codexBinaryName
            } ?: error("Codex 归档缺少 ${runtime.androidAbi} 可执行文件")
            val destination = File(paths.stagingRootfs, "usr/local/bin/codex")
            source.copyTo(destination, overwrite = true)
            Os.chmod(destination.absolutePath, 0b111101101)
        } finally {
            extractDir.deleteRecursively()
        }
    }

    private fun installRuntimeHelpers() {
        listOf(
            "codex-provider-token.py",
            "agentdeck-file-adapter.py",
        ).forEach { name ->
            val destination = File(paths.stagingRootfs, "usr/local/lib/agentdeck/$name")
            app.assets.open("wrappers/$name").use { input ->
                FileOutputStream(destination).use(input::copyTo)
            }
            Os.chmod(destination.absolutePath, 0b111000000)
        }
        // Guest CLI for L1 host workspace IPC (ADR-0011)
        val hostCli = File(paths.stagingRootfs, "usr/local/bin/agentdeck-host")
        app.assets.open("wrappers/agentdeck-host").use { input ->
            FileOutputStream(hostCli).use(input::copyTo)
        }
        Os.chmod(hostCli.absolutePath, 0b111101101)
    }

    private suspend fun installBaseTools(androidAbi: String, region: NetworkRegion) {
        val result = EmbeddedProotProcess(paths, paths.stagingRootfs).execute(
            script = embeddedInstallBaseToolsScript(androidAbi, region),
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
                "command -v pdftotext; test -x /usr/local/lib/agentdeck/agentdeck-file-adapter.py; " +
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
        bytesDone: Long? = null,
        bytesTotal: Long? = null,
    ) {
        callback(
            RecipeInstallProgress(
                recipeId = CODEX_RECIPE_ID,
                recipeName = "Codex 本机运行环境",
                recipeIndex = 0,
                recipeCount = 1,
                phase = phase,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
            ),
        )
    }

    companion object {
        private const val CODEX_RECIPE_ID = "recipe_codex"
        // A fresh install peaks near 900 MiB on the ARM64 device once the rootfs,
        // package metadata, Codex binary, and verified download cache coexist.
        private const val TOOLS_TIMEOUT_MILLIS = 20L * 60 * 1_000
        private const val VERIFY_TIMEOUT_MILLIS = 60_000L
        private const val PROGRESS_MIN_BYTES = 512L * 1024
        private const val PROGRESS_MIN_INTERVAL_NANOS = 250L * 1_000 * 1_000
    }
}

internal fun hasRequiredRuntimeSpace(availableBytes: Long): Boolean =
    availableBytes >= MIN_FREE_SPACE_BYTES

private const val MIN_FREE_SPACE_BYTES = 1_100L * 1024 * 1024

/**
 * 下载 [artifact] 到 [cacheDir]，支持 `.part` 断点续传与有限次网络重试。
 * 最终 SHA-256 校验失败或长度不符直接抛错（不重试）；仅 IOException 触发重试。
 * [onBytes] 报告该文件已落盘字节数（含续传部分，可能反复递增）。
 */
internal suspend fun downloadArtifact(
    cacheDir: File,
    artifact: VerifiedArtifact,
    client: OkHttpClient,
    region: NetworkRegion = NetworkRegion.CHINA,
    onBytes: (bytesDone: Long) -> Unit = {},
): File {
    val target = File(cacheDir, artifact.fileName)
    if (target.isFile && runCatching { verifyArtifact(target, artifact) }.isSuccess) {
        onBytes(artifact.sizeBytes)
        return target
    }
    val part = File(cacheDir, ".${artifact.fileName}.part")
    if (part.isFile && part.length() > artifact.sizeBytes) {
        check(part.delete()) { "无法清理 ${artifact.fileName} 的残留下载" }
    }
    val orderedUrls = orderUrlsForRegion(artifact.urls, region)
    downloadWithUrlFallback(client, artifact, part, orderedUrls, onBytes)
    verifyArtifact(part, artifact)
    target.delete()
    check(part.renameTo(target)) { "无法保存已验证的运行组件" }
    return target
}

/**
 * Try each mirror/URL in order. Network failures are retried per URL; on hard
 * failure switch to the next URL and discard any partial from the previous host
 * so we never mix bytes from different sources.
 */
private suspend fun downloadWithUrlFallback(
    client: OkHttpClient,
    artifact: VerifiedArtifact,
    part: File,
    urls: List<String>,
    onBytes: (bytesDone: Long) -> Unit,
) {
    var lastError: Exception? = null
    for ((index, url) in urls.withIndex()) {
        try {
            withNetworkRetries {
                downloadOnce(client, url, artifact, part, onBytes)
            }
            return
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            // Do not resume a partial across different mirrors.
            if (part.isFile) {
                check(part.delete()) { "无法清理 ${artifact.fileName} 的残留下载" }
            }
            onBytes(0L)
            if (index == urls.lastIndex) break
        }
    }
    throw lastError ?: IllegalStateException("下载 ${artifact.fileName} 失败：无可用源")
}

/** 单次下载尝试；`.part` 已存在时用 Range 续传，服务端忽略 Range（200）则全量重下。 */
private fun downloadOnce(
    client: OkHttpClient,
    url: String,
    artifact: VerifiedArtifact,
    part: File,
    onBytes: (bytesDone: Long) -> Unit,
) {
    val resumedBytes = if (part.isFile) part.length() else 0L
    if (resumedBytes == artifact.sizeBytes) {
        onBytes(resumedBytes)
        return
    }
    val request = Request.Builder().url(url).get().apply {
        if (resumedBytes > 0) header("Range", "bytes=$resumedBytes-")
    }.build()
    client.newCall(request).execute().use { response ->
        check(response.isSuccessful) {
            "下载 ${artifact.fileName} 失败（HTTP ${response.code}，源: $url）"
        }
        val resuming = resumedBytes > 0 && response.code == HTTP_PARTIAL
        if (resumedBytes > 0 && !resuming) {
            check(part.delete()) { "无法清理 ${artifact.fileName} 的残留下载" }
        }
        val body = response.body ?: error("下载 ${artifact.fileName} 未返回内容")
        val declaredLength = body.contentLength()
        val expectedLength = artifact.sizeBytes - if (resuming) resumedBytes else 0L
        require(declaredLength == -1L || declaredLength == expectedLength) {
            "${artifact.fileName} 服务端长度与清单不一致"
        }
        FileOutputStream(part, resuming).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                var total = if (resuming) resumedBytes else 0L
                val stallWatchdog = DownloadStallWatchdog(startBytes = total)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= artifact.sizeBytes) {
                        "${artifact.fileName} 下载内容超过清单大小"
                    }
                    output.write(buffer, 0, count)
                    stallWatchdog.onBytes(total)
                    onBytes(total)
                }
            }
        }
    }
}

/** 仅对网络类异常（IOException）做指数退避重试；校验失败与取消不重试。 */
private suspend fun <T> withNetworkRetries(block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            if (attempt >= RETRY_BACKOFF_MILLIS.size) throw error
            delay(RETRY_BACKOFF_MILLIS[attempt])
            attempt += 1
        }
    }
}

internal fun verifyArtifact(file: File, artifact: VerifiedArtifact) {
    require(file.isFile && file.length() == artifact.sizeBytes) {
        "${artifact.fileName} 文件大小校验失败"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    require(actual == artifact.sha256) { "${artifact.fileName} SHA-256 校验失败" }
}

private const val HTTP_PARTIAL = 206
private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
private const val STALL_WINDOW_MS = 20_000L
private const val STALL_MIN_BYTES = 512L * 1024

/**
 * readTimeout 只能发现"完全没有字节"的连接；被限速的镜像会不断重置读超时，
 * 让 116 MB 的 rootfs 以几 KB/s 永远下不完（V2301A 实测：28 MB 后掉到约
 * 5 KB/s，恰好高过 4 KB/s 的初版下限）。这个看门狗按 20 秒窗口统计吞吐量，
 * 低于约 26 KB/s 就抛 IOException——同一镜像重试会带 Range 续传，三次仍不
 * 达标才换下一个源。
 */
internal class DownloadStallWatchdog(
    startBytes: Long = 0L,
    private val windowMs: Long = STALL_WINDOW_MS,
    private val minBytesPerWindow: Long = STALL_MIN_BYTES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var windowStartMs = nowMs()
    private var windowStartBytes = startBytes

    fun onBytes(totalBytes: Long) {
        val now = nowMs()
        if (now - windowStartMs < windowMs) return
        val gained = totalBytes - windowStartBytes
        if (gained < minBytesPerWindow) {
            throw IOException("下载源速度过低（${windowMs / 1000} 秒仅 $gained B），切换下一个源")
        }
        windowStartMs = now
        windowStartBytes = totalBytes
    }
}
private val RETRY_BACKOFF_MILLIS = longArrayOf(2_000L, 4_000L, 8_000L)

/** Guest DNS：按区域优先本地公共 DNS，再回退另一侧，降低 apt 阶段解析失败概率。 */
internal fun embeddedRuntimeResolvConf(
    region: NetworkRegion = NetworkRegion.CHINA,
): String = when (region) {
    NetworkRegion.CHINA ->
        "nameserver 223.5.5.5\n" +
            "nameserver 119.29.29.29\n" +
            "nameserver 8.8.8.8\n" +
            "nameserver 1.1.1.1\n"
    NetworkRegion.OVERSEAS ->
        "nameserver 1.1.1.1\n" +
            "nameserver 8.8.8.8\n" +
            "nameserver 223.5.5.5\n" +
            "nameserver 119.29.29.29\n"
}

internal fun embeddedAptConf(): String =
    "Acquire::Retries \"5\";\n" +
        "Acquire::http::Timeout \"30\";\n" +
        "Acquire::https::Timeout \"30\";\n"

/**
 * 按 ABI + 区域选择 apt 镜像（HTTP，避免 ca-certificates 安装前无法走 HTTPS）。
 * arm64 走 ubuntu-ports，x86_64 走 ubuntu。
 * 优先区域在前，另一侧作为 fallback。
 */
internal fun embeddedAptMirrorBases(
    androidAbi: String,
    region: NetworkRegion = NetworkRegion.CHINA,
): List<String> {
    val path = when (androidAbi) {
        "arm64-v8a" -> "ubuntu-ports"
        "x86_64" -> "ubuntu"
        else -> error("不支持的架构: $androidAbi")
    }
    val domestic = listOf(
        "http://mirrors.aliyun.com/$path",
        "http://mirrors.tuna.tsinghua.edu.cn/$path",
        "http://mirrors.ustc.edu.cn/$path",
    )
    val international = when (androidAbi) {
        "arm64-v8a" -> listOf("http://ports.ubuntu.com/ubuntu-ports")
        "x86_64" -> listOf("http://archive.ubuntu.com/ubuntu")
        else -> error("不支持的架构: $androidAbi")
    }
    return when (region) {
        NetworkRegion.CHINA -> domestic + international
        NetworkRegion.OVERSEAS -> international + domestic
    }
}

internal fun embeddedAptSourcesList(
    androidAbi: String,
    mirrorBase: String? = null,
    suite: String = "noble",
    region: NetworkRegion = NetworkRegion.CHINA,
): String {
    val base = mirrorBase ?: embeddedAptMirrorBases(androidAbi, region).first()
    val components = "main restricted universe multiverse"
    return buildString {
        listOf("", "-updates", "-backports", "-security").forEach { pocket ->
            append("deb $base $suite$pocket $components\n")
        }
    }
}

/**
 * 在 PRoot 内安装基础工具：按区域镜像顺序尝试 apt-get update，成功后再 install。
 * 使用 heredoc 写 sources.list，避免依赖已安装的 sed/python。
 */
internal fun embeddedInstallBaseToolsScript(
    androidAbi: String,
    region: NetworkRegion = NetworkRegion.CHINA,
): String = buildString {
    appendLine("set -e")
    appendLine("updated=0")
    embeddedAptMirrorBases(androidAbi, region).forEach { mirror ->
        appendLine("if [ \"\$updated\" -eq 0 ]; then")
        appendLine("cat > /etc/apt/sources.list <<'AGENTDECK_APT_EOF'")
        append(embeddedAptSourcesList(androidAbi, mirrorBase = mirror, region = region))
        appendLine("AGENTDECK_APT_EOF")
        appendLine("rm -rf /var/lib/apt/lists/* || true")
        appendLine("mkdir -p /var/lib/apt/lists/partial")
        appendLine("apt-get update && updated=1 || true")
        appendLine("fi")
    }
    appendLine("test \"\$updated\" -eq 1")
    appendLine(
        "apt-get install -y --no-install-recommends " +
            "ca-certificates git python3 poppler-utils",
    )
    appendLine("apt-get clean")
    appendLine("mkdir -p /root/projects/default")
}
