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
        val policy = HostToolPolicy(ExperienceLevel.DEVELOPER, workspaceEnabled = true, hasWorkspaceGrant = true)
        // No tool maps to UI_AUTOMATION yet; capability list must not include L3/L4
        assertEquals(setOf(HostCapability.WORKSPACE_FS), policy.listEnabledCapabilities())
    }
}
