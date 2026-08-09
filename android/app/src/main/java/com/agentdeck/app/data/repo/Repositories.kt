package com.agentdeck.app.data.repo

import android.content.res.AssetManager
import androidx.room.withTransaction
import com.agentdeck.app.data.db.AgentCardEntity
import com.agentdeck.app.data.db.AppMetadataEntity
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.db.ProviderProfileEntity
import com.agentdeck.app.data.db.ProviderModelEntity
import com.agentdeck.app.domain.model.AgentCard
import com.agentdeck.app.domain.model.AgentRecipe
import com.agentdeck.app.domain.model.PathNamespace
import com.agentdeck.app.domain.model.ProviderProfile
import com.agentdeck.app.domain.model.ProviderAdapterId
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.model.ProviderModel
import com.agentdeck.app.domain.model.ProviderType
import com.agentdeck.app.domain.profiles.ProfileInputValidator
import com.agentdeck.app.domain.recipe.RecipeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ProfileRepository(
    private val db: AppDatabase,
) {
    fun observeProfiles(): Flow<List<ProviderProfile>> =
        db.providerProfileDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getProfile(id: String): ProviderProfile? =
        db.providerProfileDao().getById(id)?.toDomain()

    suspend fun getProfileByBaseUrl(baseUrl: String): ProviderProfile? =
        db.providerProfileDao().getByBaseUrl(baseUrl)?.toDomain()

    suspend fun saveProfile(
        existingId: String?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        defaultModel: String,
        adapterId: ProviderAdapterId = if (type == ProviderType.ANTHROPIC) {
            ProviderAdapterId.ANTHROPIC
        } else {
            ProviderAdapterId.OPENAI_RESPONSES
        },
        credentialRef: String? = null,
        connectionStatus: ProviderConnectionStatus = ProviderConnectionStatus.UNVERIFIED,
        lastCheckedAtEpochMs: Long? = null,
    ): ProviderProfile = db.withTransaction {
        ProfileInputValidator.validate(name, baseUrl, defaultModel).getOrThrow()
        val id = existingId ?: "prof_${UUID.randomUUID().toString().take(8)}"
        val existing = existingId?.let { db.providerProfileDao().getById(it) }
        require(existingId == null || existing != null) { "要编辑的 CLI 配置不存在" }
        if (existing != null && existing.type != type.name) {
            require(db.agentCardDao().countByProfileId(existing.id) == 0) {
                "该配置仍被卡片引用，解除绑定后才能更改 Provider 类型"
            }
        }
        if (existing != null && existing.baseUrl != baseUrl.trim()) {
            require(db.agentCardDao().countByProfileId(existing.id) == 0) {
                "该服务已有对话，不能更改 Base URL；请复制为新服务"
            }
        }
        val now = System.currentTimeMillis()
        val profile = ProviderProfile(
            id = id,
            name = name.trim(),
            type = type,
            baseUrl = baseUrl.trim(),
            defaultModel = defaultModel.trim(),
            adapterId = adapterId,
            credentialRef = credentialRef ?: existing?.credentialRef,
            connectionStatus = connectionStatus,
            lastCheckedAtEpochMs = lastCheckedAtEpochMs,
            createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
            updatedAtEpochMs = now,
        )
        db.providerProfileDao().upsert(ProviderProfileEntity.from(profile))
        profile
    }

    suspend fun deleteProfile(id: String): Int = db.withTransaction {
        val affectedCards = db.agentCardDao().countByProfileId(id)
        require(affectedCards == 0) { "该服务仍被 $affectedCards 个对话使用，请先解除绑定" }
        db.providerProfileDao().delete(id)
        affectedCards
    }

    fun observeModels(providerId: String): Flow<List<ProviderModel>> =
        db.providerModelDao().observeByProvider(providerId)
            .map { list -> list.map { it.toDomain() } }

    fun observeAllModels(): Flow<List<ProviderModel>> =
        db.providerModelDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getModels(providerId: String): List<ProviderModel> =
        db.providerModelDao().getByProvider(providerId).map { it.toDomain() }

    suspend fun replaceModels(providerId: String, models: List<ProviderModel>) = db.withTransaction {
        require(db.providerProfileDao().getById(providerId) != null) { "模型服务不存在" }
        require(models.all { it.providerId == providerId }) { "模型列表包含错误的服务引用" }
        db.providerModelDao().deleteByProvider(providerId)
        db.providerModelDao().upsertAll(models.map(ProviderModelEntity::from))
    }

    suspend fun saveProfileAndModels(
        existingId: String?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        defaultModel: String,
        adapterId: ProviderAdapterId,
        credentialRef: String,
        connectionStatus: ProviderConnectionStatus,
        lastCheckedAtEpochMs: Long,
        models: List<ProviderModel>,
    ): ProviderProfile = db.withTransaction {
        require(type == ProviderType.OPENAI_COMPATIBLE) { "Codex 模型服务类型不受支持" }
        require(
            adapterId == ProviderAdapterId.SUB2API ||
                adapterId == ProviderAdapterId.OPENAI_RESPONSES,
        ) { "Codex Provider adapter 不受支持" }
        require(
            connectionStatus == ProviderConnectionStatus.READY ||
                connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED,
        ) { "模型服务尚未通过验证" }
        require(
            connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED ||
                models.any { it.id == defaultModel },
        ) { "默认模型不在服务返回的模型列表中" }
        val profile = saveProfile(
            existingId = existingId,
            name = name,
            type = type,
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            adapterId = adapterId,
            credentialRef = credentialRef,
            connectionStatus = connectionStatus,
            lastCheckedAtEpochMs = lastCheckedAtEpochMs,
        )
        db.providerModelDao().deleteByProvider(profile.id)
        db.providerModelDao().upsertAll(
            normalizeProviderModels(profile.id, models).map(ProviderModelEntity::from),
        )
        profile
    }

    suspend fun ensureSeedProfiles() {
        if (db.providerProfileDao().count() > 0) return
        defaultSeedProfiles(System.currentTimeMillis()).forEach { profile ->
            ProfileInputValidator.validate(
                profile.name,
                profile.baseUrl,
                profile.defaultModel,
            ).getOrThrow()
            db.providerProfileDao().upsert(ProviderProfileEntity.from(profile))
        }
    }
}

