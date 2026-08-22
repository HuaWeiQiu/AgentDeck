package com.agentdeck.app.domain.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAutomatorDumpParserTest {

    private val sample = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
            package="com.example.app" content-desc="" checkable="false" password="false"
            clickable="true" enabled="true" focused="false" scrollable="false"
            bounds="[0,0][1080,2400]">
            <node index="1" text="登录" resource-id="com.example.app:id/login" class="android.widget.Button"
              package="com.example.app" content-desc="sign in" checkable="false" password="false"
              clickable="true" enabled="true" focused="false" scrollable="false"
              bounds="[40,2200][1040,2320]"/>
            <node index="2" text="" resource-id="com.example.app:id/pwd" class="android.widget.EditText"
              package="com.example.app" content-desc="" checkable="false" password="true"
              clickable="true" enabled="true" focused="true" scrollable="false"
              bounds="[40,800][1040,900]"/>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun `parses nodes with fingerprints matching a11y collect semantics`() {
        val nodes = UiAutomatorDumpParser.parse(sample, 1080, 2400)

        assertEquals(3, nodes.size)
        val root = nodes[0]
        assertEquals("com.example.app", root.packageName)
        assertEquals("android.widget.FrameLayout", root.className)
        assertTrue(root.clickable)
        assertEquals("android.widget.FrameLayout|||[0,0][1080,2400]", root.fingerprint)
        assertNull(root.parentFingerprint)

        val login = nodes[1]
        assertEquals("登录", login.text)
        assertEquals("sign in", login.contentDescription)
        assertTrue(login.clickable)
        assertFalse(login.editable)
        assertEquals(root.fingerprint, login.parentFingerprint)
        assertEquals(listOf(40, 2200, 1040, 2320).toList(), login.bounds.toList())

        val pwd = nodes[2]
        assertTrue(pwd.password)
        assertTrue(pwd.editable)
        assertTrue(pwd.focused)
    }

    @Test
    fun `malformed bounds fall back to zero rect`() {
        assertTrue(
            UiAutomatorDumpParser.parseBounds("[abc][def]").toList() ==
                intArrayOf(0, 0, 0, 0).toList(),
        )
        assertEquals(4, UiAutomatorDumpParser.parseBounds("").size)
    }

    @Test
    fun `depth and size caps are enforced`() {
        val deep = buildString {
            append("<hierarchy>")
            repeat(UiAutomationLimits.MAX_DEPTH + 5) { append("<node class=\"android.view.View\" bounds=\"[0,0][10,10]\">") }
            repeat(UiAutomationLimits.MAX_DEPTH + 5) { append("</node>") }
            append("</hierarchy>")
        }
        val nodes = UiAutomatorDumpParser.parse(deep, 10, 10)
        assertTrue(nodes.size <= UiAutomationLimits.MAX_NODES)
        assertTrue(nodes.size <= UiAutomationLimits.MAX_DEPTH)
    }

    @Test
    fun `extracts package name from dump`() {
        assertEquals("com.example.app", UiAutomatorDumpParser.packageNameOf(sample))
        assertEquals("", UiAutomatorDumpParser.packageNameOf(""))
    }

    @Test
    fun `window size parsed from wm size output`() {
        assertEquals(1260 to 2800, UiAutomatorDumpParser.windowSizeFromWmSize("Physical size: 1260x2800"))
        assertEquals(1440 to 3200, UiAutomatorDumpParser.windowSizeFromWmSize("Physical size: 1080x2400\nOverride size: 1440x3200"))
        assertEquals(1 to 1, UiAutomatorDumpParser.windowSizeFromWmSize("no display"))
    }
}
