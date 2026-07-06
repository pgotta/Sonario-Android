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
 * Models screen, post-setup. Lets the user pick the active model, download
 * additional models in-app (same downloader as first-run setup), and see which
 * are present. One model loads at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: SummaryViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()
    val dl = ui.download
    LaunchedEffect(Unit) { vm.refreshModels() }

    Scaffold(
        containerColor = SonarioColors.Deep,
        topBar = {
            TopAppBar(
                title = { Text("Models", color = SonarioColors.Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                            tint = SonarioColors.InkSoft)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SonarioColors.Deep),
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(scroll).padding(16.dp)
        ) {
            Text("Pick the on-device model. One model stays loaded at a time. " +
                "Download more here any time.",
                color = SonarioColors.Muted,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))

            ui.models.forEach { m ->
                val selected = m.fileName == ui.model.fileName && m.present
                val downloadingThis = dl.active && dl.model?.fileName == m.fileName
                Surface(
                    color = if (selected) SonarioColors.Panel2 else SonarioColors.Panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    onClick = { if (m.present) vm.setModel(m) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (m.present) {
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
                                Text(m.label, color = SonarioColors.Ink,
                                    fontWeight = FontWeight.SemiBold)
                                Text(m.note, color = SonarioColors.Muted,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (m.present) "On device, ${m.sizeMb} MB"
                                    else "Not downloaded, ~${m.sizeMb} MB",
                                    color = if (m.present) SonarioColors.Green
                                            else SonarioColors.Muted,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            if (!m.present && !downloadingThis) {
                                Button(
                                    onClick = { vm.downloadModel(m) },
                                    enabled = !dl.active,
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

                        if (downloadingThis) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { dl.fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = SonarioColors.Green,
                                trackColor = SonarioColors.Panel,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${dl.bytes / 1_000_000} / ${dl.total / 1_000_000} MB",
                                    color = SonarioColors.InkSoft,
                                    style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.cancelDownload() }) {
                                    Text("Cancel", color = SonarioColors.Muted)
                                }
                            }
                        }

                        if (dl.model?.fileName == m.fileName && dl.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(dl.error!!, color = SonarioColors.InkSoft,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
