package com.agentdeck.app.domain.backup

import com.agentdeck.app.domain.model.ConversationIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBackupCodecTest {
    @Test
    fun round_trip_keeps_identity() {
        val original = ConversationBackupDocument(
            exportedAtEpochMs = 1700000000000L,
            conversations = listOf(
                ConversationBackupItem(
                    id = "card_ds",
                    name = "ds",
                    customTitle = "设计助手",
                    recipeId = "recipe_codex",
                    profileId = "profile_deepseek",
                    modelId = "deepseek-v4-flash",
                    permissionLevel = "ASK_FIRST",
                    workspacePath = "/root/projects/default",
                    pinned = true,
                    archived = false,
                    identity = ConversationIdentity(
                        roleName = "角色设计师",
                        selfDefinition = "帮用户打磨人设",
                        objective = "写清楚边界",
                    ),
                    selectedExtensionIds = listOf("ext_skill_a"),
                ),
            ),
        )
        val encoded = ConversationBackupCodec.encode(original)
        val decoded = ConversationBackupCodec.decode(encoded)
        assertEquals(original, decoded)
        assertTrue(!encoded.contains("sk-"))
        assertTrue(!encoded.contains("Bearer"))
    }

    @Test
    fun rejects_unknown_format() {
        try {
            ConversationBackupCodec.decode("{\"format\":\"other\",\"version\":1,\"exportedAtEpochMs\":1}")
            throw AssertionError("expected failure")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("不是 AgentDeck"))
        }
    }

    @Test
    fun blank_identity_is_dropped() {
        val encoded = ConversationBackupCodec.encode(
            ConversationBackupDocument(
                exportedAtEpochMs = 1700000000000L,
                conversations = listOf(
                    ConversationBackupItem(
                        id = "card_1",
                        name = "会话",
                        customTitle = null,
                        recipeId = "recipe_codex",
                        profileId = null,
                        modelId = null,
                        permissionLevel = null,
                        workspacePath = "/root/projects/default",
                        pinned = false,
                        archived = false,
                        identity = null,
                        selectedExtensionIds = emptyList(),
                    ),
                ),
            ),
        )
        val decoded = ConversationBackupCodec.decode(encoded)
        assertNull(decoded.conversations.single().identity)
        assertEquals("会话", decoded.conversations.single().name)
    }
}
