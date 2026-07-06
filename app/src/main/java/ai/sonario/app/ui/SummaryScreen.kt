package ai.sonario.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel, onOpenModels: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = SonarioColors.Deep,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DepthMark(markSize = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Sonar", style = MaterialTheme.typography.titleLarge,
                            color = SonarioColors.Ink)
                        Text("io", style = MaterialTheme.typography.titleLarge,
                            color = SonarioColors.Green)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.Tune, contentDescription = "Models",
                            tint = SonarioColors.InkSoft)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SonarioColors.Deep),
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (ui.result == null) {
                Spacer(Modifier.height(8.dp))
                DepthMark()
                Spacer(Modifier.height(10.dp))
                Text("Summarize anything, on device",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonarioColors.Ink, textAlign = TextAlign.Center)
                Text("Paste a YouTube link, an article URL, or text. Nothing leaves your phone except the fetch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonarioColors.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))

                InputCard(ui, vm)
            }

            ui.error?.let { ErrorCard(it) }

            if (ui.busy) ProgressCard(ui)

            ui.result?.let { res -> ResultArea(res, ui.view, vm) }

            Spacer(Modifier.height(40.dp))
        }
    }

        // Live system meter, pinned bottom-left (like the desktop resource readout).
        SysMeter(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputCard(ui: UiState, vm: SummaryViewModel) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = ui.input,
                onValueChange = vm::onInput,
                placeholder = { Text("YouTube link, article URL, or pasted text") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                minLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SonarioColors.Panel2,
                    unfocusedContainerColor = SonarioColors.Panel2,
                    focusedBorderColor = SonarioColors.Green,
                    unfocusedBorderColor = SonarioColors.RuleSoft,
                    cursorColor = SonarioColors.Green,
                    focusedTextColor = SonarioColors.Ink,
                    unfocusedTextColor = SonarioColors.Ink,
                ),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(ui.model.label, maxLines = 1) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = SonarioColors.Muted),
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = vm::summarize,
                    enabled = !ui.busy && ui.input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonarioColors.Green,
                        contentColor = SonarioColors.Abyss),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Summarize", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(ui: UiState) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val label = when (ui.phase) {
                "fetching" -> "Fetching source"
                "chunking" -> "Splitting into sections"
                "condensing" -> "Condensing section ${ui.progressCurrent} of ${ui.progressTotal}"
                "synthesizing" -> "Writing the summary"
                "deriving" -> "Building bullets and detailed view"
                else -> "Working"
            }
            Text(label, color = SonarioColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            if (ui.progressTotal > 0 && ui.phase == "condensing") {
                LinearProgressIndicator(
                    progress = { ui.progressCurrent.toFloat() / ui.progressTotal },
                    modifier = Modifier.fillMaxWidth(),
                    color = SonarioColors.Green,
                    trackColor = SonarioColors.Panel2,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SonarioColors.Green,
                    trackColor = SonarioColors.Panel2,
                )
            }
            if (ui.liveText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(ui.liveText.takeLast(280),
                    color = SonarioColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Text(msg, color = SonarioColors.InkSoft,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultArea(
    res: ai.sonario.app.summarize.SummarizeEngine.Result,
    view: SummaryView,
    vm: SummaryViewModel,
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        // Source header
        Text(res.title, style = MaterialTheme.typography.headlineMedium,
            color = SonarioColors.Ink)
        val meta = buildString {
            append(res.kind)
            res.approxMinutes?.let { append("  ·  $it min") }
        }
        Text(meta, color = SonarioColors.Muted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

        // Normal / Detailed / Bullets toggle (default Normal), matching desktop.
        ViewToggle(view, vm)

        Spacer(Modifier.height(14.dp))
        Surface(
            color = SonarioColors.Panel,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val md = when (view) {
                SummaryView.NORMAL -> res.normal
                SummaryView.DETAILED -> res.detailed
                SummaryView.BULLETS -> res.bullets
            }
            Box(Modifier.padding(16.dp)) {
                Markdown(content = md)
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = vm::reset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = SonarioColors.InkSoft),
        ) { Text("New summary") }
    }
}

@Composable
private fun ViewToggle(view: SummaryView, vm: SummaryViewModel) {
    val options = listOf(
        SummaryView.NORMAL to "Normal",
        SummaryView.DETAILED to "Detailed",
        SummaryView.BULLETS to "Bullets",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SonarioColors.Panel2)
            .padding(3.dp),
    ) {
        options.forEach { (v, label) ->
            val selected = v == view
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) SonarioColors.Green else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { vm.setView(v) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label,
                    color = if (selected) SonarioColors.Abyss else SonarioColors.InkSoft,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

