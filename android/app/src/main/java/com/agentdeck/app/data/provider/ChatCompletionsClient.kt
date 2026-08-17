package com.agentdeck.app.data.provider

import com.agentdeck.app.data.secure.ProviderCredentialVault
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.isChatCompletionsCompatible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight OpenAI-compatible **Chat Completions** streaming client (no PRoot).
 * For pure chat / gateway smoke (e.g. dots). Does not run tools or agent loops.
 */
internal class ChatCompletionsClient(
    private val vault: ProviderCredentialVault,
    private val http: OkHttpClient = defaultClient(),
) {
    sealed class Event {
        data class Delta(val text: String) : Event()
        data class Completed(val fullText: String) : Event()
        data class Failed(val message: String) : Event()
    }

    data class Message(val role: String, val content: String)

    fun stream(
        profile: ProviderProfile,
        modelId: String,
        messages: List<Message>,
    ): Flow<Event> = callbackFlow {
        require(profile.adapterId.isChatCompletionsCompatible()) {
            "需要 Chat Completions 模型服务"
        }
        val credentialRef = requireNotNull(profile.credentialRef) { "缺少 API Key" }
        val apiKey = vault.load(credentialRef)?.toString(Charsets.UTF_8)?.trim().orEmpty()
        require(apiKey.isNotEmpty()) { "API Key 为空" }

        val base = profile.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val model = modelId.ifBlank { profile.defaultModel }
        val bodyJson = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put(
                "messages",
                JSONArray().also { arr ->
                    messages.forEach { m ->
                        arr.put(
                            JSONObject()
                                .put("role", m.role)
                                .put("content", m.content),
                        )
                    }
                },
            )

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        val callRef = AtomicReference<Call?>(null)
        val call = http.newCall(request)
        callRef.set(call)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(400).orEmpty()
                trySend(Event.Failed("HTTP ${response.code}${if (err.isNotBlank()) ": $err" else ""}"))
                close()
                return@callbackFlow
            }
            val body = response.body
            if (body == null) {
                trySend(Event.Failed("空响应"))
                close()
                return@callbackFlow
            }
            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            val assembled = StringBuilder()
            reader.useLines { lines ->
                for (line in lines) {
                    if (!isActive) break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val piece = parseDelta(data) ?: continue
                    if (piece.isEmpty()) continue
                    assembled.append(piece)
                    trySend(Event.Delta(piece))
                }
            }
            if (assembled.isNotEmpty()) {
                trySend(Event.Completed(assembled.toString()))
            } else {
                trySend(Event.Failed("未收到模型内容"))
            }
            close()
        } catch (error: Exception) {
            if (isActive) {
                trySend(Event.Failed(error.message ?: "请求失败"))
            }
            close(error)
        }

        awaitClose {
            callRef.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseDelta(data: String): String? = runCatching {
        val root = JSONObject(data)
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            return delta.optString("content").takeIf { it.isNotEmpty() }
                ?: delta.optString("text").takeIf { it.isNotEmpty() }
        }
        val message = choice.optJSONObject("message")
        message?.optString("content")?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
            .build()
    }
}
