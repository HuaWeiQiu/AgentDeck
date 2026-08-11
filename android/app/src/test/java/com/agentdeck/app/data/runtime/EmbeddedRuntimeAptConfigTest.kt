package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRuntimeAptConfigTest {
    @Test
    fun `resolv conf prefers domestic public dns in china`() {
        val conf = embeddedRuntimeResolvConf(NetworkRegion.CHINA)
        assertTrue(conf.startsWith("nameserver 223.5.5.5\n"))
        assertTrue(conf.contains("nameserver 119.29.29.29\n"))
        assertTrue(conf.contains("nameserver 8.8.8.8\n"))
        assertTrue(conf.endsWith("nameserver 1.1.1.1\n"))
    }

    @Test
    fun `resolv conf prefers public dns overseas`() {
        val conf = embeddedRuntimeResolvConf(NetworkRegion.OVERSEAS)
        assertTrue(conf.startsWith("nameserver 1.1.1.1\n"))
        assertTrue(conf.contains("nameserver 8.8.8.8\n"))
    }

    @Test
    fun `arm64 china uses ubuntu-ports domestic mirrors first`() {
        val mirrors = embeddedAptMirrorBases("arm64-v8a", NetworkRegion.CHINA)
        assertEquals(
            listOf(
                "http://mirrors.aliyun.com/ubuntu-ports",
                "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
                "http://mirrors.ustc.edu.cn/ubuntu-ports",
                "http://ports.ubuntu.com/ubuntu-ports",
            ),
            mirrors,
        )
        val sources = embeddedAptSourcesList("arm64-v8a", region = NetworkRegion.CHINA)
        assertTrue(sources.contains("http://mirrors.aliyun.com/ubuntu-ports noble main"))
        assertTrue(sources.contains("noble-security"))
        assertFalse(sources.contains("https://"))
    }

    @Test
    fun `arm64 overseas uses ports ubuntu first`() {
        val mirrors = embeddedAptMirrorBases("arm64-v8a", NetworkRegion.OVERSEAS)
        assertEquals("http://ports.ubuntu.com/ubuntu-ports", mirrors.first())
        assertTrue(mirrors.any { it.contains("mirrors.aliyun.com") })
        val sources = embeddedAptSourcesList("arm64-v8a", region = NetworkRegion.OVERSEAS)
        assertTrue(sources.startsWith("deb http://ports.ubuntu.com/ubuntu-ports noble main"))
    }

    @Test
    fun `x86_64 china uses ubuntu domestic mirrors`() {
        val mirrors = embeddedAptMirrorBases("x86_64", NetworkRegion.CHINA)
        assertEquals("http://mirrors.aliyun.com/ubuntu", mirrors.first())
        val sources = embeddedAptSourcesList("x86_64", mirrorBase = mirrors[1])
        assertTrue(sources.contains("http://mirrors.tuna.tsinghua.edu.cn/ubuntu noble main"))
        assertFalse(sources.contains("ubuntu-ports"))
    }

    @Test
    fun `x86_64 overseas uses archive ubuntu first`() {
        val mirrors = embeddedAptMirrorBases("x86_64", NetworkRegion.OVERSEAS)
        assertEquals("http://archive.ubuntu.com/ubuntu", mirrors.first())
    }

    @Test
    fun `install script tries mirrors then installs packages`() {
        val china = embeddedInstallBaseToolsScript("arm64-v8a", NetworkRegion.CHINA)
        assertTrue(china.indexOf("mirrors.aliyun.com/ubuntu-ports") < china.indexOf("ports.ubuntu.com"))
        assertTrue(china.contains("apt-get update && updated=1 || true"))
        assertTrue(china.contains("test \"\$updated\" -eq 1"))
        assertTrue(china.contains("ca-certificates git python3 poppler-utils"))

        val overseas = embeddedInstallBaseToolsScript("arm64-v8a", NetworkRegion.OVERSEAS)
        assertTrue(overseas.indexOf("ports.ubuntu.com") < overseas.indexOf("mirrors.aliyun.com"))
    }
}
