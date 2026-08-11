package com.agentdeck.app.data.runtime

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 用出口 IP 判定 [NetworkRegion]，结果缓存 24h。
 *
 * 探测顺序：
 * 1. 本地缓存
 * 2. Cloudflare `cdn-cgi/trace` 的 `loc=`（轻量、无 key）
 * 3. ipinfo.io/country 纯文本（备用）
 * 4. 系统 locale / 时区软提示
 * 5. 默认 [NetworkRegion.CHINA]（产品主用户在国内）
 */
internal class NetworkRegionDetector(
    context: Context,
    client: OkHttpClient? = null,
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val softHint: () -> NetworkRegion = { defaultSoftHint() },
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun detect(forceRefresh: Boolean = false): NetworkRegion = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedRegion()?.let { return@withContext it }
        }
        val probed = probeCloudflare() ?: probeIpInfo()
        val region = probed ?: softHint()
        persist(region)
        region
    }

    private fun cachedRegion(): NetworkRegion? {
        val raw = prefs.getString(KEY_REGION, null) ?: return null
        val at = prefs.getLong(KEY_AT_MS, 0L)
        if (at <= 0L || clockMs() - at > CACHE_TTL_MS) return null
        return runCatching { NetworkRegion.valueOf(raw) }.getOrNull()
    }

    private fun persist(region: NetworkRegion) {
        prefs.edit()
            .putString(KEY_REGION, region.name)
            .putLong(KEY_AT_MS, clockMs())
            .apply()
    }

    private fun probeCloudflare(): NetworkRegion? = runCatching {
        val body = httpGetText("https://www.cloudflare.com/cdn-cgi/trace") ?: return null
        val loc = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("loc=") }
            ?.substringAfter("loc=")
        countryCodeToRegion(loc)
    }.getOrNull()

    private fun probeIpInfo(): NetworkRegion? = runCatching {
        val body = httpGetText("https://ipinfo.io/country") ?: return null
        countryCodeToRegion(body.lineSequence().firstOrNull())
    }.getOrNull()

    private fun httpGetText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.take(2_048)
        }
    }

    companion object {
        private const val PREFS_NAME = "agentdeck_network_region"
        private const val KEY_REGION = "region"
        private const val KEY_AT_MS = "region_at_ms"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        internal fun defaultSoftHint(
            locale: Locale = Locale.getDefault(),
            timeZoneId: String = TimeZone.getDefault().id,
        ): NetworkRegion {
            val country = locale.country.uppercase(Locale.ROOT)
            if (country == "CN" || country == "PRC") return NetworkRegion.CHINA
            if (timeZoneId == "Asia/Shanghai" || timeZoneId == "Asia/Urumqi") {
                return NetworkRegion.CHINA
            }
            return NetworkRegion.OVERSEAS
        }
    }
}
