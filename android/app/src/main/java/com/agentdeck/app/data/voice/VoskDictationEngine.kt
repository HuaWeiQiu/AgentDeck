package com.agentdeck.app.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline Chinese dictation via Vosk using an explicit AudioRecord loop.
 * Avoids SpeechService callback quirks on some OEM ROMs.
 */
class VoskDictationEngine(
    context: Context,
    private val modelStore: VoskModelStore = VoskModelStore(context),
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)

    @Volatile
    private var model: Model? = null

    @Volatile
    private var recordThread: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recognizer: Recognizer? = null

    fun isModelReady(): Boolean = modelStore.isReady()

    /** Download model if needed and load it into memory (IO). */
    suspend fun ensureReady(onProgress: (String) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        modelStore.ensureReady(onProgress).mapCatching {
            if (model == null) {
                model = Model(modelStore.modelPath().absolutePath)
            }
        }
    }

    /**
     * Offline smoke-test path: feed a mono 16-bit PCM WAV (16 kHz preferred) into Vosk.
     */
    suspend fun transcribeWav(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady().getOrThrow()
            val loaded = model ?: error("语音模型未就绪")
            val pcm = readPcm16Mono(file)
            val rec = Recognizer(loaded, SAMPLE_RATE.toFloat())
            try {
                val chunk = 4000
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + chunk, pcm.size)
                    val slice = pcm.copyOfRange(offset, end)
                    rec.acceptWaveForm(slice, slice.size)
                    offset = end
                }
                parseHypothesis(rec.finalResult, partial = false)
            } finally {
                runCatching { rec.close() }
            }
        }
    }

    fun start(
        onPartial: (String) -> Unit,
        onUtterance: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!active.compareAndSet(false, true)) return
        val loaded = model
        if (loaded == null) {
            active.set(false)
            onError("语音模型未就绪")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            active.set(false)
            onError("无法初始化麦克风")
            return
        }
        val bufferSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // ~200ms of 16-bit mono

        val recorder = openRecorder(bufferSize)
        if (recorder == null) {
            active.set(false)
            onError("麦克风初始化失败")
            return
        }

        val rec = try {
            Recognizer(loaded, SAMPLE_RATE.toFloat())
        } catch (error: Exception) {
            runCatching { recorder.release() }
            active.set(false)
            onError(error.message?.take(120) ?: "无法创建识别器")
            return
        }

        audioRecord = recorder
        recognizer = rec

        val thread = Thread(
            {
                var maxAbs = 0
                var frames = 0
                try {
                    recorder.startRecording()
                    Log.i(TAG, "AudioRecord started status=${recorder.recordingState} sourceOk=true")
                    val buf = ByteArray(bufferSize)
                    while (active.get()) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        frames++
                        // Track peak amplitude so we know whether the mic is actually open.
                        var i = 0
                        while (i + 1 < n) {
                            val sample = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                            val a = kotlin.math.abs(sample)
                            if (a > maxAbs) maxAbs = a
                            i += 2
                        }
                        if (frames % 10 == 0) {
                            Log.i(TAG, "audio peak=$maxAbs frames=$frames")
                        }
                        val accepted = rec.acceptWaveForm(buf, n)
                        if (accepted) {
                            // Endpoint: one finished phrase.
                            val text = parseHypothesis(rec.result, partial = false)
                            Log.i(TAG, "utterance='$text'")
                            if (text.isNotBlank()) {
                                mainHandler.post { onUtterance(text) }
                            }
                        } else {
                            val partial = parseHypothesis(rec.partialResult, partial = true)
                            if (partial.isNotBlank()) {
                                Log.i(TAG, "partial='$partial'")
                                mainHandler.post { onPartial(partial) }
                            }
                        }
                    }
                    val finalText = parseHypothesis(rec.finalResult, partial = false)
                    Log.i(TAG, "final='$finalText' peak=$maxAbs frames=$frames")
                    if (finalText.isBlank() && maxAbs < 200) {
                        mainHandler.post { onError("没听到声音，请靠近麦克风再说一次") }
                    } else {
                        mainHandler.post { onFinal(finalText) }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "dictation loop failed", error)
                    mainHandler.post {
                        onError(error.message?.take(120) ?: "离线语音识别失败")
                    }
                } finally {
                    cleanupCapture()
                    active.set(false)
                }
            },
            "vosk-dictation",
        )
        recordThread = thread
        thread.start()
    }

    private fun openRecorder(bufferSize: Int): AudioRecord? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return null
        }
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
        )
        for (source in sources) {
            val recorder = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (_: SecurityException) {
                return null
            } catch (_: Exception) {
                null
            }
            if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "using AudioSource=$source")
                return recorder
            }
            runCatching { recorder?.release() }
        }
        return null
    }

    fun stop() {
        // Flip the flag; the capture thread will emit finalResult and exit.
        if (!active.getAndSet(false)) return
        runCatching { audioRecord?.stop() }
    }

    fun release() {
        active.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { recordThread?.join(1000) }
        cleanupCapture()
        runCatching { model?.close() }
        model = null
    }

    private fun cleanupCapture() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { recognizer?.close() }
        recognizer = null
        recordThread = null
    }

    private fun parseHypothesis(raw: String?, partial: Boolean): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching {
            val json = JSONObject(raw)
            when {
                partial -> json.optString("partial").trim()
                else -> json.optString("text").ifBlank { json.optString("partial") }.trim()
            }
        }.getOrDefault("")
    }

    private fun readPcm16Mono(file: File): ByteArray {
        require(file.isFile) { "WAV 不存在: ${file.absolutePath}" }
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4).also { raf.readFully(it) }
            check(String(riff) == "RIFF") { "不是 WAV 文件" }
            raf.skipBytes(4)
            val wave = ByteArray(4).also { raf.readFully(it) }
            check(String(wave) == "WAVE") { "不是 WAV 文件" }
            var dataSize = -1
            var dataOffset = -1L
            while (raf.filePointer < raf.length()) {
                val chunkId = ByteArray(4).also { raf.readFully(it) }
                val sizeBytes = ByteArray(4).also { raf.readFully(it) }
                val size = (sizeBytes[0].toInt() and 0xff) or
                    ((sizeBytes[1].toInt() and 0xff) shl 8) or
                    ((sizeBytes[2].toInt() and 0xff) shl 16) or
                    ((sizeBytes[3].toInt() and 0xff) shl 24)
                val id = String(chunkId)
                if (id == "data") {
                    dataSize = size
                    dataOffset = raf.filePointer
                    break
                }
                raf.skipBytes(size)
            }
            check(dataOffset >= 0 && dataSize > 0) { "WAV 缺少 data 块" }
            raf.seek(dataOffset)
            val pcm = ByteArray(dataSize)
            raf.readFully(pcm)
            return pcm
        }
    }

    companion object {
        private const val TAG = "AgentDeckVoice"
        private const val SAMPLE_RATE = 16_000
    }
}
