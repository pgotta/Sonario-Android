package ai.sonario.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Lets the user choose among Sonario's mobile-oriented local models, download
 * additional models, and see which model is currently active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: SummaryViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()
    val download = ui.download
    LaunchedEffect(Unit) { vm.refreshModels() }

    Scaffold(
        containerColor = SonarioColors.Deep,
        topBar = {
            TopAppBar(
                title = { Text("Models", color = SonarioColors.Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            tint = SonarioColors.InkSoft,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SonarioColors.Deep,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(scroll)
                .padding(16.dp),
        ) {
            Text(
                "Pick the on-device model that matches what you care about most. " +
                    "Only one model is loaded into memory at a time.",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))

            ui.models.forEach { model ->
                val selected = model.fileName == ui.model.fileName && model.present
                val downloadingThis =
                    download.active && download.model?.fileName == model.fileName

                Surface(
                    color = if (selected) SonarioColors.Panel2 else SonarioColors.Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    onClick = { if (model.present) vm.setModel(model) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (model.present) {
                                Icon(
                                    if (selected) Icons.Filled.CheckCircle
                                    else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (selected) SonarioColors.Green
                                    else SonarioColors.Muted,
                                )
                                Spacer(Modifier.width(12.dp))
                            }

                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.label,
                                    color = SonarioColors.Ink,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    model.note,
                                    color = SonarioColors.Muted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (model.present) {
                                        "Downloaded • ${formatModelSize(model.sizeMb)}"
                                    } else {
                                        "Not downloaded • about ${formatModelSize(model.sizeMb)}"
                                    },
                                    color = if (model.present) SonarioColors.Green
                                    else SonarioColors.Muted,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }

                            if (!model.present && !downloadingThis) {
                                Button(
                                    onClick = { vm.downloadModel(model) },
                                    enabled = !download.active,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SonarioColors.Green,
                                        contentColor = SonarioColors.Abyss,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Get")
                                }
                            }
                        }

                        if (downloadingThis) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { download.fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = SonarioColors.Green,
                                trackColor = SonarioColors.Panel,
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${download.bytes / 1_000_000} / " +
                                        "${download.total / 1_000_000} MB",
                                    color = SonarioColors.InkSoft,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.cancelDownload() }) {
                                    Text("Cancel", color = SonarioColors.Muted)
                                }
                            }
                        }

                        if (download.model?.fileName == model.fileName &&
                            download.error != null
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                download.error!!,
                                color = SonarioColors.InkSoft,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Downloaded and partial model files stay inside Sonario's private " +
                    "app storage. Android deletes them when Sonario is uninstalled.",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatModelSize(sizeMb: Int): String =
    if (sizeMb >= 1000) String.format("%.1f GB", sizeMb / 1000.0)
    else "$sizeMb MB"
