package ai.sonario.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.sonario.app.SummaryService
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.data.Settings
import ai.sonario.app.llm.BUNDLED_MODELS
import ai.sonario.app.llm.GroqEngine
import ai.sonario.app.llm.LlmEngine
import ai.sonario.app.llm.ModelDownloader
import ai.sonario.app.llm.ModelInfo
import ai.sonario.app.llm.RateLimiter
import ai.sonario.app.source.FileTextExtractor
import ai.sonario.app.source.SourceFetcher
import ai.sonario.app.summarize.SummarizeEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SummaryView { NORMAL, DETAILED, BULLETS, CHAPTER }

/** One question and its cited answer, for the ask box. */
data class QaPair(val question: String, val answer: String)

/** What the model download is doing right now, for the picker UI. */
data class DownloadState(
    val model: ModelInfo? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val active: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) bytes.toFloat() / total else 0f
}

data class UiState(
    val input: String = "",
    val busy: Boolean = false,
    val phase: String = "",
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val liveText: String = "",
    val rateWaitSeconds: Long = 0,
    val estimateText: String = "",
    val qaHistory: List<QaPair> = emptyList(),
    val asking: Boolean = false,
    val result: SummarizeEngine.Result? = null,
    val view: SummaryView = SummaryView.NORMAL,
    val error: String? = null,
    val model: ModelInfo = BUNDLED_MODELS.first(),
    val models: List<ModelInfo> = emptyList(),
    val hasAnyModel: Boolean = false,
    val download: DownloadState = DownloadState(),
    // engine + cloud settings
    val engineChoice: EngineChoice = EngineChoice.ON_DEVICE,
    val groqKeySet: Boolean = false,
    val groqModel: String = Settings.DEFAULT_GROQ_MODEL,
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = LlmEngine.get(app)
    private val fetcher = SourceFetcher()
    private val downloader = ModelDownloader(llm.modelsDir())
    private val settings = Settings(app)
    private val rateLimiter = RateLimiter(app)
    private val groq = GroqEngine(
        apiKeyProvider = { settings.groqApiKey },
        modelProvider = { settings.groqModel },
        rateLimiter = rateLimiter,
        onRateWait = { secs -> onRateWait(secs) },
    )

    private var downloadJob: Job? = null
    // Progress collection job for whichever SummarizeEngine is active.
    private var progressJob: Job? = null
    // Retained after a summary so the ask box can answer against the source.
    private var lastSourceText: String = ""
    private var lastSummarizer: SummarizeEngine? = null

    private val appCtx = app.applicationContext

    /** Keep the process foreground-priority so long jobs survive backgrounding. */
    private fun startKeepAlive(text: String) {
        runCatching { SummaryService.start(appCtx, text) }
    }
    private fun stopKeepAlive() {
        runCatching { SummaryService.stop(appCtx) }
    }

    /** A requested export the Activity should fulfil by opening the file picker. */
    data class PendingExport(
        val format: ai.sonario.app.data.Exporter.Format,
        val title: String,
        val body: String,
    )

    private val _pendingExport = MutableStateFlow<PendingExport?>(null)
    val pendingExport: StateFlow<PendingExport?> = _pendingExport.asStateFlow()

    fun requestExport(
        format: ai.sonario.app.data.Exporter.Format, title: String, body: String,
    ) {
        _pendingExport.value = PendingExport(format, title, body)
    }

    fun clearPendingExport() { _pendingExport.value = null }

    // ── ask a question about the summarized source ──────────────────────────────

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _ui.value.asking) return
        val summarizer = lastSummarizer
        if (summarizer == null || lastSourceText.isBlank()) {
            _ui.value = _ui.value.copy(
                error = "Ask is available after you summarize something.")
            return
        }
        _ui.value = _ui.value.copy(asking = true)
        viewModelScope.launch {
            val answer = try {
                summarizer.answer(q, lastSourceText)
            } catch (e: Exception) {
                "Sorry, that failed: ${e.message}"
            }
            _ui.value = _ui.value.copy(
                asking = false,
                qaHistory = _ui.value.qaHistory + QaPair(q, answer),
            )
        }
    }

    // ── local file picker ───────────────────────────────────────────────────────

    private val extractor = FileTextExtractor(app)

    /** Set true to ask the Activity to open the system file picker. */
    private val _pickFile = MutableStateFlow(false)
    val pickFile: StateFlow<Boolean> = _pickFile.asStateFlow()

    fun requestPickFile() { _pickFile.value = true }
    fun clearPickFile() { _pickFile.value = false }

    /** Called by the Activity with the picked file's Uri. Extracts + summarizes. */
    fun onFilePicked(uri: android.net.Uri) {
        _pickFile.value = false
        if (_ui.value.busy) return
        val useGroq = _ui.value.engineChoice == EngineChoice.GROQ
        if (useGroq && !settings.hasGroqKey) {
            _ui.value = _ui.value.copy(
                error = "Groq is selected but no API key is set. Open Settings first.")
            return
        }
        if (!useGroq && !llm.isModelPresent(_ui.value.model)) {
            _ui.value = _ui.value.copy(
                error = "That model isn't on the device yet. Download it first.")
            return
        }

        val summarizer = if (useGroq)
            SummarizeEngine(groq, bigContext = true)
        else
            SummarizeEngine(llm, bigContext = false)
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            summarizer.progress.collect { p ->
                _ui.value = _ui.value.copy(
                    phase = p.phase, progressCurrent = p.current,
                    progressTotal = p.total, liveText = p.live,
                    rateWaitSeconds = if (p.live.isNotBlank()) 0 else _ui.value.rateWaitSeconds)
            }
        }

        _ui.value = _ui.value.copy(busy = true, error = null, result = null,
            phase = "reading file", liveText = "", rateWaitSeconds = 0, estimateText = "")
        startKeepAlive("Summarizing your file…")
        viewModelScope.launch {
            try {
                val ex = extractor.extract(uri)
                if (!ex.ok) {
                    _ui.value = _ui.value.copy(busy = false,
                        error = ex.error ?: "Couldn't read that file.")
                    return@launch
                }
                if (useGroq) setEstimate(ex.text)
                var result = summarizer.run(
                    model = _ui.value.model,
                    text = ex.text,
                    title = ex.name,
                    kind = if (ex.isEpub) "EPUB" else "Document",
                    approxMinutes = null,
                )
                // EPUB: also build the per-chapter summary for the Chapter view.
                if (ex.isEpub) {
                    val chapters = summarizer.summarizeChapters(ex.chapters, result.normal)
                    result = result.copy(chapters = chapters)
                }
                lastSourceText = ex.text
                lastSummarizer = summarizer
                _ui.value = _ui.value.copy(busy = false, result = result,
                    view = SummaryView.NORMAL, liveText = "", rateWaitSeconds = 0,
                    estimateText = "", qaHistory = emptyList())
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false,
                    error = e.message ?: "Summarize failed.")
            } finally {
                progressJob?.cancel()
                stopKeepAlive()
            }
        }
    }

    /** Called from the Groq engine while it waits for a rate-limit slot. */
    private fun onRateWait(secs: Long) {
        _ui.value = _ui.value.copy(rateWaitSeconds = secs)
    }

    /** Tokens used today against the free-tier daily cap, for display. */
    fun groqDailyUsage(): Pair<Long, Long> {
        val u = rateLimiter.dailyUsage()
        return u.used to u.limit
    }

    /** Build a human-readable estimate line and put it in the UI (Groq only). */
    private fun setEstimate(sourceText: String) {
        val e = rateLimiter.estimate(sourceText)
        val tokens = formatCount(e.totalTokens)
        val eta = formatEta(e.etaSeconds)
        val line = buildString {
            append("~$tokens tokens · $eta")
            append(" · ${e.percentOfRemaining}% of today's remaining budget")
            if (e.exceedsDaily) {
                val rem = formatCount(e.dailyRemaining)
                append("\n⚠ This is more than today's remaining free-tier budget " +
                    "(~$rem left). It may stop partway; it resets tomorrow.")
            }
        }
        _ui.value = _ui.value.copy(estimateText = line)
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> "${(n / 1000)}K"
        else -> n.toString()
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 60 -> "est. ~${seconds}s"
        else -> {
            val m = seconds / 60
            val s = seconds % 60
            if (s == 0L) "est. ~${m} min" else "est. ~${m}m ${s}s"
        }
    }

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private fun initialState(): UiState {
        val models = llm.availableModels()
        val present = models.firstOrNull { it.present }
        return UiState(
            models = models,
            hasAnyModel = present != null,
            model = present ?: models.first(),
            engineChoice = settings.engine,
            groqKeySet = settings.hasGroqKey,
            groqModel = settings.groqModel,
        )
    }

    fun onInput(s: String) { _ui.value = _ui.value.copy(input = s) }
    fun setView(v: SummaryView) { _ui.value = _ui.value.copy(view = v) }
    fun setModel(m: ModelInfo) { _ui.value = _ui.value.copy(model = m) }

    fun refreshModels() {
        val models = llm.availableModels()
        _ui.value = _ui.value.copy(
            models = models,
            hasAnyModel = models.any { it.present },
        )
    }

    // ── engine + cloud settings ────────────────────────────────────────────────

    fun setEngine(choice: EngineChoice) {
        settings.engine = choice
        _ui.value = _ui.value.copy(engineChoice = choice)
    }

    fun setGroqKey(key: String) {
        settings.groqApiKey = key
        _ui.value = _ui.value.copy(groqKeySet = settings.hasGroqKey)
    }

    fun setGroqModel(model: String) {
        settings.groqModel = model
        _ui.value = _ui.value.copy(groqModel = settings.groqModel)
    }

    fun currentGroqKeyMasked(): String {
        val k = settings.groqApiKey ?: return ""
        return if (k.length <= 8) "••••" else k.take(4) + "…" + k.takeLast(4)
    }

    // ── model download (first-run picker + Models screen) ──────────────────────

    fun downloadModel(model: ModelInfo) {
        if (_ui.value.download.active) return
        _ui.value = _ui.value.copy(
            download = DownloadState(model = model, active = true))
        downloadJob = viewModelScope.launch {
            downloader.download(model).collect { st ->
                when (st) {
                    is ModelDownloader.State.Progress ->
                        _ui.value = _ui.value.copy(
                            download = _ui.value.download.copy(
                                bytes = st.bytes, total = st.total))
                    is ModelDownloader.State.Done -> {
                        val models = llm.availableModels()
                        _ui.value = _ui.value.copy(
                            models = models,
                            hasAnyModel = true,
                            model = models.firstOrNull { it.fileName == model.fileName }
                                ?: model,
                            download = DownloadState(),
                        )
                    }
                    is ModelDownloader.State.Failed ->
                        _ui.value = _ui.value.copy(
                            download = DownloadState(model = model, error = st.message))
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _ui.value = _ui.value.copy(download = DownloadState())
    }

    // ── summarize ──────────────────────────────────────────────────────────────

    fun summarize() {
        val state = _ui.value
        if (state.input.isBlank() || state.busy) return

        val useGroq = state.engineChoice == EngineChoice.GROQ
        if (useGroq) {
            if (!settings.hasGroqKey) {
                _ui.value = state.copy(
                    error = "Groq is selected but no API key is set. Open Settings " +
                            "and paste your free key from console.groq.com.")
                return
            }
        } else {
            if (!llm.isModelPresent(state.model)) {
                _ui.value = state.copy(
                    error = "That model isn't on the device yet. Download it first.")
                return
            }
        }

        // Build the pipeline for the chosen engine. Cloud gets big-context chunking.
        val summarizer = if (useGroq)
            SummarizeEngine(groq, bigContext = true)
        else
            SummarizeEngine(llm, bigContext = false)

        // Re-subscribe progress to this pipeline instance.
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            summarizer.progress.collect { p ->
                _ui.value = _ui.value.copy(
                    phase = p.phase,
                    progressCurrent = p.current,
                    progressTotal = p.total,
                    liveText = p.live,
                    // tokens flowing means we're not waiting on the rate limit
                    rateWaitSeconds = if (p.live.isNotBlank()) 0 else _ui.value.rateWaitSeconds,
                )
            }
        }

        _ui.value = state.copy(busy = true, error = null, result = null,
            phase = "fetching", liveText = "", rateWaitSeconds = 0, estimateText = "")
        startKeepAlive("Summarizing…")
        viewModelScope.launch {
            try {
                val src = fetcher.fetch(state.input)
                if (!src.ok) {
                    _ui.value = _ui.value.copy(busy = false,
                        error = src.error ?: "Could not read that source.")
                    return@launch
                }
                if (useGroq) setEstimate(src.text)
                val result = summarizer.run(
                    model = state.model,
                    text = src.text,
                    title = src.title,
                    kind = src.kind,
                    approxMinutes = src.approxMinutes,
                )
                lastSourceText = src.text
                lastSummarizer = summarizer
                _ui.value = _ui.value.copy(busy = false, result = result,
                    view = SummaryView.NORMAL, liveText = "", rateWaitSeconds = 0,
                    estimateText = "", qaHistory = emptyList())
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false,
                    error = e.message ?: "Summarize failed.")
            } finally {
                progressJob?.cancel()
                stopKeepAlive()
            }
        }
    }

    fun reset() {
        _ui.value = _ui.value.copy(result = null, error = null, input = "",
            phase = "", liveText = "")
    }
}
