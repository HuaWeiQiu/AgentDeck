package com.agentdeck.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.agentdeck.app.ui.navigation.AgentDeckRoot
import com.agentdeck.app.ui.theme.AgentDeckTheme

class MainActivity : ComponentActivity() {
    // cardId + arrival timestamp, so tapping the same card's notification twice
    // still re-triggers navigation (the LaunchedEffect key would not change otherwise).
    private val deepLink = mutableStateOf<Pair<String, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = intent.deepLink()
        enableEdgeToEdge()
        setContent {
            val target by deepLink
            AgentDeckTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentDeckRoot(deepLink = target)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.deepLink()?.let { deepLink.value = it }
    }

    private fun Intent.deepLink(): Pair<String, Long>? =
        getStringExtra(EXTRA_DEEP_LINK_CARD_ID)?.let { it to System.currentTimeMillis() }

    companion object {
        /** Intent extra carrying the card to open, set by chat event notifications. */
        const val EXTRA_DEEP_LINK_CARD_ID = "agentdeck_card_id"
    }
}
