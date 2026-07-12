package ai.sonario.app.ui

import ai.sonario.app.R
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.data.Exporter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ai.sonario.app.summarize.SummarizeEngine
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    vm: SummaryViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = SonarioColors.Deep,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(
                                id = R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sonar", style = MaterialTheme.typography.titleLarge,
                            color = SonarioColors.Ink)
                        Text("io", style = MaterialTheme.typography.titleLarge,
                            color = SonarioColors.Green)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings",
                            tint = SonarioColors.InkSoft)
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
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(10.dp))
                val cloud = ui.engineChoice == EngineChoice.GROQ
                Text(
                    if (cloud) "Summarize anything, fast"
                    else "Summarize anything, on device",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonarioColors.Ink, textAlign = TextAlign.Center)
                Text(
                    if (cloud)
                        "Paste a YouTube link, an article URL, or text. Your text is " +
                        "sent to Groq's cloud to summarize."
                    else
                        "Paste a YouTube link, an article URL, or text. On-device: " +
                        "nothing leaves your phone except the fetch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonarioColors.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))

                EngineToggle(ui, vm)
                Spacer(Modifier.height(14.dp))

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

@Composable
private fun EngineToggle(ui: UiState, vm: SummaryViewModel) {
    val options = listOf(
        EngineChoice.ON_DEVICE to "On-device",
        EngineChoice.GROQ to "Groq cloud",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SonarioColors.Panel2)
            .padding(3.dp),
    ) {
        options.forEach { (choice, label) ->
            val selected = choice == ui.engineChoice
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) SonarioColors.Green
                               else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { vm.setEngine(choice) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label,
                    color = if (selected) SonarioColors.Abyss else SonarioColors.InkSoft,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
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
                colors = sonarioFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            // Or pick a local document (PDF, EPUB, DOCX, TXT, MD).
            Surface(
                color = SonarioColors.Panel2,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.requestPickFile() },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null,
                        tint = SonarioColors.Green, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Choose a file  (PDF, EPUB, DOCX, TXT, MD)",
                        color = SonarioColors.InkSoft,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f, fill = false),
                    label = {
                        val isCloud = ui.engineChoice == EngineChoice.GROQ
                        Text(
                            if (isCloud) "Groq: ${shortModel(ui.groqModel)}"
                            else ui.model.label,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = SonarioColors.Muted),
                )
                Spacer(Modifier.width(8.dp))
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
                    Text("Summarize", fontWeight = FontWeight.SemiBold,
                        maxLines = 1, softWrap = false)
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
                "chapters" -> "Summarizing chapters ${ui.progressCurrent} of ${ui.progressTotal}"
                "reading file" -> "Reading file"
                "answering" -> "Finding the answer"
                else -> "Working"
            }
            Text(label, color = SonarioColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)

            if (ui.estimateText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(ui.estimateText,
                    color = SonarioColors.Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }

            if (ui.rateWaitSeconds > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pacing for Groq's rate limit, resuming in ${ui.rateWaitSeconds}s. " +
                    "Large videos are sent in timed batches so they don't get blocked.",
                    color = SonarioColors.Teal,
                    style = MaterialTheme.typography.bodyMedium)
            }

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
    res: SummarizeEngine.Result,
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
        ViewToggle(view, vm, hasChapters = res.chapters.isNotBlank())

        val md = when (view) {
            SummaryView.NORMAL -> res.normal
            SummaryView.DETAILED -> res.detailed
            SummaryView.BULLETS -> res.bullets
            SummaryView.CHAPTER -> res.chapters
        }

        // Actions: copy all + export to a file.
        Spacer(Modifier.height(12.dp))
        val clipboard = LocalClipboardManager.current
        var showExport by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(md)) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SonarioColors.InkSoft),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy all")
            }
            Spacer(Modifier.width(10.dp))
            Box {
                Button(
                    onClick = { showExport = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonarioColors.Green,
                        contentColor = SonarioColors.Abyss),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
                DropdownMenu(
                    expanded = showExport,
                    onDismissRequest = { showExport = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Save as PDF") },
                        onClick = { showExport = false
                            vm.requestExport(Exporter.Format.PDF, res.title, md) })
                    DropdownMenuItem(
                        text = { Text("Save as Markdown (.md)") },
                        onClick = { showExport = false
                            vm.requestExport(Exporter.Format.MD, res.title, md) })
                    DropdownMenuItem(
                        text = { Text("Save as text (.txt)") },
                        onClick = { showExport = false
                            vm.requestExport(Exporter.Format.TXT, res.title, md) })
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Surface(
            color = SonarioColors.Panel,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(16.dp)) {
                SelectionContainer {
                    Markdown(
                        content = md,
                        colors = markdownColor(
                            text = SonarioColors.Ink,
                            linkText = SonarioColors.Green,
                        ),
                        typography = markdownTypography(
                            // Section headers: smaller, semibold, Sonario green,
                            // instead of the library's default huge white text.
                            h1 = MaterialTheme.typography.titleLarge.copy(
                                color = SonarioColors.Green,
                                fontWeight = FontWeight.SemiBold),
                            h2 = MaterialTheme.typography.titleMedium.copy(
                                color = SonarioColors.Green,
                                fontWeight = FontWeight.SemiBold),
                            h3 = MaterialTheme.typography.titleSmall.copy(
                                color = SonarioColors.Green,
                                fontWeight = FontWeight.SemiBold),
                            h4 = MaterialTheme.typography.labelLarge.copy(
                                color = SonarioColors.Green,
                                fontWeight = FontWeight.SemiBold),
                            h5 = MaterialTheme.typography.labelLarge.copy(
                                color = SonarioColors.Green),
                            h6 = MaterialTheme.typography.labelLarge.copy(
                                color = SonarioColors.Green),
                            text = MaterialTheme.typography.bodyLarge.copy(
                                color = SonarioColors.Ink),
                            paragraph = MaterialTheme.typography.bodyLarge.copy(
                                color = SonarioColors.Ink),
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        AskBox(vm)

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = vm::reset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = SonarioColors.InkSoft),
        ) { Text("New summary") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskBox(vm: SummaryViewModel) {
    val ui by vm.ui.collectAsState()
    var question by remember { mutableStateOf("") }

    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Ask about this", color = SonarioColors.InkSoft,
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            // Prior Q&A pairs.
            ui.qaHistory.forEach { qa ->
                Text(qa.question, color = SonarioColors.Green,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Markdown(
                        content = qa.answer,
                        colors = markdownColor(
                            text = SonarioColors.Ink, linkText = SonarioColors.Green),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Ask a question about the source") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.asking,
                colors = sonarioFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.ask(question); question = "" },
                enabled = !ui.asking && question.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SonarioColors.Green,
                    contentColor = SonarioColors.Abyss),
            ) {
                if (ui.asking) {
                    Text("Thinking…")
                } else {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ask")
                }
            }
        }
    }
}

@Composable
private fun ViewToggle(view: SummaryView, vm: SummaryViewModel, hasChapters: Boolean) {
    val options = buildList {
        add(SummaryView.NORMAL to "Normal")
        add(SummaryView.DETAILED to "Detailed")
        add(SummaryView.BULLETS to "Bullets")
        if (hasChapters) add(SummaryView.CHAPTER to "Chapter")
    }
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
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1)
            }
        }
    }
}


// Trim a Groq model id like "meta-llama/llama-4-scout-17b-16e-instruct" to
// something short for the chip.
private fun shortModel(m: String): String {
    val tail = m.substringAfterLast('/')
    return if (tail.length > 22) tail.take(22) + "…" else tail
}
