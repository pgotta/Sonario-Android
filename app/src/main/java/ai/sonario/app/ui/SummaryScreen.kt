package ai.sonario.app.ui

// Focal v1.4.0 - restored original SummaryScreen

import ai.sonario.app.R
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.data.Exporter
import ai.sonario.app.data.SessionStatus
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import ai.sonario.app.summarize.SummarizeEngine
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.delay

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
                        Text("Focal", style = MaterialTheme.typography.titleLarge,
                            color = SonarioColors.Ink)
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
                .imePadding()
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
                val cloud = ui.engineChoice == EngineChoice.CLOUD
                Text(
                    if (cloud) "Summarize anything, fast"
                    else "Summarize anything, on device",
                    style = MaterialTheme.typography.headlineMedium,
                    color = SonarioColors.Ink, textAlign = TextAlign.Center)
                Text(
                    if (cloud)
                        "Paste a YouTube link, an article URL, or text. Your text is " +
                            "sent to the cloud to summarize."
                    else
                        "Paste a YouTube link, an article URL, or text. On-device: " +
                            "nothing leaves your phone except the fetch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonarioColors.Muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))

                EngineToggle(ui, vm)
                Spacer(Modifier.height(14.dp))

                InputCard(ui, vm)

                if (ui.sessionNotice.isNotBlank() || ui.resumeAvailable) {
                    SessionNoticeCard(ui, vm)
                }
                if (ui.recentSessions.isNotEmpty()) {
                    RecentSessionsCard(ui, vm)
                }
            }

            ui.error?.let { ErrorCard(it) }

            if (ui.busy) ProgressCard(ui, vm)

            ui.result?.let { res -> ResultArea(res, ui.view, vm) }

            Spacer(Modifier.height(40.dp))
        }
    }

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
        EngineChoice.CLOUD to "Cloud",
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
                    .clickable(enabled = !ui.busy && !ui.asking) { vm.setEngine(choice) }
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
                        val isCloud = ui.engineChoice == EngineChoice.CLOUD
                        Text(
                            if (isCloud) "Cloud"
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
private fun SessionNoticeCard(ui: UiState, vm: SummaryViewModel) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (ui.sessionNotice.isNotBlank()) {
                Text(
                    ui.sessionNotice,
                    color = SonarioColors.InkSoft,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (ui.resumeAvailable) {
                Spacer(Modifier.height(if (ui.sessionNotice.isBlank()) 0.dp else 10.dp))
                Button(
                    onClick = { vm.resumeSession() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonarioColors.Green,
                        contentColor = SonarioColors.Abyss,
                    ),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Resume saved session")
                }
            }
        }
    }
}

@Composable
private fun RecentSessionsCard(ui: UiState, vm: SummaryViewModel) {
    var showClearConfirmation by remember { mutableStateOf(false) }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear all recent sessions?") },
            text = {
                Text(
                    "This permanently deletes every saved session and " +
                        "summary from this device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        vm.clearAllSessions()
                    },
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = SonarioColors.Green,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recent sessions",
                    color = SonarioColors.InkSoft,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { showClearConfirmation = true },
                    enabled = !ui.busy && !ui.asking,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
            }
            Spacer(Modifier.height(8.dp))

            ui.recentSessions.take(6).forEachIndexed { index, session ->
                val relative = remember(session.updatedAt) {
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        session.updatedAt,
                        System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                }
                val status = when (session.status) {
                    SessionStatus.COMPLETE -> "Saved"
                    SessionStatus.RUNNING -> "Interrupted"
                    SessionStatus.FAILED -> "Stopped"
                    SessionStatus.CANCELLED -> "Cancelled"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !ui.busy && !ui.asking) {
                            vm.openSession(session.id)
                        }
                        .padding(vertical = 9.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            session.title,
                            color = SonarioColors.Ink,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "$status · ${session.kind} · $relative",
                            color = SonarioColors.Muted,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                    if (session.canResume) {
                        IconButton(
                            onClick = { vm.resumeSession(session.id) },
                            enabled = !ui.busy && !ui.asking,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Resume",
                                tint = SonarioColors.Green,
                            )
                        }
                    }
                    IconButton(
                        onClick = { vm.deleteSession(session.id) },
                        enabled = !ui.busy && !ui.asking,
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete session",
                            tint = SonarioColors.Muted,
                        )
                    }
                }
                if (index != ui.recentSessions.take(6).lastIndex) {
                    HorizontalDivider(color = SonarioColors.Panel2)
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(ui: UiState, vm: SummaryViewModel) {
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

            if (ui.networkStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    ui.networkStatus,
                    color = SonarioColors.Teal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (ui.rateWaitSeconds > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pacing for the rate limit, resuming in ${ui.rateWaitSeconds}s.",
                    color = SonarioColors.Teal,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(10.dp))
            if (ui.progressTotal > 0 && ui.phase == "condensing") {
                LinearProgressIndicator(
                    progress = {
                        if (ui.progressTotal > 0)
                            (ui.progressCurrent.toFloat() / ui.progressTotal).coerceIn(0f, 1f)
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = SonarioColors.Green,
                    trackColor = SonarioColors.Panel2,
                )
            } else if (ui.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SonarioColors.Green,
                    trackColor = SonarioColors.Panel2,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ui.phase}", color = SonarioColors.Muted,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { vm.cancel() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = SonarioColors.Muted),
                ) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ResultArea(
    res: SummarizeEngine.Result,
    view: SummaryView,
    vm: SummaryViewModel,
) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            val focusManager = LocalFocusManager.current
            val bringIntoView = remember { BringIntoViewRequester() }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(res.title.ifBlank { "Summary" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SonarioColors.Ink,
                    modifier = Modifier.weight(1f))
                ViewTabs(view) { vm.setView(it) }
            }
            Spacer(Modifier.height(12.dp))

            SelectionContainer {
                Markdown(
                    content = when (view) {
                        SummaryView.NORMAL -> res.normal
                        SummaryView.DETAILED -> res.detailed
                        SummaryView.BULLETS -> res.bullets
                        SummaryView.CHAPTER -> res.chapters.ifBlank { res.normal }
                    },
                    colors = markdownColor(
                        text = SonarioColors.Ink,
                        codeBackground = SonarioColors.Panel2,
                        inlineCodeBackground = SonarioColors.Panel2,
                    ),
                    typography = markdownTypography(
                        default = MaterialTheme.typography.bodyMedium.copy(
                            color = SonarioColors.Ink),
                    ),

                )
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clipboard = LocalClipboardManager.current
                val copyText = when (view) {
                    SummaryView.NORMAL -> res.normal
                    SummaryView.DETAILED -> res.detailed
                    SummaryView.BULLETS -> res.bullets
                    SummaryView.CHAPTER -> res.chapters.ifBlank { res.normal }
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(stripMd(copyText))) {
                }) {
                    Icon(Icons.Filled.ContentCopy, "Copy",
                        tint = SonarioColors.InkSoft)
                }
                Spacer(Modifier.width(8.dp))
                ExportMenu(res, vm)
                Spacer(Modifier.weight(1f))
                approxMinutes(res.approxMinutes)
            }

            Spacer(Modifier.height(16.dp))
            AskBox(vm, focusManager, bringIntoView)
        }
    }
}

