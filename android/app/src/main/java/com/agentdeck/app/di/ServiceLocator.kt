package com.agentdeck.app.di

import android.content.Context
import com.agentdeck.app.data.chat.CodexBridgeLauncher
import com.agentdeck.app.data.chat.ConversationLinkRepository
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.InitialDataSeeder
import com.agentdeck.app.data.repo.OnboardingRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.repo.RecipeRepository
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.env.EnvironmentProbe
import com.agentdeck.app.domain.install.RecipeInstaller
import com.agentdeck.app.domain.launch.LaunchInteractor
import com.agentdeck.app.domain.setup.SetupCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual DI for skeleton. Can be replaced with Hilt later.
 */
object ServiceLocator {
    @Volatile private var initialized = false
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var termux: TermuxGateway
        private set
    lateinit var profiles: ProfileRepository
        private set
    lateinit var cards: CardRepository
        private set
    lateinit var seeder: InitialDataSeeder
        private set
    lateinit var recipes: RecipeRepository
        private set
    lateinit var onboarding: OnboardingRepository
        private set
    lateinit var envProbe: EnvironmentProbe
        private set
    lateinit var launcher: LaunchInteractor
        private set
    lateinit var installer: RecipeInstaller
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
            termux = AndroidTermuxGateway(app)
            profiles = ProfileRepository(db)
            cards = CardRepository(db)
            seeder = InitialDataSeeder(db, profiles, cards)
            recipes = RecipeRepository(app.assets)
            onboarding = OnboardingRepository(app)
            envProbe = EnvironmentProbe(termux)
            launcher = LaunchInteractor(cards, profiles, recipes, termux)
            installer = RecipeInstaller(termux, recipes)
            conversationLinks = ConversationLinkRepository(app)
            codexBridge = CodexBridgeLauncher(termux)
            setup = SetupCoordinator(
                scanner = envProbe,
                installer = installer,
                termux = termux,
                scope = appScope,
                onReport = onboarding::record,
            )
            initialized = true
        }
    }
}
