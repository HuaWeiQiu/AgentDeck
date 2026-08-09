package com.agentdeck.app.data.provider

import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.profiles.ProviderEndpointNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

interface ProviderModelDiscovery {
    suspend fun discover(
        profile: ProviderProfile,
        apiKey: ByteArray,
        discoveredAtEpochMs: Long = System.currentTimeMillis(),
    ): List<ProviderModel>
}

class ProviderDiscoveryException(
    val status: ProviderConnectionStatus,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class OkHttpProviderModelDiscovery(
    private val client: OkHttpClient = defaultClient(),
) : ProviderModelDiscovery {
    override suspend fun discover(
        profile: ProviderProfile,
        apiKey: ByteArray,
        discoveredAtEpochMs: Long,
    ): List<ProviderModel> = withContext(Dispatchers.IO) {
        require(
            profile.adapterId == ProviderAdapterId.SUB2API ||
                profile.adapterId == ProviderAdapterId.OPENAI_RESPONSES,
        ) { "该 Provider 不支持 Codex 模型发现" }
        validateApiKey(apiKey)
        val endpoint = ProviderEndpointNormalizer.normalize(profile.baseUrl).getOrElse { error ->
            throw ProviderDiscoveryException(
                ProviderConnectionStatus.INVALID_RESPONSE,
                error.message ?: "Base URL 无效",
                error,
            )
        }
        val keyText = apiKey.toString(StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url(endpoint.modelsUrl)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $keyText")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    throw ProviderDiscoveryException(
                        ProviderConnectionStatus.NETWORK_ERROR,
                        "模型地址发生重定向，请填写最终 HTTPS API 地址",
                    )
                }
                if (!response.isSuccessful) throw httpFailure(response.code)
                val body = response.body ?: throw ProviderDiscoveryException(
                    ProviderConnectionStatus.INVALID_RESPONSE,
                    "上游没有返回模型列表",
                )
                parseModels(profile.id, readBounded(body), discoveredAtEpochMs)
            }
        } catch (error: ProviderDiscoveryException) {
            throw error
        } catch (error: SSLException) {
            throw ProviderDiscoveryException(
                ProviderConnectionStatus.TLS_ERROR,
                "无法验证模型服务的 HTTPS 证书",
                error,
            )
        } catch (error: SocketTimeoutException) {
            throw ProviderDiscoveryException(
                ProviderConnectionStatus.NETWORK_ERROR,
                "连接模型服务超时",
                error,
            )
        } catch (error: IOException) {
            throw ProviderDiscoveryException(
                ProviderConnectionStatus.NETWORK_ERROR,
                "无法连接模型服务",
                error,
            )
        }
    }

    private fun parseModels(
        providerId: String,
        bytes: ByteArray,
        discoveredAtEpochMs: Long,
    ): List<ProviderModel> = try {
        val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        val data = root.optJSONArray("data") ?: throw ProviderDiscoveryException(
            ProviderConnectionStatus.INVALID_RESPONSE,
            "上游模型响应缺少 data 列表",
        )
        require(data.length() <= MAX_MODELS) { "上游返回的模型数量超过限制" }
        val seen = LinkedHashSet<String>()
        val result = ArrayList<ProviderModel>(data.length())
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank() || id.length > MAX_MODEL_ID_LENGTH || id.any(Char::isISOControl)) continue
            if (!seen.add(id)) continue
            val displayName = item.optString("display_name")
                .trim()
                .takeIf {
                    it.isNotBlank() && it.length <= MAX_DISPLAY_NAME_LENGTH &&
                        it.none(Char::isISOControl)
                }
                ?: id
            result += ProviderModel(
                providerId = providerId,
                id = id,
                displayName = displayName,
                discoveredAtEpochMs = discoveredAtEpochMs,
            )
        }
        if (result.isEmpty()) {
            throw ProviderDiscoveryException(
                ProviderConnectionStatus.INVALID_RESPONSE,
                "上游没有返回可用模型",
            )
        }
        result
    } catch (error: ProviderDiscoveryException) {
        throw error
    } catch (error: Exception) {
        throw ProviderDiscoveryException(
            ProviderConnectionStatus.INVALID_RESPONSE,
            "无法解析上游模型列表",
            error,
        )
    } finally {
        bytes.fill(0)
    }

    private fun readBounded(body: ResponseBody): ByteArray {
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw ProviderDiscoveryException(
                ProviderConnectionStatus.INVALID_RESPONSE,
                "上游模型响应过大",
            )
        }
        val buffer = Buffer()
        val source = body.source()
        var total = 0L
        while (total <= MAX_RESPONSE_BYTES) {
            val read = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - total))
            if (read == -1L) return buffer.readByteArray()
            total += read
        }
        buffer.clear()
        throw ProviderDiscoveryException(
            ProviderConnectionStatus.INVALID_RESPONSE,
            "上游模型响应过大",
        )
    }

    private fun httpFailure(code: Int): ProviderDiscoveryException = when (code) {
        401 -> ProviderDiscoveryException(
            ProviderConnectionStatus.CREDENTIAL_REJECTED,
            "API Key 无效或已失效",
        )
        403 -> ProviderDiscoveryException(
            ProviderConnectionStatus.FORBIDDEN,
            "当前 API Key 没有访问该分组或模型的权限",
        )
        404 -> ProviderDiscoveryException(
            ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
            "该地址不支持 /models，可手动填写模型 ID",
        )
        429 -> ProviderDiscoveryException(
            ProviderConnectionStatus.RATE_LIMITED,
            "模型服务请求过于频繁，请稍后重试",
        )
        in 500..599 -> ProviderDiscoveryException(
            ProviderConnectionStatus.NETWORK_ERROR,
            "模型服务暂时不可用（HTTP $code）",
        )
        else -> ProviderDiscoveryException(
            ProviderConnectionStatus.INVALID_RESPONSE,
            "模型服务返回 HTTP $code",
        )
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1L * 1_024 * 1_024
        private const val MAX_MODELS = 5_000
        private const val MAX_MODEL_ID_LENGTH = 160
        private const val MAX_DISPLAY_NAME_LENGTH = 320

        private fun validateApiKey(value: ByteArray) {
            require(value.isNotEmpty() && value.size <= 8 * 1_024) { "API Key 无效" }
            require(value.none { it == 0.toByte() || it == '\r'.code.toByte() || it == '\n'.code.toByte() }) {
                "API Key 包含非法字符"
            }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
