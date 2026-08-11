package com.agentdeck.app.domain.host

import com.agentdeck.app.domain.settings.ExperienceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostToolPolicyTest {
    @Test
    fun `standard mode denies all host tools`() {
        val policy = HostToolPolicy(
            experienceLevel = ExperienceLevel.STANDARD,
            workspaceEnabled = true,
            hasWorkspaceGrant = true,
        )
        val denied = policy.evaluate(HostToolName.WORKSPACE_READ)
        assertNotNull(denied)
        assertEquals("host_standard_mode", denied!!.code)
        assertTrue(policy.listEnabledCapabilities().isEmpty())
    }

    @Test
    fun `advanced requires explicit workspace switch and grant`() {
        val noSwitch = HostToolPolicy(ExperienceLevel.ADVANCED, workspaceEnabled = false, hasWorkspaceGrant = true)
        assertEquals("host_workspace_disabled", noSwitch.evaluate(HostToolName.WORKSPACE_STAT)!!.code)

        val noGrant = HostToolPolicy(ExperienceLevel.ADVANCED, workspaceEnabled = true, hasWorkspaceGrant = false)
        assertEquals("host_workspace_no_grant", noGrant.evaluate(HostToolName.WORKSPACE_STAT)!!.code)

        val ok = HostToolPolicy(ExperienceLevel.ADVANCED, workspaceEnabled = true, hasWorkspaceGrant = true)
        assertNull(ok.evaluate(HostToolName.WORKSPACE_READ))
        assertEquals(setOf(HostCapability.WORKSPACE_FS), ok.listEnabledCapabilities())
    }

    @Test
    fun `unimplemented capabilities stay denied`() {
        // Even developer + flags cannot enable L3/L4 until implemented
        val policy = HostToolPolicy(
            ExperienceLevel.DEVELOPER,
            workspaceEnabled = true,
            hasWorkspaceGrant = true,
            maxHostLevel = 4,
        )
        // No tool maps to UI_AUTOMATION yet; capability list must not include L3/L4
        assertEquals(setOf(HostCapability.WORKSPACE_FS), policy.listEnabledCapabilities())
    }

    @Test
    fun `secure channel max level blocks above L1`() {
        val secure = HostToolPolicy(
            experienceLevel = ExperienceLevel.ADVANCED,
            workspaceEnabled = true,
            hasWorkspaceGrant = true,
            maxHostLevel = 1,
        )
        // WORKSPACE is L1 — allowed by channel (still needs grant checks which pass)
        assertEquals(null, secure.evaluate(HostToolName.WORKSPACE_STAT))
        // No L2+ tools in enum wire yet for share; channel check uses capability.level
        assertEquals(1, HostCapability.WORKSPACE_FS.level)
        assertEquals(2, HostCapability.SHARE_INTENT.level)
        assertTrue(HostCapability.SHARE_INTENT.level > secure.maxHostLevel)
    }
}
