package com.agentdeck.app.di

import android.content.Context
import com.agentdeck.app.data.db.AppDatabase
import com.agentdeck.app.data.repo.CardRepository
import com.agentdeck.app.data.repo.ProfileRepository
import com.agentdeck.app.data.repo.RecipeRepository
import com.agentdeck.app.data.secure.SecureKeyStore
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.data.termux.TermuxGateway
import com.agentdeck.app.domain.env.EnvironmentProbe
import com.agentdeck.app.domain.install.RecipeInstaller
import com.agentdeck.app.domain.launch.LaunchInteractor

/**
 * Minimal manual DI for skeleton. Can be replaced with Hilt later.
 */
object ServiceLocator {
    @Volatile private var initialized = false

    lateinit var termux: TermuxGateway
        private set
    lateinit var profiles: ProfileRepository
        private set
    lateinit var cards: CardRepository
        private set
    lateinit var recipes: RecipeRepository
        private set
    lateinit var envProbe: EnvironmentProbe
        private set
    lateinit var launcher: LaunchInteractor
        private set
    lateinit var installer: RecipeInstaller
        private set
    lateinit var keyStore: SecureKeyStore
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val db = AppDatabase.get(app)
            keyStore = SecureKeyStore(app)
            termux = AndroidTermuxGateway(app)
            profiles = ProfileRepository(db, keyStore)
            cards = CardRepository(db)
            recipes = RecipeRepository(app)
            envProbe = EnvironmentProbe(termux)
            launcher = LaunchInteractor(cards, profiles, recipes, termux)
            installer = RecipeInstaller(termux, recipes)
            initialized = true
        }
    }
}
