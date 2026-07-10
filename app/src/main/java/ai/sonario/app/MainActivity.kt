package ai.sonario.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.ui.CrashScreen
import ai.sonario.app.ui.ModelsScreen
import ai.sonario.app.ui.SettingsScreen
import ai.sonario.app.ui.SetupScreen
import ai.sonario.app.ui.SonarioTheme
import ai.sonario.app.ui.SummaryScreen
import ai.sonario.app.ui.SummaryViewModel

/** Top-level destinations. SUMMARY is the default; SETUP is derived from state. */
private enum class Screen { SUMMARY, MODELS, SETTINGS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = readSharedText(intent)
        val lastCrash = CrashReporter.consumeLastCrash(this)

        setContent {
            SonarioTheme {
                // If the previous run crashed, show the report instead of
                // relaunching into a possible crash loop.
                var crashText by remember { mutableStateOf(lastCrash) }
                crashText?.let { report ->
                    CrashScreen(text = report, onDismiss = { crashText = null })
                    return@SonarioTheme
                }

                val vm: SummaryViewModel = viewModel()
                val uiState by vm.ui.collectAsState()
                var screen by remember { mutableStateOf(Screen.SUMMARY) }

                // Pre-fill from a shared link (e.g. Share from YouTube).
                LaunchedEffect(shared) {
                    if (!shared.isNullOrBlank()) vm.onInput(shared)
                }

                // First run: on-device engine with no model and no cloud key means
                // there is nothing to summarize with -> show setup. Explicit
                // navigation (Models/Settings) always wins over this.
                val needsSetup = !uiState.hasAnyModel &&
                    uiState.engineChoice == EngineChoice.ON_DEVICE &&
                    !uiState.groqKeySet

                when {
                    screen == Screen.MODELS ->
                        ModelsScreen(vm, onBack = { screen = Screen.SUMMARY })
                    screen == Screen.SETTINGS ->
                        SettingsScreen(vm, onBack = { screen = Screen.SUMMARY })
                    needsSetup ->
                        SetupScreen(vm, onUseCloud = {
                            // Switching to the cloud engine ends the setup state;
                            // land on Settings so the user can paste their key.
                            vm.setEngine(EngineChoice.GROQ)
                            screen = Screen.SETTINGS
                        })
                    else -> SummaryScreen(
                        vm,
                        onOpenModels = { screen = Screen.MODELS },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                }
            }
        }
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }
}