internal fun normalizeProviderModels(
    providerId: String,
    models: List<ProviderModel>,
): List<ProviderModel> = models.map { it.copy(providerId = providerId) }

internal fun defaultSeedProfiles(@Suppress("UNUSED_PARAMETER") createdAtEpochMs: Long): List<ProviderProfile> =
    emptyList()

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
                profileId = null,
                modelId = null,
                termuxSessionName = "agentdeck-codex-default",
                workspaceNamespace = PathNamespace.UBUNTU,
                workspacePath = "/root/projects/default",
                distro = "ubuntu",
                innerBin = "codex",
            ),
        )
    }
}

class InitialDataSeeder(
    private val db: AppDatabase,
    private val profiles: ProfileRepository,
    private val cards: CardRepository,
) {
    suspend fun ensureInitialData() = db.withTransaction {
        if (db.appMetadataDao().getValue(INITIAL_SEED_KEY) == "true") return@withTransaction
        profiles.ensureSeedProfiles()
        cards.ensureSeedCards()
        db.appMetadataDao().upsert(AppMetadataEntity(INITIAL_SEED_KEY, "true"))
    }

    companion object {
        private const val INITIAL_SEED_KEY = "initial_seed_completed"
    }
}

class RecipeRepository(
    private val assets: AssetManager,
) : RecipeCatalog {
    private val cachedRecipes: List<AgentRecipe> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val names = assets.list("recipes")?.filter { it.endsWith(".yaml") || it.endsWith(".yml") }
            ?: emptyList()
        require(names.isNotEmpty()) { "APK 中没有配方资源" }
        names.sorted().map { file ->
            assets.open("recipes/$file").use { input ->
                RecipeParser.parse(input, "recipes/$file")
            }
        }.also { recipes ->
            val byId = recipes.associateBy { it.id }
            require(byId.size == recipes.size) { "APK 配方包含重复 ID" }
            recipes.forEach { recipe ->
                val missing = recipe.dependsOn.filterNot(byId::containsKey)
                require(missing.isEmpty()) {
                    "配方 ${recipe.id} 缺少依赖: ${missing.joinToString()}"
                }
            }
        }.sortedWith(compareBy<AgentRecipe> { it.priority }.thenBy { it.name })
    }

    override fun loadRecipes(): List<AgentRecipe> = cachedRecipes

    override fun readWrapperAsset(name: String): String {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "wrapper 资源名无效"
        }
        return assets.open("wrappers/$name").bufferedReader().use { it.readText() }
    }
}
