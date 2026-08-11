package com.agentdeck.app.data.extensions

import androidx.room.withTransaction
import com.agentdeck.app.data.chat.CodexBridgeLauncher
import com.agentdeck.app.data.db.AgentCardEntity
import com.agentdeck.app.data.db.AgentCardExtensionEntity
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.db.ExtensionEntity
import com.agentdeck.app.data.db.ExtensionToolEntity
import com.agentdeck.app.data.db.McpExtensionConfigEntity
import com.agentdeck.app.data.db.SkillExtensionConfigEntity
import com.agentdeck.app.data.db.toDomain
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.data.secure.ExtensionCredentialVault
import com.agentdeck.app.domain.extensions.ExtensionAuthType
import com.agentdeck.app.domain.extensions.ExtensionKind
import com.agentdeck.app.domain.extensions.ExtensionLevel
import com.agentdeck.app.domain.extensions.ExtensionPolicy
import com.agentdeck.app.domain.extensions.ExtensionSessionHandle
import com.agentdeck.app.domain.extensions.ExtensionSessionPlan
import com.agentdeck.app.domain.extensions.ExtensionStatus
import com.agentdeck.app.domain.extensions.ExtensionTool
import com.agentdeck.app.domain.extensions.ManagedExtension
import com.agentdeck.app.domain.extensions.McpServerApprovalIdentity
import com.agentdeck.app.domain.model.AgentCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

