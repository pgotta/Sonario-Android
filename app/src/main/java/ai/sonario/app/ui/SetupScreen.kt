package ai.sonario.app.ui

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
import ai.sonario.app.llm.ModelInfo

/**
 * First-run setup. Sonario needs one on-device model before it can summarize.
 * This screen lets the user pick which model to download and shows live
 * progress. It replaces the old manual adb-push step entirely. Once any model
 * is present, the app routes straight to the summarizer on launch.
 */
@Composable
fun SetupScreen(vm: SummaryViewModel, onUseCloud: () -> Unit = {}) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()
    val dl = ui.download

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
                Text("Sonar", style = MaterialTheme.typography.headlineLarge,
                    color = SonarioColors.Ink)
                Text("io", style = MaterialTheme.typography.headlineLarge,
                    color = SonarioColors.Green)
            }
            Text("One quick setup step",
                style = MaterialTheme.typography.titleLarge,
                color = SonarioColors.InkSoft,
                modifier = Modifier.padding(top = 4.dp))
            Text(
                "Sonario runs the AI on your phone, so it needs a model file. " +
                "Pick one to download now. You only do this once, and you can " +
                "switch later in Models. Use Wi-Fi: these are 1 to 2 GB.",
                style = MaterialTheme.typography.bodyMedium,
                color = SonarioColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
            )

            ui.models.forEach { m ->
                ModelChoice(
                    model = m,
                    downloadingThis = dl.active && dl.model?.fileName == m.fileName,
                    fraction = if (dl.model?.fileName == m.fileName) dl.fraction else 0f,
                    bytes = if (dl.model?.fileName == m.fileName) dl.bytes else 0L,
                    total = if (dl.model?.fileName == m.fileName) dl.total else 0L,
                    anyDownloadActive = dl.active,
                    errorForThis = if (dl.model?.fileName == m.fileName) dl.error else null,
                    onDownload = { vm.downloadModel(m) },
                    onCancel = { vm.cancelDownload() },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "On-device keeps everything on your phone but is slow. Prefer speed? " +
                "You can use the Groq cloud instead (needs a free API key).",
                style = MaterialTheme.typography.labelLarge,
                color = SonarioColors.Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onUseCloud,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SonarioColors.Green),
            ) { Text("Use Groq cloud instead") }
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
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, color = SonarioColors.Ink,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge)
                    Text("~${model.sizeMb} MB", color = SonarioColors.Green,
                        style = MaterialTheme.typography.labelLarge)
                }
                if (!downloadingThis) {
                    Button(
                        onClick = onDownload,
                        enabled = !anyDownloadActive,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SonarioColors.Green,
                            contentColor = SonarioColors.Abyss),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Get")
                    }
                }
            }
            Text(model.note, color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp))

            if (downloadingThis) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = SonarioColors.Green,
                    trackColor = SonarioColors.Panel2,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mb = bytes / 1_000_000
                    val totalMb = total / 1_000_000
                    Text("$mb / $totalMb MB", color = SonarioColors.InkSoft,
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = SonarioColors.Muted)
                    }
                }
            }

            errorForThis?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = SonarioColors.InkSoft,
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onDownload,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SonarioColors.Green),
                ) { Text("Retry") }
            }
        }
    }
}
