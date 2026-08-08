package com.agentdeck.app.domain.profiles

import java.net.URI

object ProfileInputValidator {
    fun validate(
        name: String,
        baseUrl: String,
        defaultModel: String,
    ): Result<Unit> = runCatching {
        require(name.trim().isNotEmpty() && name.trim().length <= 80) {
            "名称不能为空且不能超过 80 个字符"
        }
        require(defaultModel.trim().isNotEmpty() && defaultModel.trim().length <= 160) {
            "默认模型不能为空且不能超过 160 个字符"
        }
        require(baseUrl.trim().length <= 2_048) { "Base URL 不能超过 2048 个字符" }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(
            uri != null &&
                (uri.scheme?.lowercase() == "https" || uri.scheme?.lowercase() == "http") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) {
            "Base URL 必须是 http/https 地址，且不能包含凭据、query 或 fragment"
        }
    }
}
