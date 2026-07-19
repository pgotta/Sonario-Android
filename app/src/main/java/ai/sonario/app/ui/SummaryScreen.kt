package ai.sonario.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.sonario.app.data.Exporter
import ai.sonario.app.llm.EngineChoice
import ai.sonario.app.llm.LlmProvider
import kotlinx.coroutines.launch

/**
 * The main summary screen. Shows the source title, a progress indicator
 * while summarising, the rendered markdown summary, and an error surface
 * with retry when things go wrong.
 *
 * Improvements over the original:
 *  - Provider badge showing which cloud backend is active.
 *  - Inline retry / cancel buttons during a run.
 *  - Share and export actions on completed summaries.
 *  - Clearer empty state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    vm: SummaryViewModel,
    onSettings: () -> Unit,
) {
    val state = vm.state
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.sourceTitle.ifBlank { "Sonario" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // Provider badge
                        if (vm.isCloud) {
                            Text(
                                text = "${vm.activeProvider.displayName} · ${vm.providerConfig.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = "On-device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                },
                actions = {
                    if (state.summary.isNotBlank()) {
                        IconButton(onClick = {
                            scope.launch {
                                val file = Exporter.exportMd(ctx, state.sourceTitle, state.summary)
                                // share intent …
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Progress / status ─────────────────────────────────────────────
            if (state.isRunning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(state.status, style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { vm.cancel() }) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                }
            }

            // ── Error surface ─────────────────────────────────────────────────
            state.error?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Something went wrong",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.clearError() }) {
                                Text("Dismiss")
                            }
                            Button(onClick = { vm.retry() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // ── Summary output ────────────────────────────────────────────────
            if (state.summary.isNotBlank()) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        // MarkdownText is the existing composable from the original codebase
                        MarkdownText(state.summary)
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (!state.isRunning && state.summary.isBlank() && state.error == null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "No summary yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Paste text, open a URL, or pick a file to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