internal class ExtensionRepository(
    private val db: AppDatabase,
    private val policy: ExtensionPolicy,
    private val credentials: ExtensionCredentialVault,
    private val paths: EmbeddedRuntimePaths,
    private val skills: SkillPackageInstaller = SkillPackageInstaller(paths),
    private val secureMcpClient: OkHttpClient = secureMcpHttpClient(),
) {
    private val skillImportMutex = Mutex()
    private val storageReconcileMutex = Mutex()
    @Volatile private var storageReconciled = false
    val requiresManagedMcp: Boolean = !policyAllowsLocal()

    fun observeExtensions(): Flow<List<ManagedExtension>> = flow {
        ensureStorageReconciled()
        emitAll(
            combine(
                db.extensionDao().observeAll(),
                db.mcpExtensionConfigDao().observeAll(),
                db.skillExtensionConfigDao().observeAll(),
                db.extensionToolDao().observeAll(),
            ) { extensions, mcpConfigs, skillConfigs, tools ->
                val mcpById = mcpConfigs.associateBy { it.extensionId }
                val skillById = skillConfigs.associateBy { it.extensionId }
                val toolsById = tools.groupBy { it.extensionId }
                extensions.map { extension ->
                    extension.toDomain(
                        mcpById[extension.id],
                        skillById[extension.id],
                        toolsById[extension.id].orEmpty(),
                    )
                }
            },
        )
    }

    fun observeCardSelections(): Flow<Map<String, Set<String>>> =
        db.agentCardExtensionDao().observeAll().map { rows ->
            rows.groupBy { it.cardId }.mapValues { (_, values) ->
                values.mapTo(linkedSetOf()) { it.extensionId }
            }
        }

    suspend fun getAll(): List<ManagedExtension> {
        ensureStorageReconciled()
        return hydrate(db.extensionDao().getAll())
    }

    suspend fun getById(id: String): ManagedExtension? {
        ensureStorageReconciled()
        return db.extensionDao().getById(id)?.let { hydrate(listOf(it)).single() }
    }

    suspend fun saveRemoteMcp(
        existingId: String?,
        name: String,
        description: String,
        url: String,
        authType: ExtensionAuthType,
        bearerToken: ByteArray?,
        discoveredTools: List<ExtensionTool>,
    ): ManagedExtension {
        ensureStorageReconciled()
        val normalizedUrl = policy.validateRemoteUrl(url).toString()
        validateDisplay(name, description)
        require(discoveredTools.isNotEmpty()) { "请先连接并获取 MCP 工具列表" }
        val validatedTools = policy.normalizeTools(discoveredTools)
        val existing = existingId?.let { requireNotNull(getById(it)) { "扩展不存在" } }
        require(existing == null || existing.kind == ExtensionKind.REMOTE_MCP) { "扩展类型不能更改" }
        val id = existing?.id ?: newExtensionId()
        val oldCredential = existing?.mcp?.credentialRef
        var createdCredential: String? = null
        val credentialRef = when (authType) {
            ExtensionAuthType.NONE -> null
            ExtensionAuthType.BEARER -> if (bearerToken != null && bearerToken.isNotEmpty()) {
                newCredentialRef().also { ref ->
                    credentials.save(ref, bearerToken)
                    createdCredential = ref
                }
            } else {
                require(
                    existing?.mcp?.authType == ExtensionAuthType.BEARER &&
                        existing.mcp.url == normalizedUrl,
                ) { "地址或鉴权方式已变化，请重新输入 Bearer Token" }
                requireNotNull(oldCredential?.takeIf(credentials::contains)) { "请输入 Bearer Token" }
            }
        }
        val normalizedTools = preserveToolSelections(validatedTools, existing?.tools.orEmpty())
            .map { it.copy(extensionId = id) }
        val level = policy.levelFor(ExtensionKind.REMOTE_MCP, normalizedTools)
        val now = System.currentTimeMillis()
        try {
            db.withTransaction {
                db.extensionDao().upsert(
                    ExtensionEntity(
                        id = id,
                        name = name.trim(),
                        description = description.trim(),
                        kind = ExtensionKind.REMOTE_MCP.name,
                        requiredLevel = level.value,
                        enabled = existing?.enabled ?: true,
                        status = ExtensionStatus.READY.name,
                        createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                        updatedAtEpochMs = now,
                    ),
                )
                db.mcpExtensionConfigDao().upsert(
                    McpExtensionConfigEntity(
                        extensionId = id,
                        transport = "streamable_http",
                        url = normalizedUrl,
                        command = null,
                        argsJson = "[]",
                        authType = authType.name,
                        credentialRef = credentialRef,
                    ),
                )
                db.extensionToolDao().deleteByExtensionId(id)
                db.extensionToolDao().upsertAll(normalizedTools.map(ExtensionToolEntity::from))
            }
        } catch (error: Exception) {
            createdCredential?.let(credentials::delete)
            throw error
        }
        if (oldCredential != null && oldCredential != credentialRef) credentials.delete(oldCredential)
        return requireNotNull(getById(id))
    }

    suspend fun saveLocalMcp(
        existingId: String?,
        name: String,
        description: String,
        command: String,
        args: List<String>,
    ): ManagedExtension {
        policy.validateLocalCommand(command.trim(), args)
        validateDisplay(name, description)
        val existing = existingId?.let { requireNotNull(getById(it)) { "扩展不存在" } }
        require(existing == null || existing.kind == ExtensionKind.LOCAL_MCP) { "扩展类型不能更改" }
        val id = existing?.id ?: newExtensionId()
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.extensionDao().upsert(
                ExtensionEntity(
                    id = id,
                    name = name.trim(),
                    description = description.trim(),
                    kind = ExtensionKind.LOCAL_MCP.name,
                    requiredLevel = ExtensionLevel.LOCAL_PROCESS.value,
                    enabled = existing?.enabled ?: true,
                    status = ExtensionStatus.READY.name,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                    updatedAtEpochMs = now,
                ),
            )
            db.mcpExtensionConfigDao().upsert(
                McpExtensionConfigEntity(
                    extensionId = id,
                    transport = "stdio",
                    url = null,
                    command = command.trim(),
                    argsJson = JSONArray(args).toString(),
                    authType = ExtensionAuthType.NONE.name,
                    credentialRef = null,
                ),
            )
        }
        return requireNotNull(getById(id))
    }

    suspend fun importSkill(input: InputStream): ManagedExtension = skillImportMutex.withLock {
        ensureStorageReconciled()
        policy.requireAllowed(ExtensionLevel.SKILL)
        val id = newExtensionId()
        val installed = skills.install(id, input)
        val now = System.currentTimeMillis()
        try {
            require(getAll().none { it.kind == ExtensionKind.SKILL && it.name == installed.name }) {
                "同名 Skill 已经存在"
            }
            db.withTransaction {
                db.extensionDao().upsert(
                    ExtensionEntity(
                        id = id,
                        name = installed.name,
                        description = installed.description,
                        kind = ExtensionKind.SKILL.name,
                        requiredLevel = ExtensionLevel.SKILL.value,
                        enabled = true,
                        status = ExtensionStatus.READY.name,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
                db.skillExtensionConfigDao().upsert(
                    SkillExtensionConfigEntity(
                        extensionId = id,
                        installedPath = installed.path,
                        version = null,
                        manifestHash = installed.manifestHash,
                    ),
                )
            }
        } catch (error: Exception) {
            skills.delete(installed.path)
            throw error
        }
        requireNotNull(getById(id))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        requireNotNull(getById(id)) { "扩展不存在" }
        db.extensionDao().setEnabled(id, enabled, System.currentTimeMillis())
    }

    suspend fun setToolEnabled(extensionId: String, toolName: String, enabled: Boolean) {
        db.withTransaction {
            val extension = requireNotNull(getById(extensionId)) { "扩展不存在" }
            require(extension.tools.any { it.name == toolName }) { "MCP 工具不存在" }
            db.extensionToolDao().setEnabled(extensionId, toolName, enabled)
            val tools = db.extensionToolDao().getByExtensionId(extensionId).map(ExtensionToolEntity::toDomain)
            val level = policy.levelFor(extension.kind, tools)
            db.extensionDao().upsert(
                ExtensionEntity(
                    id = extension.id,
                    name = extension.name,
                    description = extension.description,
                    kind = extension.kind.name,
                    requiredLevel = level.value,
                    enabled = extension.enabled,
                    status = extension.status.name,
                    createdAtEpochMs = extension.createdAtEpochMs,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun delete(id: String) {
        val extension = requireNotNull(getById(id)) { "扩展不存在" }
        db.extensionDao().delete(id)
        extension.mcp?.credentialRef?.let(credentials::delete)
        extension.skill?.installedPath?.let(skills::delete)
    }

    suspend fun saveCardWithSelections(card: AgentCard, extensionIds: Set<String>) {
        db.withTransaction {
            val all = getAll().associateBy(ManagedExtension::id)
            extensionIds.forEach { id ->
                val extension = requireNotNull(all[id]) { "所选扩展不存在" }
                policy.requireAllowed(extension.requiredLevel)
            }
            db.agentCardDao().upsert(AgentCardEntity.from(card))
            db.agentCardExtensionDao().deleteByCardId(card.id)
            db.agentCardExtensionDao().insertAll(
                extensionIds.distinct().map { AgentCardExtensionEntity(card.id, it) },
            )
        }
    }

    suspend fun openSession(cardId: String): ExtensionSessionHandle {
        val all = getAll()
        val selectedIds = db.agentCardExtensionDao().getExtensionIds(cardId).toSet()
        val selected = all.filter { it.id in selectedIds && it.enabled && it.status == ExtensionStatus.READY }
        selected.forEach { policy.requireAllowed(it.requiredLevel) }
        val resources = mutableListOf<AutoCloseable>()
        try {
            val instanceKey = CodexBridgeLauncher.instanceKey(cardId)
            val snapshot = SkillSnapshot.create(
                paths = paths,
                key = instanceKey,
                skills = selected.filter { it.kind == ExtensionKind.SKILL },
            )
            resources += snapshot
            val servers = JSONObject()
            val approvalIdentities = linkedMapOf<String, McpServerApprovalIdentity>()
            selected.filter { it.kind != ExtensionKind.SKILL }.forEach { extension ->
                val mcp = requireNotNull(extension.mcp) { "MCP 配置不存在" }
                val toolAllowlist = extensionToolAllowlist(extension.kind, extension.tools)
                val config = JSONObject()
                    .put("enabled", true)
                    .put("required", true)
                    .put("default_tools_approval_mode", "prompt")
                    .put("startup_timeout_sec", 15)
                    .put("tool_timeout_sec", 90)
                when (extension.kind) {
                    ExtensionKind.REMOTE_MCP -> {
                        val endpoint = policy.validateRemoteUrl(requireNotNull(mcp.url))
                        val proxy = SecureMcpProxy(
                            upstream = endpoint,
                            credentialVault = credentials,
                            credentialRef = mcp.credentialRef,
                            client = secureMcpClient,
                        )
                        resources += proxy
                        config.put("url", proxy.url)
                    }
                    ExtensionKind.LOCAL_MCP -> {
                        val command = requireNotNull(mcp.command)
                        policy.validateLocalCommand(command, mcp.args)
                        LocalMcpRuntimeLoader.adapter.apply(config, mcp)
                    }
                    ExtensionKind.SKILL -> Unit
                }
                if (toolAllowlist.enforce) {
                    config.put("enabled_tools", JSONArray(toolAllowlist.enabledToolNames))
                }
                val managedServerId = serverId(extension.id)
                servers.put(managedServerId, config)
                approvalIdentities[managedServerId] = McpServerApprovalIdentity(
                    displayName = extension.name,
                    enabledToolNames = toolAllowlist.enabledToolNames.toSet(),
                    enforceAllowlist = toolAllowlist.enforce,
                )
            }
            val overlay = JSONObject().put("mcp_servers", servers)
            return ExtensionSessionHandle(
                plan = ExtensionSessionPlan(
                    configOverlay = overlay.toString(),
                    skillSnapshotKey = snapshot.key,
                    enabledNames = selected.map(ManagedExtension::name),
                    mcpApprovalIdentities = approvalIdentities,
                ),
                resources = resources,
            )
        } catch (error: Exception) {
            resources.asReversed().forEach { runCatching(it::close) }
            throw error
        }
    }

    fun discoverRemote(url: String, bearer: ByteArray? = null): List<ExtensionTool> =
        RemoteMcpToolDiscovery(policy, secureMcpClient).discover(url, bearer)

    fun mergeSessionConfig(
        base: JSONObject,
        plan: ExtensionSessionPlan,
        inheritedServerIds: Set<String> = emptySet(),
    ): JSONObject = ExtensionConfigComposer.merge(
        base = base,
        overlay = JSONObject(plan.configOverlay),
        managedOnly = requiresManagedMcp,
        inheritedServerIds = inheritedServerIds,
    )

    private suspend fun hydrate(extensions: List<ExtensionEntity>): List<ManagedExtension> {
        val mcp = db.mcpExtensionConfigDao().getAll().associateBy { it.extensionId }
        val skill = db.skillExtensionConfigDao().getAll().associateBy { it.extensionId }
        val tools = db.extensionToolDao().getAll().groupBy { it.extensionId }
        return extensions.map { it.toDomain(mcp[it.id], skill[it.id], tools[it.id].orEmpty()) }
    }

    private suspend fun ensureStorageReconciled() {
        if (storageReconciled) return
        withContext(Dispatchers.IO) {
            storageReconcileMutex.withLock {
                if (storageReconciled) return@withLock
                val mcpConfigs = db.mcpExtensionConfigDao().getAll()
                val skillConfigs = db.skillExtensionConfigDao().getAll()
                credentials.pruneExcept(mcpConfigs.mapNotNullTo(hashSetOf()) { it.credentialRef })
                skills.pruneExcept(skillConfigs.mapTo(hashSetOf()) { it.installedPath })
                storageReconciled = true
            }
        }
    }

    private fun validateDisplay(name: String, description: String) {
        require(name.isNotBlank() && name.trim().length <= 80 && name.none(Char::isISOControl)) {
            "扩展名称不能为空且不能超过 80 字符"
        }
        require(description.length <= 500 && description.none(Char::isISOControl)) {
            "扩展说明不能超过 500 字符"
        }
    }

    private fun policyAllowsLocal(): Boolean = runCatching {
        policy.requireAllowed(ExtensionLevel.LOCAL_PROCESS)
        true
    }.getOrDefault(false)

    companion object {
        private fun newExtensionId() = "ext_${randomHex(16)}"
        private fun newCredentialRef() = "extcred_${randomHex(16)}"
        private fun randomHex(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        internal fun serverId(extensionId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("agentdeck-managed-mcp-v1:$extensionId".toByteArray(StandardCharsets.UTF_8))
                .take(8)
                .joinToString("") { byte -> "%02x".format(byte) }
            return "agentdeck_ext_$digest"
        }
    }
}

internal fun preserveToolSelections(
    discovered: List<ExtensionTool>,
    existing: List<ExtensionTool>,
): List<ExtensionTool> {
    val enabledByName = existing.associate { it.name to it.enabled }
    return discovered.map { tool ->
        tool.copy(enabled = enabledByName[tool.name] ?: tool.enabled)
    }
}

internal data class ExtensionToolAllowlist(
    val enforce: Boolean,
    val enabledToolNames: List<String>,
)

internal fun extensionToolAllowlist(
    kind: ExtensionKind,
    tools: List<ExtensionTool>,
): ExtensionToolAllowlist = ExtensionToolAllowlist(
    enforce = kind == ExtensionKind.REMOTE_MCP || tools.isNotEmpty(),
    enabledToolNames = tools.filter(ExtensionTool::enabled).map(ExtensionTool::name),
)
