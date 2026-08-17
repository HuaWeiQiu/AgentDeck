package com.agentdeck.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.agentdeck.app.data.voice.VoskDictationEngine
import com.agentdeck.app.ui.navigation.AgentDeckRoot
import com.agentdeck.app.ui.theme.AgentDeckTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // cardId + arrival timestamp, so tapping the same card's notification twice
    // still re-triggers navigation (the LaunchedEffect key would not change otherwise).
    private val deepLink = mutableStateOf<Pair<String, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLink.value = intent.deepLink()
        enableEdgeToEdge()
        // Keep compositor on GPU; avoid software fallback jank on high-DPI panels.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        setContent {
            val target by deepLink
            AgentDeckTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentDeckRoot(deepLink = target)
                }
            }
        }
        maybeRunVoiceSelfTest(intent)
    }

    override fun onStop() {
        super.onStop()
        // Keep agents warm while the process lives so re-opening chat is instant.
        // Reclaim only under system memory pressure (see AgentDeckApp.onTrimMemory).
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.deepLink()?.let { deepLink.value = it }
        maybeRunVoiceSelfTest(intent)
    }

    private fun maybeRunVoiceSelfTest(intent: Intent?) {
        if (intent?.action != ACTION_VOICE_SELFTEST) return
        // Only the debuggable-id beta package is intended for this path.
        if (!packageName.endsWith(".debug")) return
        val path = intent.getStringExtra(EXTRA_VOICE_WAV_PATH)
            ?: File(getExternalFilesDir(null), "agentdeck_tts.wav").absolutePath
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VoskDictationEngine(this@MainActivity).transcribeWav(File(path))
            }
            val message = result.fold(
                onSuccess = { text ->
                    if (text.isBlank()) "Vosk 自测完成，但结果为空" else "Vosk 自测: $text"
                },
                onFailure = { error -> "Vosk 自测失败: ${error.message?.take(100)}" },
            )
            Log.i(VOICE_TEST_TAG, message)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun Intent.deepLink(): Pair<String, Long>? =
        getStringExtra(EXTRA_DEEP_LINK_CARD_ID)?.let { it to System.currentTimeMillis() }

    companion object {
        /** Intent extra carrying the card to open, set by chat event notifications. */
        const val EXTRA_DEEP_LINK_CARD_ID = "agentdeck_card_id"

        const val ACTION_VOICE_SELFTEST = "com.agentdeck.app.ACTION_VOICE_SELFTEST"
        const val EXTRA_VOICE_WAV_PATH = "wav_path"
        private const val VOICE_TEST_TAG = "AgentDeckVoiceTest"
    }
}