@Composable
private fun AskBox(
    vm: SummaryViewModel,
    focusManager: androidx.compose.ui.focus.FocusManager,
    bringIntoView: BringIntoViewRequester,
) {
    var question by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    val asking = vm.ui.collectAsState().value.asking
    val askError = vm.ui.collectAsState().value.askError
    val qaHistory = vm.ui.collectAsState().value.qaHistory

    OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        placeholder = { Text("Ask a question about this source…") },
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { hasFocus = it.isFocused },
        singleLine = true,
        trailingIcon = {
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    if (vm.ask(question)) question = ""
                },
                enabled = question.isNotBlank() && !asking,
            ) {
                if (asking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = SonarioColors.Green,
                        strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.AutoAwesome, "Ask",
                        tint = if (question.isNotBlank()) SonarioColors.Green
                               else SonarioColors.Muted)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focusManager.clearFocus()
                if (vm.ask(question)) question = ""
            },
        ),
        colors = sonarioFieldColors(),
    )

    askError?.let {
        Text(it, color = SonarioColors.Teal,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp))
    }

    if (qaHistory.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        qaHistory.takeLast(3).forEach { qa ->
            Surface(
                color = SonarioColors.Panel2,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Q: ${qa.question}", fontWeight = FontWeight.SemiBold,
                        color = SonarioColors.Ink,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(qa.answer, color = SonarioColors.InkSoft,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ViewTabs(current: SummaryView, onChange: (SummaryView) -> Unit) {
    val views = listOf(
        SummaryView.NORMAL to "Normal",
        SummaryView.DETAILED to "Detailed",
        SummaryView.BULLETS to "Bullets",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SonarioColors.Panel2)
            .padding(2.dp)
    ) {
        views.forEach { (v, label) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (v == current) SonarioColors.Green
                               else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label,
                    color = if (v == current) SonarioColors.Abyss else SonarioColors.InkSoft,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ExportMenu(res: SummarizeEngine.Result, vm: SummaryViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val exporter = ai.sonario.app.data.Exporter

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Download, "Export", tint = SonarioColors.InkSoft)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Export as TXT") },
                onClick = {
                    expanded = false
                    vm.requestExport(Exporter.Format.TXT, res.title, res.normal)
                },
            )
            DropdownMenuItem(
                text = { Text("Export as Markdown") },
                onClick = {
                    expanded = false
                    vm.requestExport(Exporter.Format.MD, res.title, res.normal)
                },
            )
            DropdownMenuItem(
                text = { Text("Export as PDF") },
                onClick = {
                    expanded = false
                    vm.requestExport(Exporter.Format.PDF, res.title, res.normal)
                },
            )
        }
    }
}

@Composable
private fun ErrorCard(error: String) {
    Surface(
        color = SonarioColors.Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Text(
            error,
            color = SonarioColors.Teal,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(14.dp),
        )
    }
}

private fun approxMinutes(minutes: Int?) {
    if (minutes != null && minutes > 0) {
        Text("~$minutes min source",
            color = SonarioColors.Muted,
            style = MaterialTheme.typography.labelMedium)
    }
}

private fun stripMd(md: String): String {
    return md
        .replace(Regex("\\*{1,2}(.+?)\\*{1,2}"), "$1")
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("\\[(.+?)\\(.*?\\)\\]"), "$1")
        .trim()
}
