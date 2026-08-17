package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NetworkRegionTest {
    @Test
    fun `classifies domestic and official hosts`() {
        assertTrue(isDomesticDownloadUrl("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/a.tar.gz"))
        assertTrue(isDomesticDownloadUrl("https://mirrors.aliyun.com/ubuntu-cdimage/a.tar.gz"))
        assertTrue(isDomesticDownloadUrl("https://ghfast.top/https://github.com/openai/codex/x"))
        assertTrue(isDomesticDownloadUrl("https://npmmirror.com/mirrors/node/v24.19.0/node.tar.gz"))
        assertTrue(isDomesticDownloadUrl("https://cdn.npmmirror.com/binaries/node/v24.19.0/node.tar.gz"))
        assertFalse(isDomesticDownloadUrl("https://cdimage.ubuntu.com/ubuntu-base/a.tar.gz"))
        assertFalse(isDomesticDownloadUrl("https://github.com/openai/codex/releases/download/x"))
        assertFalse(isDomesticDownloadUrl("https://nodejs.org/dist/v24.19.0/node.tar.gz"))
        assertFalse(isDomesticDownloadUrl("https://registry.npmjs.org/@deepseek-ai/dsh"))
    }

    @Test
    fun `npm registry follows region`() {
        assertEquals("https://registry.npmmirror.com", npmRegistryForRegion(NetworkRegion.CHINA))
        assertEquals("https://registry.npmjs.org", npmRegistryForRegion(NetworkRegion.OVERSEAS))
    }

    @Test
    fun `china prefers npmmirror node dist before nodejs org`() {
        val urls = listOf(
            "https://npmmirror.com/mirrors/node/v24.19.0/node.tar.gz",
            "https://nodejs.org/dist/v24.19.0/node.tar.gz",
        )
        assertEquals(
            listOf(
                "https://npmmirror.com/mirrors/node/v24.19.0/node.tar.gz",
                "https://nodejs.org/dist/v24.19.0/node.tar.gz",
            ),
            orderUrlsForRegion(urls, NetworkRegion.CHINA),
        )
        assertEquals(
            listOf(
                "https://nodejs.org/dist/v24.19.0/node.tar.gz",
                "https://npmmirror.com/mirrors/node/v24.19.0/node.tar.gz",
            ),
            orderUrlsForRegion(urls, NetworkRegion.OVERSEAS),
        )
    }

    @Test
    fun `china prefers domestic then official`() {
        val urls = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/a",
            "https://cdimage.ubuntu.com/a",
            "https://ghfast.top/https://github.com/x",
            "https://github.com/openai/codex/x",
        )
        assertEquals(
            listOf(
                "https://mirrors.tuna.tsinghua.edu.cn/a",
                "https://ghfast.top/https://github.com/x",
                "https://cdimage.ubuntu.com/a",
                "https://github.com/openai/codex/x",
            ),
            orderUrlsForRegion(urls, NetworkRegion.CHINA),
        )
    }

    @Test
    fun `overseas prefers official then domestic`() {
        val urls = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/a",
            "https://cdimage.ubuntu.com/a",
            "https://ghfast.top/https://github.com/x",
            "https://github.com/openai/codex/x",
        )
        assertEquals(
            listOf(
                "https://cdimage.ubuntu.com/a",
                "https://github.com/openai/codex/x",
                "https://mirrors.tuna.tsinghua.edu.cn/a",
                "https://ghfast.top/https://github.com/x",
            ),
            orderUrlsForRegion(urls, NetworkRegion.OVERSEAS),
        )
    }

    @Test
    fun `country code mapping`() {
        assertEquals(NetworkRegion.CHINA, countryCodeToRegion("cn"))
        assertEquals(NetworkRegion.OVERSEAS, countryCodeToRegion("US"))
        assertEquals(NetworkRegion.OVERSEAS, countryCodeToRegion("HK"))
        assertEquals(null, countryCodeToRegion(""))
        assertEquals(null, countryCodeToRegion("CNX"))
    }

    @Test
    fun `soft hint uses locale and timezone`() {
        assertEquals(
            NetworkRegion.CHINA,
            NetworkRegionDetector.defaultSoftHint(Locale.SIMPLIFIED_CHINESE, "America/New_York"),
        )
        assertEquals(
            NetworkRegion.CHINA,
            NetworkRegionDetector.defaultSoftHint(Locale.US, "Asia/Shanghai"),
        )
        assertEquals(
            NetworkRegion.OVERSEAS,
            NetworkRegionDetector.defaultSoftHint(Locale.US, "America/Los_Angeles"),
        )
    }
}
