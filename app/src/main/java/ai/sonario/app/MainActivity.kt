package ai.sonario.app

import android.content.Intent
import android.os.Build
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
        maybeRequestNotifications()

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

                // Export: watch for a requested export, open the system file
                // picker (ACTION_CREATE_DOCUMENT), then write the file.
                val pending by vm.pendingExport.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current
                val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                        "*/*"
                    )
                ) { uri ->
                    val p = pending
                    if (uri != null && p != null) {
                        runCatching {
                            ai.sonario.app.data.Exporter.write(
                                context, uri, p.format, p.title, p.body)
                        }
                    }
                    vm.clearPendingExport()
                }
                LaunchedEffect(pending) {
                    val p = pending ?: return@LaunchedEffect
                    exportLauncher.launch(
                        ai.sonario.app.data.Exporter.suggestedName(p.title, p.format))
                }

                // Local file picker: open the system browser and hand the Uri back.
                val wantFile by vm.pickFile.collectAsState()
                val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) vm.onFilePicked(uri) else vm.clearPickFile()
                }
                LaunchedEffect(wantFile) {
                    if (wantFile) {
                        // Any document type; the extractor sniffs by extension.
                        openLauncher.launch(arrayOf(
                            "text/*", "application/pdf", "application/epub+zip",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "*/*",
                        ))
                    }
                }

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
                            vm.setEngine(EngineChoice.CLOUD)
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

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
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
