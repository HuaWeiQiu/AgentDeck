package com.agentdeck.app.data.host.lab

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentdeck.app.domain.host.HostToolResult
import com.agentdeck.app.domain.host.LabIntentExecutor

class LabIntentExecutorImpl(
    context: Context,
) : LabIntentExecutor {
    private val app = context.applicationContext

    override fun openUrl(url: String): HostToolResult {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return HostToolResult.Denied("host_bad_url", "URL 无效")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return HostToolResult.Denied("host_url_scheme", "仅允许 http/https")
        }
        if (url.length > 2_000) {
            return HostToolResult.Denied("host_url_too_long", "URL 过长")
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            HostToolResult.Ok(mapOf("opened" to url))
        }.getOrElse {
            HostToolResult.Error("host_intent_failed", it.message?.take(120) ?: "无法打开链接")
        }
    }

    override fun shareText(text: String): HostToolResult {
        if (text.length > 8_000) {
            return HostToolResult.Denied("host_text_too_long", "分享文本过长")
        }
        return runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(send, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(chooser)
            HostToolResult.Ok(mapOf("shared_chars" to text.length.toString()))
        }.getOrElse {
            HostToolResult.Error("host_intent_failed", it.message?.take(120) ?: "无法分享")
        }
    }
}
