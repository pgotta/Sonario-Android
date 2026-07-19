package ai.sonario.app.ui

import ai.sonario.app.llm.ModelInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-run setup. The user can download one of three mobile-oriented local
 * models or skip the large download and use Groq cloud.
 */
@Composable
fun SetupScreen(vm: SummaryViewModel, onUseCloud: () -> Unit = {}) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()
    val download = ui.download

    Surface(color = SonarioColors.Deep, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Row {
                Text(
                    "Sonar",
                    style = MaterialTheme.typography.headlineLarge,
                    color = SonarioColors.Ink,
                )
                Text(
                    "io",
                    style = MaterialTheme.typography.headlineLarge,
                    color = SonarioColors.Green,
                )
            }
            Text(
                "Choose how local AI should feel",
                style = MaterialTheme.typography.titleLarge,
                color = SonarioColors.InkSoft,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Pick the strongest, the most writing-focused, or the fastest local " +
                    "model. Use Wi-Fi: downloads range from about 1.6 to 4.3 GB.",
                style = MaterialTheme.typography.bodyMedium,
                color = SonarioColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
            )

            ui.models.forEach { model ->
                ModelChoice(
                    model = model,
                    downloadingThis =
                        download.active && download.model?.fileName == model.fileName,
                    fraction = if (download.model?.fileName == model.fileName) {
                        download.fraction
                    } else {
                        0f
                    },
                    bytes = if (download.model?.fileName == model.fileName) {
                        download.bytes
                    } else {
                        0L
                    },
                    total = if (download.model?.fileName == model.fileName) {
                        download.total
                    } else {
                        0L
                    },
                    anyDownloadActive = download.active,
                    errorForThis = if (download.model?.fileName == model.fileName) {
                        download.error
                    } else {
                        null
                    },
                    onDownload = { vm.downloadModel(model) },
                    onCancel = { vm.cancelDownload() },
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Models are stored only in Sonario's private app storage. Uninstalling " +
                    "Sonario removes completed and partial model downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = SonarioColors.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Local mode is private but still slower than cloud inference. Prefer " +
                    "speed and stronger results? Use Groq cloud instead.",
                style = MaterialTheme.typography.labelLarge,
                color = SonarioColors.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onUseCloud,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SonarioColors.Green,
                ),
            ) {
                Text("Use Groq cloud instead")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelChoice(
    model: ModelInfo,
    downloadingThis: Boolean,
    fraction: Float,
    bytes: Long,
    total: Long,
    anyDownloadActive: Boolean,
    errorForThis: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.label,
                        color = SonarioColors.Ink,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "About ${formatSetupModelSize(model.sizeMb)}",
                        color = SonarioColors.Green,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (!downloadingThis) {
                    Button(
                        onClick = onDownload,
                        enabled = !anyDownloadActive,
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
            Text(
                model.note,
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (downloadingThis) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = SonarioColors.Green,
                    trackColor = SonarioColors.Panel2,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${bytes / 1_000_000} / ${total / 1_000_000} MB",
                        color = SonarioColors.InkSoft,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = SonarioColors.Muted)
                    }
                }
            }

            errorForThis?.let { error ->
                Spacer(Modifier.height(10.dp))
                Text(
                    error,
                    color = SonarioColors.InkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onDownload,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SonarioColors.Green,
                    ),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

private fun formatSetupModelSize(sizeMb: Int): String =
    if (sizeMb >= 1000) String.format("%.1f GB", sizeMb / 1000.0)
    else "$sizeMb MB"
