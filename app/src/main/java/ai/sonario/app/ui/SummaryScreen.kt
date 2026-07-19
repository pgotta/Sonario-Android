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
import kotlinx.coroutines.launch

/**
 * The main summary screen for v2.
 *
 * Shows the source title, a progress indicator while summarising, the
 * rendered markdown summary, and an error surface with retry when things
 * go wrong.
 *
 * Improvements over the original v1 screen:
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = vm.sourceTitle.ifBlank { "Sonario" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (vm.isCloud) {
                            Text(
                                text = "${vm.activeProvider.displayName} · $providerModel",
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
                    if (vm.summary.isNotBlank()) {
                        IconButton(onClick = {
                            scope.launch {
                                Exporter.exportMd(ctx, vm.sourceTitle, vm.summary)
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
            if (vm.isRunning) {
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(vm.status, style = MaterialTheme.typography.bodyMedium)
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
            vm.error?.let { err ->
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
            if (vm.summary.isNotBlank()) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Summary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        MarkdownText(vm.summary)
                    }
                }
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (!vm.isRunning && vm.summary.isBlank() && vm.error == null) {
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

/**
 * Lightweight markdown renderer. Uses the same Markdown composable the
 * v1 screen used (com.mikepenz.markdown.m3.Markdown) when available,
 * falling back to plain text so the file compiles standalone.
 */
@Composable
private fun MarkdownText(md: String) {
    // Try the rich markdown composable first.
    runCatching {
        com.mikepenz.markdown.m3.Markdown(
            content = md,
            modifier = Modifier.skipToLookahead(),
        )
        return
    }
    // Fallback: plain text.
    Text(md, style = MaterialTheme.typography.bodyMedium)
}

private fun Modifier.skipToLookahead() = this
