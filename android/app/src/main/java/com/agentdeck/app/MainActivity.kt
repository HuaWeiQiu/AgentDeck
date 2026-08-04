package com.agentdeck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.agentdeck.app.data.termux.AndroidTermuxGateway
import com.agentdeck.app.ui.navigation.AgentDeckRoot
import com.agentdeck.app.ui.theme.AgentDeckTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result observed via EnvironmentProbe on next scan */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Best-effort: request Termux RUN_COMMAND permission when present.
        runCatching {
            permissionLauncher.launch(AndroidTermuxGateway.RUN_COMMAND_PERMISSION)
        }

        setContent {
            AgentDeckTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentDeckRoot()
                }
            }
        }
    }
}
