package com.agentdeck.app.data.runtime

/**
 * 按出口 IP 判定的网络区域，用于 runtime / apt / 语音包等下载源排序。
 *
 * - [CHINA]：大陆镜像优先（清华/阿里/中科大、ghfast）
 * - [OVERSEAS]：官方源优先（cdimage.ubuntu.com、GitHub）
 */
enum class NetworkRegion {
    CHINA,
    OVERSEAS,
}

/** 判定 URL 是否属于国内加速/镜像源（相对官方源）。 */
internal fun isDomesticDownloadUrl(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
    return host == "ghfast.top" ||
        host.endsWith(".ghfast.top") ||
        host.contains("ghproxy") ||
        host == "npmmirror.com" ||
        host.endsWith(".npmmirror.com") ||
        host.endsWith("tsinghua.edu.cn") ||
        host.endsWith("aliyun.com") ||
        host.endsWith("ustc.edu.cn") ||
        host.endsWith("nju.edu.cn") ||
        host.endsWith("bfsu.edu.cn") ||
        host.endsWith("huaweicloud.com") ||
        host.endsWith("tencent.com") ||
        host.endsWith("myqcloud.com")
}

/** npm registry preferred for the region (used by dsh/pi installers). */
internal fun npmRegistryForRegion(region: NetworkRegion): String = when (region) {
    NetworkRegion.CHINA -> "https://registry.npmmirror.com"
    NetworkRegion.OVERSEAS -> "https://registry.npmjs.org"
}

/**
 * 按区域重排下载 URL：优先区域在前，另一侧作为 fallback 保留。
 * 同侧相对顺序保持输入顺序。
 */
internal fun orderUrlsForRegion(
    urls: List<String>,
    region: NetworkRegion,
): List<String> {
    if (urls.size <= 1) return urls
    val domestic = urls.filter(::isDomesticDownloadUrl)
    val international = urls.filterNot(::isDomesticDownloadUrl)
    if (domestic.isEmpty() || international.isEmpty()) return urls
    return when (region) {
        NetworkRegion.CHINA -> domestic + international
        NetworkRegion.OVERSEAS -> international + domestic
    }
}

internal fun countryCodeToRegion(code: String?): NetworkRegion? {
    val normalized = code?.trim()?.uppercase().orEmpty()
    if (normalized.length != 2) return null
    // 仅大陆走国内镜像；港澳台与海外走官方优先。
    return if (normalized == "CN") NetworkRegion.CHINA else NetworkRegion.OVERSEAS
}
