package com.agentdeck.app.data.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline Chinese dictation via Vosk. Not a system SpeechRecognizer wrapper.
 */
class VoskDictationEngine(
    context: Context,
    private val modelStore: VoskModelStore = VoskModelStore(context),
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)

    @Volatile
    private var model: Model? = null

    @Volatile
    private var speechService: SpeechService? = null

    fun isModelReady(): Boolean = modelStore.isReady()

    /** Download model if needed and load it into memory (IO). */
    suspend fun ensureReady(onProgress: (String) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        modelStore.ensureReady(onProgress).mapCatching {
            if (model == null) {
                model = Model(modelStore.modelPath().absolutePath)
            }
        }
    }

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!active.compareAndSet(false, true)) return
        try {
            val loaded = model
            if (loaded == null) {
                active.set(false)
                onError("语音模型未就绪")
                return
            }
            val recognizer = Recognizer(loaded, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(
                object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        val text = parseHypothesis(hypothesis, partial = true)
                        if (text.isNotBlank()) mainHandler.post { onPartial(text) }
                    }

                    override fun onResult(hypothesis: String?) {
                        // Intermediate final segments during continuous listen; keep as partial merge.
                        val text = parseHypothesis(hypothesis, partial = false)
                        if (text.isNotBlank()) mainHandler.post { onPartial(text) }
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        val text = parseHypothesis(hypothesis, partial = false)
                        mainHandler.post {
                            stopInternal()
                            if (text.isNotBlank()) onFinal(text)
                            else onFinal("")
                        }
                    }

                    override fun onError(exception: Exception?) {
                        mainHandler.post {
                            stopInternal()
                            onError(exception?.message?.take(120) ?: "离线语音识别失败")
                        }
                    }

                    override fun onTimeout() {
                        mainHandler.post {
                            stopInternal()
                            onError("没有检测到语音")
                        }
                    }
                },
            )
        } catch (error: Exception) {
            active.set(false)
            stopInternal()
            onError(error.message?.take(120) ?: "无法启动离线语音识别")
        }
    }

    fun stop() {
        // stop() asks Vosk for a final result; cleanup happens in onFinalResult/onError.
        runCatching { speechService?.stop() }
    }

    fun release() {
        active.set(false)
        stopInternal()
        runCatching { model?.close() }
        model = null
    }

    private fun stopInternal() {
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        active.set(false)
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

    companion object {
        private const val SAMPLE_RATE = 16_000.0f
    }
}
