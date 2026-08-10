package com.agentdeck.app.ui.models

import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsViewModelTest {
    @Test
    fun `adapter selection treats Sub2API as a Responses preset`() {
        val draft = ProviderEditorDraft(
            id = null,
            name = "Sub2API",
            adapterId = ProviderAdapterId.SUB2API,
            baseUrl = "https://example.com/v1",
            apiKey = "",
            model = "model",
            models = emptyList(),
            hasStoredCredential = false,
            validated = true,
            status = ProviderConnectionStatus.READY,
        )

        val generic = draft.selectAdapter(ProviderAdapterId.OPENAI_RESPONSES)
        val restored = generic.selectAdapter(ProviderAdapterId.SUB2API)

        assertEquals("Responses 服务", generic.name)
        assertFalse(generic.validated)
        assertEquals("Sub2API", restored.name)
    }

    @Test
    fun `adapter selection preserves an existing custom name`() {
        val draft = ProviderEditorDraft(
            id = "prof_1",
            name = "公司网关",
            adapterId = ProviderAdapterId.SUB2API,
            baseUrl = "https://example.com/v1",
            apiKey = "",
            model = "model",
            models = emptyList(),
            hasStoredCredential = true,
            validated = true,
            status = ProviderConnectionStatus.READY,
        )

        val selected = draft.selectAdapter(ProviderAdapterId.OPENAI_RESPONSES)

        assertEquals("公司网关", selected.name)
        assertTrue(selected.hasStoredCredential)
    }
}
