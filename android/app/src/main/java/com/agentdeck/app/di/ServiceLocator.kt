package com.agentdeck.app.di

import android.content.Context
import com.agentdeck.app.data.config.CodexProfileRepository
import com.agentdeck.app.data.chat.CodexBridgeLauncher
import com.agentdeck.app.data.chat.ChatAttachmentStore
import com.agentdeck.app.data.chat.ConversationLinkRepository
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.extensions.ExtensionRepository
import com.agentdeck.app.data.extensions.secureMcpHttpClient
import com.agentdeck.app.data.provider.OkHttpProviderModelDiscovery
import com.agentdeck.app.data.provider.ProviderModelDiscovery
import com.agentdeck.app.data.backup.ConversationBackupRepository
import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ExperienceSettingsRepository
import com.agentdeck.app.data.repo.InitialDataSeeder
import com.agentdeck.app.data.repo.OnboardingRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.repo.RecipeRepository
import android.net.Uri
import com.agentdeck.app.data.host.DefaultHostToolBroker
import com.agentdeck.app.data.host.HostToolRelay
import com.agentdeck.app.data.host.MutableHostApprovalGateway
import com.agentdeck.app.data.host.SafWorkspaceDocumentStore
import com.agentdeck.app.BuildConfig
import com.agentdeck.app.data.host.WorkspaceGrantRepository
import com.agentdeck.app.data.runtime.EmbeddedProotRuntime
import com.agentdeck.app.data.runtime.EmbeddedRuntimeInstaller
import com.agentdeck.app.data.runtime.EmbeddedRuntimePaths
import com.agentdeck.app.data.runtime.RuntimeInventory
import com.agentdeck.app.data.secure.AndroidExtensionCredentialVault
import com.agentdeck.app.data.secure.AndroidProviderCredentialVault
import com.agentdeck.app.data.secure.ExtensionCredentialVault
import com.agentdeck.app.data.secure.ProviderCredentialVault
import com.agentdeck.app.domain.env.EmbeddedEnvironmentProbe
import com.agentdeck.app.domain.env.EnvironmentScanner
import com.agentdeck.app.domain.extensions.ExtensionPolicy
import com.agentdeck.app.domain.host.HostToolBroker
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.model.ProviderConnectionStatus
import com.agentdeck.app.domain.setup.SetupCoordinator
import com.agentdeck.app.domain.runtime.AgentRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual DI for skeleton. Can be replaced with Hilt later.
 *
 * [init] 只记录 Context；所有对象均为首次访问时才构建（SYNCHRONIZED lazy，
 * 线程安全），重资源（Room、runtime 探测、/proc 清理）因此不会在冷启动主线程上执行。
 * 冷启动后由 [warmUp] 在后台线程预热整个对象图。
 */
object ServiceLocator {
    @Volatile private var initialized = false
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    private val db: AppDatabase by lazy { AppDatabase.get(app) }
    // EmbeddedProotRuntime 构造时会 reapStaleProcesses() 扫 /proc，惰性构造以避开主线程。
    private val embeddedRuntime: EmbeddedProotRuntime by lazy { EmbeddedProotRuntime(app) }

