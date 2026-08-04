package com.agentdeck.app.data.repo

import android.content.Context
import com.agentdeck.app.data.db.AgentCardEntity
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.db.ProviderProfileEntity
import com.agentdeck.app.data.secure.SecureKeyStore
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.model.RecipeSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.yaml.snakeyaml.Yaml
import java.util.UUID

class ProfileRepository(
    private val db: AppDatabase,
    private val keyStore: SecureKeyStore,
) {
    fun observeProfiles(): Flow<List<ProviderProfile>> =
        db.providerProfileDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getProfile(id: String): ProviderProfile? =
        db.providerProfileDao().getById(id)?.toDomain()

    suspend fun saveProfile(
        existingId: String?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        defaultModel: String,
        apiKey: String?,
    ): ProviderProfile {
        val id = existingId ?: "prof_${UUID.randomUUID().toString().take(8)}"
        val keyRef = "keystore:$id"
        if (!apiKey.isNullOrBlank()) {
            keyStore.put(keyRef, apiKey.trim())
        }
        val profile = ProviderProfile(
            id = id,
            name = name.trim(),
            type = type,
            baseUrl = baseUrl.trim(),
            defaultModel = defaultModel.trim(),
            keyRef = keyRef,
        )
        db.providerProfileDao().upsert(ProviderProfileEntity.from(profile))
        return profile
    }

    suspend fun deleteProfile(id: String) {
        val existing = db.providerProfileDao().getById(id)
        if (existing != null) {
            keyStore.delete(existing.keyRef)
            db.providerProfileDao().delete(id)
        }
    }

    fun getApiKey(profile: ProviderProfile): String? = keyStore.get(profile.keyRef)

    suspend fun ensureSeedProfiles() {
        if (db.providerProfileDao().count() > 0) return
        saveProfile(
            existingId = "prof_openai_demo",
            name = "OpenAI Compatible",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-5",
            apiKey = null,
        )
        saveProfile(
            existingId = "prof_anthropic_demo",
            name = "Anthropic",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            defaultModel = "claude-sonnet-4-20250514",
            apiKey = null,
        )
    }
}

class CardRepository(
    private val db: AppDatabase,
) {
    fun observeCards(): Flow<List<AgentCard>> =
        db.agentCardDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getCard(id: String): AgentCard? =
        db.agentCardDao().getById(id)?.toDomain()

    suspend fun saveCard(card: AgentCard) {
        db.agentCardDao().upsert(AgentCardEntity.from(card))
    }

    suspend fun deleteCard(id: String) {
        db.agentCardDao().delete(id)
    }

    suspend fun ensureSeedCards() {
        if (db.agentCardDao().count() > 0) return
        saveCard(
            AgentCard(
                id = "card_codex_default",
                name = "Codex",
                icon = "codex",
                recipeId = "recipe_codex",
                templateId = "tpl_codex_ubuntu",
                profileId = "prof_openai_demo",
                termuxSessionName = "agentdeck-codex-default",
                workspaceNamespace = PathNamespace.UBUNTU,
                workspacePath = "/root/projects/default",
                distro = "ubuntu",
                innerBin = "codex",
            ),
        )
        saveCard(
            AgentCard(
                id = "card_claude_default",
                name = "Claude Code",
                icon = "claude",
                recipeId = "recipe_claude_code",
                templateId = "tpl_claude_termux",
                profileId = "prof_anthropic_demo",
                termuxSessionName = "agentdeck-claude-default",
                workspaceNamespace = PathNamespace.TERMUX,
                workspacePath = "/data/data/com.termux/files/home",
                distro = "ubuntu",
                innerBin = "claude",
            ),
        )
    }
}

class RecipeRepository(
    private val context: Context,
) {
    fun loadRecipes(): List<RecipeSummary> {
        val yaml = Yaml()
        val am = context.assets
        val names = am.list("recipes")?.filter { it.endsWith(".yaml") || it.endsWith(".yml") }
            ?: emptyList()
        return names.mapNotNull { file ->
            runCatching {
                am.open("recipes/$file").use { input ->
                    @Suppress("UNCHECKED_CAST")
                    val map = yaml.load<Map<String, Any?>>(input) ?: return@use null
                    val depends = (map["depends_on"] as? List<*>)?.mapNotNull { it?.toString() }
                        ?: emptyList()
                    RecipeSummary(
                        id = map["id"]?.toString() ?: file,
                        name = map["name"]?.toString() ?: file,
                        description = map["description"]?.toString().orEmpty(),
                        priority = map["priority"]?.toString() ?: "p1",
                        dependsOn = depends,
                    )
                }
            }.getOrNull()
        }.sortedBy { it.priority }
    }

    fun readWrapperAsset(name: String): String {
        return context.assets.open("wrappers/$name").bufferedReader().use { it.readText() }
    }
}
