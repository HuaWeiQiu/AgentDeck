package com.agentdeck.app.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackWebPolicyTest {
    @Test
    fun allows_only_loopback_http() {
        assertTrue(LoopbackWebPolicy.isAllowedUrl("http://127.0.0.1:3080/"))
        assertTrue(LoopbackWebPolicy.isAllowedUrl("http://localhost:3080"))
        assertTrue(LoopbackWebPolicy.isAllowedUrl("https://127.0.0.1:8443/path"))
        assertFalse(LoopbackWebPolicy.isAllowedUrl("http://example.com/"))
        assertFalse(LoopbackWebPolicy.isAllowedUrl("file:///sdcard/x.html"))
        assertFalse(LoopbackWebPolicy.isAllowedUrl("content://media/1"))
        assertFalse(LoopbackWebPolicy.isAllowedUrl("http://192.168.1.1:3080/"))
        assertFalse(LoopbackWebPolicy.isAllowedUrl("javascript:alert(1)"))
    }

    @Test
    fun default_dsh_url_matches_official_port() {
        assertEquals("http://127.0.0.1:3080/", LoopbackWebPolicy.defaultDshUrl())
        assertEquals("http://127.0.0.1:4096/", LoopbackWebPolicy.defaultDshUrl(4096))
    }
}