    val runtime: AgentRuntime by lazy { embeddedRuntime }
    val profiles: ProfileRepository by lazy { ProfileRepository(db) }
    val credentials: ProviderCredentialVault by lazy { AndroidProviderCredentialVault(app) }
    val extensionCredentials: ExtensionCredentialVault by lazy { AndroidExtensionCredentialVault(app) }
    internal val extensions: ExtensionRepository by lazy {
        ExtensionRepository(
            db = db,
            policy = ExtensionPolicy(BuildConfig.EXTENSION_MAX_LEVEL),
            credentials = extensionCredentials,
            paths = EmbeddedRuntimePaths(app),
            secureMcpClient = secureMcpHttpClient(app),
        )
    }
    val modelDiscovery: ProviderModelDiscovery by lazy { OkHttpProviderModelDiscovery() }
    val cards: CardRepository by lazy { CardRepository(db) }
    internal val conversationBackup: ConversationBackupRepository by lazy {
        ConversationBackupRepository(app, cards, extensions)
    }
    val seeder: InitialDataSeeder by lazy { InitialDataSeeder(db, profiles, cards) }
    val recipes: RecipeRepository by lazy { RecipeRepository(app.assets) }
    val onboarding: OnboardingRepository by lazy { OnboardingRepository(app) }
    val experienceSettings: ExperienceSettingsRepository by lazy { ExperienceSettingsRepository(app) }
    val workspaceGrants: WorkspaceGrantRepository by lazy { WorkspaceGrantRepository(app) }
    val hostApprovalGateway: MutableHostApprovalGateway by lazy { MutableHostApprovalGateway() }
    val hostTools: HostToolBroker by lazy {
        DefaultHostToolBroker(
            policyProvider = {
                DefaultHostToolBroker.policyFrom(
                    experienceLevel = experienceSettings.level.value,
                    workspaceEnabled = experienceSettings.hostWorkspaceEnabled.value,
                    hasGrant = workspaceGrants.primaryGrant() != null,
                    maxHostLevel = BuildConfig.HOST_MAX_LEVEL,
                    intentEnabled = experienceSettings.labIntentEnabled.value,
                    uiAutomationEnabled = experienceSettings.labUiEnabled.value,
                    privilegedEnabled = experienceSettings.labPrivEnabled.value,
                    labRiskAccepted = experienceSettings.labRiskAccepted.value,
                )
            },
            workspace = {
                val grant = workspaceGrants.primaryGrant() ?: return@DefaultHostToolBroker null
                val uri = runCatching { Uri.parse(grant.treeUri) }.getOrNull() ?: return@DefaultHostToolBroker null
                SafWorkspaceDocumentStore(app, uri)
            },
            mirrorRoot = {
                val paths = com.agentdeck.app.data.runtime.EmbeddedRuntimePaths(app)
                paths.ensureHostLayout()
                val mirror = java.io.File(paths.projectsHome, "host-mirror")
                mirror.mkdirs()
                mirror
            },
            approval = hostApprovalGateway,
            intentExecutor = com.agentdeck.app.data.host.LabHostLoader.intentExecutor(app),
            uiExecutor = { com.agentdeck.app.data.host.LabHostLoader.uiExecutor() },
            privExecutor = com.agentdeck.app.data.host.LabHostLoader.privExecutor(),
        )
    }
    val hostToolRelay: HostToolRelay by lazy {
        HostToolRelay(app, hostTools, appScope)
    }
    val envProbe: EnvironmentScanner by lazy { EmbeddedEnvironmentProbe(embeddedRuntime) }
    val installer: RecipeInstallation by lazy { EmbeddedRuntimeInstaller(app) }
    internal val runtimeInventory: RuntimeInventory by lazy { RuntimeInventory(app, installer = installer) }
    val setup: SetupCoordinator by lazy {
        SetupCoordinator(
            scanner = envProbe,
            installer = installer,
            scope = appScope,
            managedProviderReady = {
                profiles.getProfiles().any { profile ->
                    val verified = profile.connectionStatus == ProviderConnectionStatus.READY ||
                        profile.connectionStatus == ProviderConnectionStatus.DISCOVERY_UNSUPPORTED
                    verified && profile.credentialRef?.let(credentials::contains) == true
                }
            },
            onReport = onboarding::record,
            previousCanLaunchSessions = onboarding.lastCanLaunchSessions(),
            previousFullyReady = onboarding.lastFullyReady(),
        )
    }
    val conversationLinks: ConversationLinkRepository by lazy { ConversationLinkRepository(app) }
    val codexProfile: CodexProfileRepository by lazy {
        CodexProfileRepository(
            context = app,
            allowUnmanagedMcp = BuildConfig.EXTENSION_LAB,
        )
    }
    val codexBridge: CodexBridgeLauncher by lazy { CodexBridgeLauncher(runtime, codexProfile) }
    val chatAttachments: ChatAttachmentStore by lazy { ChatAttachmentStore(app, runtime) }

    val appContext: Context
        get() {
            check(initialized) { "ServiceLocator 尚未初始化" }
            return app
        }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = context.applicationContext
            initialized = true
        }
    }

    /**
     * 冷启动后在后台线程预热重资源对象图（Room、runtime status、reapStaleProcesses），
     * 避免首个访问者（通常是主线程上的 ViewModel）承担初始化成本。不触发环境扫描。
     */
    fun warmUp() {
        check(initialized) { "ServiceLocator 尚未初始化" }
        setup
        credentials
        extensions
    }
}
