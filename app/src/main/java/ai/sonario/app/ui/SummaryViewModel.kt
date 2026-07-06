package ai.sonario.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.sonario.app.llm.BUNDLED_MODELS
import ai.sonario.app.llm.LlmEngine
import ai.sonario.app.llm.ModelDownloader
import ai.sonario.app.llm.ModelInfo
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
    val result: SummarizeEngine.Result? = null,
    val view: SummaryView = SummaryView.NORMAL,
    val error: String? = null,
    val model: ModelInfo = BUNDLED_MODELS.first(),
    val models: List<ModelInfo> = emptyList(),
    val hasAnyModel: Boolean = false,
    val download: DownloadState = DownloadState(),
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = LlmEngine.get(app)
    private val fetcher = SourceFetcher()
    private val engine = SummarizeEngine(llm)
    private val downloader = ModelDownloader(llm.modelsDir())

    private var downloadJob: Job? = null

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private fun initialState(): UiState {
        val models = llm.availableModels()
        val present = models.firstOrNull { it.present }
        return UiState(
            models = models,
            hasAnyModel = present != null,
            model = present ?: models.first(),
        )
    }

    init {
        viewModelScope.launch {
            engine.progress.collect { p ->
                _ui.value = _ui.value.copy(
                    phase = p.phase,
                    progressCurrent = p.current,
                    progressTotal = p.total,
                    liveText = p.live,
                )
            }
        }
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
        if (!llm.isModelPresent(state.model)) {
            _ui.value = state.copy(
                error = "That model isn't on the device yet. Download it first.")
            return
        }
        _ui.value = state.copy(busy = true, error = null, result = null,
            phase = "fetching", liveText = "")
        viewModelScope.launch {
            try {
                val src = fetcher.fetch(state.input)
                if (!src.ok) {
                    _ui.value = _ui.value.copy(busy = false,
                        error = src.error ?: "Could not read that source.")
                    return@launch
                }
                val result = engine.run(
                    model = state.model,
                    text = src.text,
                    title = src.title,
                    kind = src.kind,
                    approxMinutes = src.approxMinutes,
                )
                _ui.value = _ui.value.copy(busy = false, result = result,
                    view = SummaryView.NORMAL, liveText = "")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false,
                    error = "Summarize failed: ${e.message}")
            }
        }
    }

    fun reset() {
        _ui.value = _ui.value.copy(result = null, error = null, input = "",
            phase = "", liveText = "")
    }
}
