package com.agentdeck.app.data.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads and caches the small Chinese Vosk model for offline dictation.
 * First run needs network (~42 MiB); later uses local files only.
 */
class VoskModelStore(
    context: Context,
    private val http: OkHttpClient = defaultClient(),
) {
    private val app = context.applicationContext
    private val rootDir = File(app.filesDir, "vosk")
    private val modelDir = File(rootDir, MODEL_DIR_NAME)
    private val readyMarker = File(modelDir, "conf/model.conf")

    fun isReady(): Boolean = readyMarker.isFile

    fun modelPath(): File = modelDir

    suspend fun ensureReady(onProgress: (String) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext Result.success(modelDir)
        runCatching {
            rootDir.mkdirs()
            val zipFile = File(rootDir, "$MODEL_DIR_NAME.zip")
            onProgress("正在下载中文语音包（约 42MB）…")
            download(MODEL_URL, zipFile)
            onProgress("正在解压语音包…")
            if (modelDir.exists()) modelDir.deleteRecursively()
            unzip(zipFile, rootDir)
            zipFile.delete()
            check(isReady()) { "语音包解压不完整" }
            modelDir
        }
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败 HTTP ${response.code}" }
            val body = response.body ?: error("下载失败：空响应")
            target.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
        check(target.length() > 1_000_000L) { "下载文件过小，可能失败" }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipArchiveInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.trimStart('/')
                // Zip-slip guard
                val outFile = File(destDir, name).canonicalFile
                check(outFile.path.startsWith(destDir.canonicalPath + File.separator) || outFile.path == destDir.canonicalPath) {
                    "非法压缩路径: $name"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    continue
                }
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out -> zis.copyTo(out) }
            }
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
