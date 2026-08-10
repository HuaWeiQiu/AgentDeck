package com.agentdeck.app.domain.chat

import com.agentdeck.app.domain.model.ConversationIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdentityPolicyTest {
    @Test
    fun `identity is normalized and compiled as persistent self definition`() {
        val identity = ConversationIdentity(
            roleName = "  林老师  ",
            selfDefinition = " 一位耐心的中文写作导师 ",
            objective = "帮助用户改进文章",
            communicationStyle = "温和、直接",
            boundaries = "不替用户编造事实",
        )

        val normalized = ConversationIdentityPolicy.normalize(identity)
        val instructions = ConversationIdentityPolicy.instructions(requireNotNull(normalized))

        assertEquals("林老师", normalized.roleName)
        assertTrue(instructions.contains("you are 林老师"))
        assertTrue(instructions.contains("persistent identity"))
        assertTrue(instructions.contains("do not answer with the Codex"))
        assertFalse(instructions.contains("pretend"))
    }

    @Test
    fun `conversation identity appends without replacing global developer instructions`() {
        val config = JSONObject().put("developer_instructions", "全局规则")
        val merged = ConversationIdentityPolicy.mergeIntoConfig(
            config,
            ConversationIdentity("侦探", "负责推理案件线索"),
        )

        assertEquals("全局规则", config.getString("developer_instructions"))
        assertTrue(merged.getString("developer_instructions").startsWith("全局规则\n\n"))
        assertTrue(merged.getString("developer_instructions").contains("you are 侦探"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `identity requires a self definition`() {
        ConversationIdentityPolicy.normalize(ConversationIdentity("角色", "  "))
    }
}
