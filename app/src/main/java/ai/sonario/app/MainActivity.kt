package ai.sonario.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.sonario.app.ui.ModelsScreen
import ai.sonario.app.ui.SetupScreen
import ai.sonario.app.ui.SonarioTheme
import ai.sonario.app.ui.SummaryScreen
import ai.sonario.app.ui.SummaryViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = readSharedText(intent)

        setContent {
            SonarioTheme {
                val vm: SummaryViewModel = viewModel()
                val uiState by vm.ui.collectAsState()
                var screen by remember { mutableStateOf("summary") }

                // Pre-fill from a shared link (e.g. Share from YouTube).
                androidx.compose.runtime.LaunchedEffect(shared) {
                    if (!shared.isNullOrBlank()) vm.onInput(shared)
                }

                when {
                    // First run: no model on the device yet -> setup/picker.
                    !uiState.hasAnyModel -> SetupScreen(vm)
                    screen == "models" -> ModelsScreen(vm, onBack = { screen = "summary" })
                    else -> SummaryScreen(vm, onOpenModels = { screen = "models" })
                }
            }
        }
    }

    private fun readSharedText(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND &&
            intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }
}
