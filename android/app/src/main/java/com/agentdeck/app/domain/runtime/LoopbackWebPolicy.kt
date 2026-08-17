package com.agentdeck.app.domain.runtime

import java.net.URI

/**
 * Hard allowlist for embedding local CLI Web UIs (e.g. dsh).
 * Only loopback HTTP(S) is permitted — never file://, content://, or remote hosts.
 */
object LoopbackWebPolicy {
    fun isAllowedUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "127.0.0.1" && host != "localhost" && host != "[::1]" && host != "::1") {
            return false
        }
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        // Ephemeral / common local UI ports; reject obviously wrong extremes.
        return port in 1..65_535
    }

    fun defaultDshUrl(port: Int = 3080): String {
        require(port in 1..65_535)
        return "http://127.0.0.1:$port/"
    }
}
