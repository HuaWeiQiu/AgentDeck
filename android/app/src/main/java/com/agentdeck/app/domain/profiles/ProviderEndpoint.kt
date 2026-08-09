package com.agentdeck.app.domain.profiles

import java.net.URI

data class ProviderEndpoint(
    val apiBaseUrl: String,
    val modelsUrl: String,
)

object ProviderEndpointNormalizer {
    fun normalize(value: String): Result<ProviderEndpoint> = runCatching {
        require(value.trim().length <= 2_048) { "Base URL 不能超过 2048 个字符" }
        val input = runCatching { URI(value.trim()) }.getOrNull()
        require(
            input != null &&
                input.scheme?.lowercase() == "https" &&
                !input.host.isNullOrBlank() &&
                input.userInfo == null &&
                input.query == null &&
                input.fragment == null,
        ) { "模型服务必须使用不含凭据、query 或 fragment 的 HTTPS 地址" }
        require(input.path.none { it.isISOControl() }) { "Base URL 包含非法字符" }

        val normalizedPath = input.path.orEmpty().trimEnd('/').let { path ->
            when {
                path.isBlank() -> "/v1"
                path.startsWith('/') -> path
                else -> "/$path"
            }
        }
        val base = URI(
            "https",
            null,
            input.host.lowercase(),
            input.port,
            normalizedPath,
            null,
            null,
        ).toASCIIString()
        ProviderEndpoint(
            apiBaseUrl = base,
            modelsUrl = "$base/models",
        )
    }
}
