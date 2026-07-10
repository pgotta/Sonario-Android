package ai.sonario.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.data.Settings
import ai.sonario.app.llm.BUNDLED_MODELS
import ai.sonario.app.llm.GroqEngine
import ai.sonario.app.llm.LlmEngine
import ai.sonario.app.llm.ModelDownloader
import ai.sonario.app.llm.ModelInfo
import ai.sonario.app.llm.RateLimiter
import ai.sonario.app.source.SourceFetcher
import ai.sonario.app.summarize.SummarizeEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SummaryView { NORMAL, DETAILED, BULLETS }

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

    /** Called from the Groq engine while it waits for a rate-limit slot. */
    private fun onRateWait(secs: Long) {
        _ui.value = _ui.value.copy(rateWaitSeconds = secs)
    }

    /** Tokens used today against the free-tier daily cap, for display. */
    fun groqDailyUsage(): Pair<Long, Long> {
        val u = rateLimiter.dailyUsage()
        return u.used to u.limit
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
            phase = "fetching", liveText = "", rateWaitSeconds = 0)
        viewModelScope.launch {
            try {
                val src = fetcher.fetch(state.input)
                if (!src.ok) {
                    _ui.value = _ui.value.copy(busy = false,
                        error = src.error ?: "Could not read that source.")
                    return@launch
                }
                val result = summarizer.run(
                    model = state.model,
                    text = src.text,
                    title = src.title,
                    kind = src.kind,
                    approxMinutes = src.approxMinutes,
                )
                _ui.value = _ui.value.copy(busy = false, result = result,
                    view = SummaryView.NORMAL, liveText = "", rateWaitSeconds = 0)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false,
                    error = e.message ?: "Summarize failed.")
            } finally {
                progressJob?.cancel()
            }
        }
    }

    fun reset() {
        _ui.value = _ui.value.copy(result = null, error = null, input = "",
            phase = "", liveText = "")
    }
}
