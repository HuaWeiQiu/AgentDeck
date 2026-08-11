package com.agentdeck.app.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedRuntimeAptConfigTest {
    @Test
    fun `resolv conf prefers domestic public dns`() {
        val conf = embeddedRuntimeResolvConf()
        assertTrue(conf.startsWith("nameserver 223.5.5.5\n"))
        assertTrue(conf.contains("nameserver 119.29.29.29\n"))
        assertTrue(conf.contains("nameserver 8.8.8.8\n"))
        assertTrue(conf.endsWith("nameserver 1.1.1.1\n"))
    }

    @Test
    fun `arm64 uses ubuntu-ports domestic mirrors`() {
        val mirrors = embeddedAptMirrorBases("arm64-v8a")
        assertEquals(
            listOf(
                "http://mirrors.aliyun.com/ubuntu-ports",
                "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
                "http://mirrors.ustc.edu.cn/ubuntu-ports",
            ),
            mirrors,
        )
        val sources = embeddedAptSourcesList("arm64-v8a")
        assertTrue(sources.contains("http://mirrors.aliyun.com/ubuntu-ports noble main"))
        assertTrue(sources.contains("noble-security"))
        assertFalse(sources.contains("ports.ubuntu.com"))
        assertFalse(sources.contains("https://"))
    }

    @Test
    fun `x86_64 uses ubuntu domestic mirrors`() {
        val mirrors = embeddedAptMirrorBases("x86_64")
        assertEquals("http://mirrors.aliyun.com/ubuntu", mirrors.first())
        val sources = embeddedAptSourcesList("x86_64", mirrorBase = mirrors[1])
        assertTrue(sources.contains("http://mirrors.tuna.tsinghua.edu.cn/ubuntu noble main"))
        assertFalse(sources.contains("ubuntu-ports"))
    }

    @Test
    fun `install script tries mirrors then installs packages`() {
        val script = embeddedInstallBaseToolsScript("arm64-v8a")
        assertTrue(script.contains("mirrors.aliyun.com/ubuntu-ports"))
        assertTrue(script.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"))
        assertTrue(script.contains("mirrors.ustc.edu.cn/ubuntu-ports"))
        assertTrue(script.contains("apt-get update && updated=1 || true"))
        assertTrue(script.contains("test \"\$updated\" -eq 1"))
        assertTrue(script.contains("ca-certificates git python3 poppler-utils"))
        assertTrue(script.contains("AGENTDECK_APT_EOF"))
    }
}
