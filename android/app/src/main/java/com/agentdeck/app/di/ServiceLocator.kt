package com.agentdeck.app.di

import android.content.Context
import com.agentdeck.app.data.chat.CodexBridgeLauncher
import com.agentdeck.app.data.chat.ConversationLinkRepository
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.provider.OkHttpProviderModelDiscovery
import com.agentdeck.app.data.provider.ProviderModelDiscovery
import com.agentdeck.app.data.provider.ExistingCodexProviderImporter
import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ExperienceSettingsRepository
import com.agentdeck.app.data.repo.InitialDataSeeder
import com.agentdeck.app.data.repo.OnboardingRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.repo.RecipeRepository
import com.agentdeck.app.data.repo.RuntimeSettingsRepository
import com.agentdeck.app.data.runtime.EmbeddedProotRuntime
import com.agentdeck.app.data.runtime.EmbeddedRuntimeInstaller
import com.agentdeck.app.data.runtime.RoutingRecipeInstallation
import com.agentdeck.app.data.runtime.RuntimeRouter
import com.agentdeck.app.data.runtime.TermuxRuntime
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.data.secure.AndroidProviderCredentialVault
import com.agentdeck.app.data.secure.ProviderCredentialVault
import com.agentdeck.app.domain.env.EmbeddedEnvironmentProbe
import com.agentdeck.app.domain.env.EnvironmentProbe
import com.agentdeck.app.domain.env.EnvironmentScanner
import com.agentdeck.app.domain.env.RoutingEnvironmentScanner
import com.agentdeck.app.domain.install.RecipeInstallation
import com.agentdeck.app.domain.install.RecipeInstaller
import com.agentdeck.app.domain.launch.LaunchInteractor
import com.agentdeck.app.domain.setup.SetupCoordinator
import com.agentdeck.app.domain.runtime.AgentRuntime
import com.agentdeck.app.domain.runtime.RuntimeSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual DI for skeleton. Can be replaced with Hilt later.
 */
object ServiceLocator {
    @Volatile private var initialized = false
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var runtime: AgentRuntime
        private set
    lateinit var profiles: ProfileRepository
        private set
    lateinit var credentials: ProviderCredentialVault
        private set
    lateinit var modelDiscovery: ProviderModelDiscovery
        private set
    lateinit var existingCodexProviderImporter: ExistingCodexProviderImporter
        private set
    lateinit var cards: CardRepository
        private set
    lateinit var seeder: InitialDataSeeder
        private set
    lateinit var recipes: RecipeRepository
        private set
    lateinit var onboarding: OnboardingRepository
        private set
    lateinit var experienceSettings: ExperienceSettingsRepository
        private set
    lateinit var runtimeSettings: RuntimeSettingsRepository
        private set
    lateinit var envProbe: EnvironmentScanner
        private set
    lateinit var launcher: LaunchInteractor
        private set
    lateinit var installer: RecipeInstallation
        private set
    lateinit var setup: SetupCoordinator
        private set
    lateinit var conversationLinks: ConversationLinkRepository
        private set
    lateinit var codexBridge: CodexBridgeLauncher
        private set
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val db = AppDatabase.get(app)
            val termuxGateway = AndroidTermuxGateway(app)
            profiles = ProfileRepository(db)
            credentials = AndroidProviderCredentialVault(app)
            modelDiscovery = OkHttpProviderModelDiscovery()
            cards = CardRepository(db)
            seeder = InitialDataSeeder(db, profiles, cards)
            recipes = RecipeRepository(app.assets)
            onboarding = OnboardingRepository(app)
            experienceSettings = ExperienceSettingsRepository(app)
            val termuxRuntime = TermuxRuntime(termuxGateway)
            val embeddedRuntime = EmbeddedProotRuntime(app)
            existingCodexProviderImporter = ExistingCodexProviderImporter(
                termuxRuntime = termuxRuntime,
                profiles = profiles,
                cards = cards,
                credentials = credentials,
                discovery = modelDiscovery,
            )
            val initialRuntime = if (
                !onboarding.shouldOpenDoctor() && termuxRuntime.status().ready
            ) {
                RuntimeSelection.TERMUX_COMPATIBILITY
            } else {
                RuntimeSelection.EMBEDDED
            }
            runtimeSettings = RuntimeSettingsRepository(app, initialRuntime)
            runtime = RuntimeRouter(runtimeSettings.selection, embeddedRuntime, termuxRuntime)
            envProbe = RoutingEnvironmentScanner(
                selection = runtimeSettings.selection,
                embedded = EmbeddedEnvironmentProbe(embeddedRuntime),
                termux = EnvironmentProbe(termuxRuntime),
            )
            launcher = LaunchInteractor(cards, profiles, recipes, runtime)
            installer = RoutingRecipeInstallation(
                selection = runtimeSettings.selection,
                embedded = EmbeddedRuntimeInstaller(app),
                termux = RecipeInstaller(termuxRuntime, recipes),
            )
            conversationLinks = ConversationLinkRepository(app)
            codexBridge = CodexBridgeLauncher(runtime)
            setup = SetupCoordinator(
                scanner = envProbe,
                installer = installer,
                runtime = runtime,
                scope = appScope,
                onReport = onboarding::record,
            )
            initialized = true
        }
    }
}
