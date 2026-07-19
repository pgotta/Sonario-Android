package ai.sonario.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.sonario.app.SummaryService
import ai.sonario.app.data.EngineChoice
import ai.sonario.app.data.SessionPreview
import ai.sonario.app.data.SessionStatus
import ai.sonario.app.data.SessionStore
import ai.sonario.app.data.Settings
import ai.sonario.app.data.StoredQa
import ai.sonario.app.data.SummarySession
import ai.sonario.app.llm.BUNDLED_MODELS
import ai.sonario.app.llm.CloudEngine
import ai.sonario.app.llm.LlmEngine
import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.ModelDownloader
import ai.sonario.app.llm.ModelInfo
import ai.sonario.app.llm.RateLimiter
import ai.sonario.app.source.FileTextExtractor
import ai.sonario.app.source.SourceFetcher
import ai.sonario.app.summarize.SummarizeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class SummaryView { NORMAL, DETAILED, BULLETS, CHAPTER }

data class QaPair(val question: String, val answer: String)

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
    val networkStatus: String = "",
    val estimateText: String = "",
    val qaHistory: List<QaPair> = emptyList(),
    val asking: Boolean = false,
    val askError: String? = null,
    val result: SummarizeEngine.Result? = null,
    val view: SummaryView = SummaryView.NORMAL,
    val error: String? = null,
    val model: ModelInfo = BUNDLED_MODELS.first(),
    val models: List<ModelInfo> = emptyList(),
    val hasAnyModel: Boolean = false,
    val download: DownloadState = DownloadState(),
    // engine + cloud settings
    val engineChoice: EngineChoice = EngineChoice.ON_DEVICE,
    val cloudProvider: LlmProvider = LlmProvider.GROQ,
    val providerKeySet: Boolean = false,
    val providerModel: String = "",
    // Durable local sessions.
    val activeSessionId: String? = null,
    val recentSessions: List<SessionPreview> = emptyList(),
    val resumeAvailable: Boolean = false,
    val sessionNotice: String = "",
)

class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    private val llm = LlmEngine.get(app)
    private val fetcher = SourceFetcher()
    private val downloader = ModelDownloader(llm.modelsDir())
    private val settings = Settings(app)
    private val sessionStore = SessionStore(app)
    private val rateLimiter = RateLimiter(app)

    private var downloadJob: Job? = null

    // Long-running work is process-scoped rather than Activity/ViewModel-scoped.
    private var summaryJob: Job?
        get() = processSummaryJob
        set(value) { processSummaryJob = value }
    private var askJob: Job?
        get() = processAskJob
        set(value) { processAskJob = value }
    private var progressJob: Job?
        get() = processProgressJob
        set(value) { processProgressJob = value }
    private var lastSourceText: String
        get() = processLastSourceText
        set(value) { processLastSourceText = value }
    private var lastSummarizer: SummarizeEngine?
        get() = processLastSummarizer
        set(value) { processLastSummarizer = value }

    private val appCtx = app.applicationContext

    private fun startKeepAlive(text: String) {
        runCatching { SummaryService.start(appCtx, text) }
    }
    private fun stopKeepAlive() {
        runCatching { SummaryService.stop(appCtx) }
    }

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

    // ── cloud engine factory ───────────────────────────────────────────────────

    private fun makeCloudEngine(provider: LlmProvider): CloudEngine {
        val cfg = settings.configFor(provider)
        return CloudEngine(
            context = app,
            configProvider = { cfg },
            apiKeyProvider = { settings.keyFor(provider) },
            rateLimiter = rateLimiter,
            onRateWait = { seconds -> onRateWait(seconds) },
            onNetworkStatus = { message -> onNetworkStatus(message) },
        )
    }

    private fun makeSummarizer(engine: EngineChoice, provider: LlmProvider): SummarizeEngine {
        return when (engine) {
            EngineChoice.ON_DEVICE -> SummarizeEngine(llm, bigContext = false)
            EngineChoice.CLOUD -> SummarizeEngine(makeCloudEngine(provider), bigContext = true)
        }
    }

    // ── ask a question about the summarized source ──────────────────────────────

    fun ask(question: String): Boolean {
        val q = question.trim()
        if (q.isEmpty() || _ui.value.asking || _ui.value.busy) return false
        val summarizer = lastSummarizer
        if (summarizer == null || lastSourceText.isBlank()) {
            _ui.value = _ui.value.copy(
                askError = "Ask is available after Sonario finishes a summary."
            )
            return false
        }

        _ui.value = _ui.value.copy(
            asking = true,
            askError = null,
            networkStatus = "",
        )
        startKeepAlive("Answering your question…")
        askJob?.cancel()
        askJob = PROCESS_SCOPE.launch {
            try {
                val answer = summarizer.answer(q, lastSourceText)
                val updatedQa = _ui.value.qaHistory + QaPair(q, answer)
                _ui.value = _ui.value.copy(
                    qaHistory = updatedQa,
                    askError = null,
                )
                _ui.value.activeSessionId?.let { id ->
                    sessionStore.load(id)?.let { session ->
                        sessionStore.save(session.copy(
                            qaHistory = updatedQa.map { StoredQa(it.question, it.answer) },
                        ))
                    }
                    refreshSessionPreviewsNow()
                }
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                _ui.value = _ui.value.copy(
                    askError = friendlyFailure(
                        error,
                        "Sonario couldn't answer that question.",
                    )
                )
            } finally {
                _ui.value = _ui.value.copy(
                    asking = false,
                    networkStatus = "",
                    rateWaitSeconds = 0,
                )
                askJob = null
                stopKeepAlive()
            }
        }
        return true
    }

    // ── local file picker ───────────────────────────────────────────────────────

    private val extractor = FileTextExtractor(app)

    private val _pickFile = MutableStateFlow(false)
    val pickFile: StateFlow<Boolean> = _pickFile.asStateFlow()

    fun requestPickFile() { _pickFile.value = true }
    fun clearPickFile() { _pickFile.value = false }

    fun onFilePicked(uri: android.net.Uri) {
        _pickFile.value = false
        if (_ui.value.busy) return

        val state = _ui.value
        val model = state.model
        if (!validateEngine(state.engineChoice, model)) return
        val summarizer = makeSummarizer(state.engineChoice, state.cloudProvider)
        attachProgress(summarizer)

        var session = sessionStore.save(SummarySession(
            input = uri.toString(),
            title = "Reading file…",
            kind = "Document",
            engineChoice = state.engineChoice,
            modelFileName = model.fileName,
            cloudProviderId = state.cloudProvider.id,
            cloudModel = state.providerModel,
            phase = "reading file",
        ))
        prepareUiForSession(session)
        startKeepAlive("Reading and summarizing your file…")

        summaryJob?.cancel()
        summaryJob = PROCESS_SCOPE.launch {
            try {
                val ex = extractor.extract(uri)
                if (!ex.ok) {
                    throw IllegalStateException(ex.error ?: "Couldn't read that file.")
                }
                session = sessionStore.save(session.copy(
                    input = ex.name,
                    title = ex.name,
                    kind = if (ex.isEpub) "EPUB" else "Document",
                    sourceText = ex.text,
                    chapters = ex.chapters,
                    phase = "chunking",
                ))
                _ui.value = _ui.value.copy(
                    input = ex.name,
                    activeSessionId = session.id,
                    phase = "chunking",
                )
                refreshSessionPreviewsNow()
                runPreparedSession(session, summarizer, model)
            } catch (_: CancellationException) {
                val saved = sessionStore.save(session.copy(
                    status = SessionStatus.CANCELLED,
                    phase = _ui.value.phase,
                    progressCurrent = _ui.value.progressCurrent,
                    progressTotal = _ui.value.progressTotal,
                ))
                _ui.value = _ui.value.copy(
                    busy = false,
                    activeSessionId = saved.id,
                    resumeAvailable = saved.sourceText.isNotBlank(),
                    sessionNotice = if (saved.sourceText.isNotBlank())
                        "Stopped. The extracted file and completed sections were saved."
                    else "Stopped before the file finished loading.",
                    liveText = "",
                    error = null,
                )
                refreshSessionPreviewsNow()
                SummaryService.stop(appCtx)
                progressJob?.cancel()
                progressJob = null
                summaryJob = null
            } catch (error: Exception) {
                val message = friendlyFailure(error, "The summary failed.")
                val saved = sessionStore.save(session.copy(
                    status = SessionStatus.FAILED,
                    phase = _ui.value.phase,
                    progressCurrent = _ui.value.progressCurrent,
                    progressTotal = _ui.value.progressTotal,
                    error = message,
                ))
                _ui.value = _ui.value.copy(
                    busy = false,
                    error = message,
                    activeSessionId = saved.id,
                    resumeAvailable = saved.sourceText.isNotBlank(),
                    sessionNotice = if (saved.sourceText.isNotBlank())
                        "The file and completed sections were saved. You can resume."
                    else "",
                    liveText = "",
                )
                refreshSessionPreviewsNow()
                SummaryService.failed(appCtx, "Summary stopped: ${saved.title}")
                progressJob?.cancel()
                progressJob = null
                summaryJob = null
            }
        }
    }

    private fun onRateWait(seconds: Long) {
        _ui.value = _ui.value.copy(rateWaitSeconds = seconds)
        if (seconds > 0 && (seconds <= 5 || seconds % 10L == 0L)) {
            runCatching {
                SummaryService.update(appCtx, "Waiting for rate limit (${seconds}s)…")
            }
        }
    }

    private fun onNetworkStatus(message: String?) {
        val text = message.orEmpty()
        _ui.value = _ui.value.copy(networkStatus = text)
        if (text.isNotBlank()) {
            runCatching { SummaryService.update(appCtx, text) }
        }
    }

    fun groqDailyUsage(): Pair<Long, Long> {
        val u = rateLimiter.dailyUsage()
        return u.used to u.limit
    }

    fun resetDailyBudget() {
        rateLimiter.resetDaily()
        _ui.value = _ui.value.copy()
    }

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

    private val _ui: MutableStateFlow<UiState> = synchronized(PROCESS_LOCK) {
        processUi ?: MutableStateFlow(initialState()).also { processUi = it }
    }
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val shouldRestore = synchronized(PROCESS_LOCK) {
            if (restoreStarted) false else { restoreStarted = true; true }
        }
        if (shouldRestore) restoreRecentSessions()
    }

    private fun restoreRecentSessions() {
        PROCESS_SCOPE.launch {
            val previews = sessionStore.previews()
            _ui.value = _ui.value.copy(recentSessions = previews)
            if (_ui.value.result == null && !_ui.value.busy) {
                previews.firstOrNull()?.let { latest ->
                    sessionStore.load(latest.id)?.let {
                        applySession(it, restoredAtLaunch = true)
                    }
                }
            }
        }
    }

    private fun refreshSessionPreviews() {
        PROCESS_SCOPE.launch { refreshSessionPreviewsNow() }
    }

    private fun refreshSessionPreviewsNow() {
        _ui.value = _ui.value.copy(recentSessions = sessionStore.previews())
    }

    private fun applySession(session: SummarySession, restoredAtLaunch: Boolean = false) {
        val models = llm.availableModels()
        val chosenModel = models.firstOrNull { it.fileName == session.modelFileName }
            ?: _ui.value.model
        val canResume = session.sourceText.isNotBlank() && session.result == null
        val interrupted = session.status == SessionStatus.RUNNING && processSummaryJob?.isActive != true
        val provider = LlmProvider.fromId(session.cloudProviderId)
        val notice = when {
            session.result != null && restoredAtLaunch -> "Restored your most recent summary."
            interrupted -> "This summary was interrupted, but its completed sections were saved. Resume continues from the last checkpoint."
            session.status == SessionStatus.FAILED && canResume -> "The previous run stopped, but its completed sections were saved. You can resume without starting over."
            session.status == SessionStatus.CANCELLED && canResume -> "This session was cancelled. Completed sections are still saved and resumable."
            else -> ""
        }

        settings.engine = session.engineChoice
        if (session.engineChoice == EngineChoice.CLOUD) {
            settings.cloudProvider = provider
            if (session.cloudModel.isNotBlank()) {
                settings.setModelFor(provider, session.cloudModel)
            }
        }
        lastSourceText = session.sourceText
        lastSummarizer = if (session.sourceText.isNotBlank()) {
            makeSummarizer(session.engineChoice, provider)
        } else null

        _ui.value = _ui.value.copy(
            input = session.input,
            busy = processSummaryJob?.isActive == true && _ui.value.activeSessionId == session.id,
            phase = if (interrupted) session.phase else _ui.value.phase,
            progressCurrent = session.progressCurrent,
            progressTotal = session.progressTotal,
            liveText = "",
            rateWaitSeconds = 0,
            networkStatus = "",
            estimateText = "",
            qaHistory = session.qaHistory.map { QaPair(it.question, it.answer) },
            asking = false,
            askError = null,
            result = session.result,
            view = SummaryView.NORMAL,
            error = when {
                session.result != null -> null
                interrupted -> null
                session.status == SessionStatus.FAILED -> session.error
                else -> null
            },
            model = chosenModel,
            models = models,
            hasAnyModel = models.any { it.present },
            engineChoice = session.engineChoice,
            cloudProvider = provider,
            providerKeySet = settings.keyFor(provider)?.let { true } ?: false,
            providerModel = if (session.cloudModel.isNotBlank()) session.cloudModel else settings.modelFor(provider),
            activeSessionId = session.id,
            resumeAvailable = canResume && processSummaryJob?.isActive != true,
            sessionNotice = notice,
        )
    }

    fun openSession(id: String) {
        if (_ui.value.busy || _ui.value.asking) return
        PROCESS_SCOPE.launch {
            val session = sessionStore.load(id) ?: return@launch
            applySession(session)
        }
    }

    fun deleteSession(id: String) {
        if (_ui.value.activeSessionId == id && _ui.value.busy) return
        PROCESS_SCOPE.launch {
            sessionStore.delete(id)
            if (_ui.value.activeSessionId == id) {
                lastSourceText = ""
                lastSummarizer = null
                _ui.value = _ui.value.copy(
                    activeSessionId = null,
                    result = null,
                    qaHistory = emptyList(),
                    resumeAvailable = false,
                    sessionNotice = "",
                    error = null,
                )
            }
            refreshSessionPreviewsNow()
        }
    }

    fun clearAllSessions() {
        if (_ui.value.busy || _ui.value.asking) return
        PROCESS_SCOPE.launch {
            sessionStore.clearAll()
            lastSourceText = ""
            lastSummarizer = null
            _ui.value = _ui.value.copy(
                activeSessionId = null,
                result = null,
                qaHistory = emptyList(),
                resumeAvailable = false,
                sessionNotice = "",
                error = null,
                recentSessions = emptyList(),
            )
        }
    }

    // ── engine / provider actions ───────────────────────────────────────────────

    fun setEngine(choice: EngineChoice) {
        settings.engine = choice
        _ui.value = _ui.value.copy(engineChoice = choice)
    }

    fun setCloudProvider(provider: LlmProvider) {
        settings.cloudProvider = provider
        _ui.value = _ui.value.copy(
            cloudProvider = provider,
            providerKeySet = settings.hasKeyFor(provider),
            providerModel = settings.modelFor(provider),
        )
    }

    fun setProviderKey(key: String, provider: LlmProvider) {
        settings.setKeyFor(provider, key)
        _ui.value = _ui.value.copy(providerKeySet = settings.hasKeyFor(provider))
    }

    fun setProviderModel(model: String, provider: LlmProvider) {
        settings.setModelFor(provider, model)
        _ui.value = _ui.value.copy(providerModel = model)
    }

    fun setCustomBaseUrl(url: String, provider: LlmProvider) {
        settings.setCustomBaseUrlFor(provider, url)
    }

    fun setTemperature(value: Float, provider: LlmProvider) {
        settings.setTemperatureFor(provider, value)
    }

    fun maskedKey(provider: LlmProvider): String =
        SecureStorage.masked(settings.keyFor(provider))

    // ── summarize flow ─────────────────────────────────────────────────────────

    fun onInput(text: String) {
        _ui.value = _ui.value.copy(input = text)
    }

    fun summarize() {
        val input = _ui.value.input.trim()
        if (input.isEmpty() || _ui.value.busy) return
        val state = _ui.value
        val model = state.model
        if (!validateEngine(state.engineChoice, model)) return
        val summarizer = makeSummarizer(state.engineChoice, state.cloudProvider)
        attachProgress(summarizer)

        var session = sessionStore.save(SummarySession(
            input = input,
            title = "Summarizing…",
            kind = "Source",
            engineChoice = state.engineChoice,
            modelFileName = model.fileName,
            cloudProviderId = state.cloudProvider.id,
            cloudModel = state.providerModel,
            phase = "fetching",
        ))
        prepareUiForSession(session)
        startKeepAlive("Summarizing…")

        summaryJob?.cancel()
        summaryJob = PROCESS_SCOPE.launch {
            try {
                val fetched = fetcher.fetch(input)
                if (fetched.error != null) throw IllegalStateException(fetched.error)
                session = sessionStore.save(session.copy(
                    input = fetched.title.ifBlank { input },
                    title = fetched.title.ifBlank { "Summary" },
                    kind = fetched.kind,
                    sourceText = fetched.text,
                    approxMinutes = fetched.approxMinutes,
                    phase = "chunking",
                ))
                _ui.value = _ui.value.copy(
                    input = fetched.title.ifBlank { input },
                    activeSessionId = session.id,
                    phase = "chunking",
                )
                refreshSessionPreviewsNow()
                runPreparedSession(session, summarizer, model)
            } catch (_: CancellationException) {
                handleCancellation(session)
            } catch (error: Exception) {
                handleFailure(session, error)
            }
        }
    }

    private suspend fun runPreparedSession(
        session: SummarySession,
        summarizer: SummarizeEngine,
        model: ModelInfo,
    ) {
        val result = summarizer.run(
            model = model,
            text = session.sourceText,
            title = session.title,
            kind = session.kind,
            approxMinutes = session.approxMinutes,
            initialCheckpoint = session.checkpoint,
            onCheckpoint = { cp ->
                sessionStore.save(session.copy(checkpoint = cp))
            },
        )
        val saved = sessionStore.save(session.copy(
            status = SessionStatus.COMPLETE,
            result = result,
            phase = "done",
        ))
        _ui.value = _ui.value.copy(
            busy = false,
            result = result,
            activeSessionId = saved.id,
            resumeAvailable = false,
            sessionNotice = "Summary complete.",
            liveText = "",
            error = null,
        )
        refreshSessionPreviewsNow()
        SummaryService.complete(appCtx, "Summary of ${result.title} is ready")
        progressJob?.cancel()
        progressJob = null
        summaryJob = null
    }

    private suspend fun handleCancellation(session: SummarySession) {
        val saved = sessionStore.save(session.copy(
            status = SessionStatus.CANCELLED,
            phase = _ui.value.phase,
            progressCurrent = _ui.value.progressCurrent,
            progressTotal = _ui.value.progressTotal,
        ))
        _ui.value = _ui.value.copy(
            busy = false,
            activeSessionId = saved.id,
            resumeAvailable = saved.sourceText.isNotBlank(),
            sessionNotice = if (saved.sourceText.isNotBlank())
                "Stopped. Completed sections were saved."
            else "Stopped before finishing.",
            liveText = "",
            error = null,
        )
        refreshSessionPreviewsNow()
        SummaryService.stop(appCtx)
        progressJob?.cancel()
        progressJob = null
        summaryJob = null
    }

    private suspend fun handleFailure(session: SummarySession, error: Exception) {
        val message = friendlyFailure(error, "The summary failed.")
        val saved = sessionStore.save(session.copy(
            status = SessionStatus.FAILED,
            phase = _ui.value.phase,
            progressCurrent = _ui.value.progressCurrent,
            progressTotal = _ui.value.progressTotal,
            error = message,
        ))
        _ui.value = _ui.value.copy(
            busy = false,
            error = message,
            activeSessionId = saved.id,
            resumeAvailable = saved.sourceText.isNotBlank(),
            sessionNotice = if (saved.sourceText.isNotBlank())
                "Completed sections were saved. You can resume."
            else "",
            liveText = "",
        )
        refreshSessionPreviewsNow()
        SummaryService.failed(appCtx, "Summary stopped: ${saved.title}")
        progressJob?.cancel()
        progressJob = null
        summaryJob = null
    }

    private fun validateEngine(engine: EngineChoice, model: ModelInfo): Boolean {
        return when (engine) {
            EngineChoice.ON_DEVICE -> {
                if (!llm.isModelPresent(model)) {
                    _ui.value = _ui.value.copy(
                        error = "No on-device model downloaded. Get one in Models."
                    )
                    return false
                }
                true
            }
            EngineChoice.CLOUD -> {
                val provider = _ui.value.cloudProvider
                if (provider.needsKey && !settings.hasKeyFor(provider)) {
                    _ui.value = _ui.value.copy(
                        error = "No ${provider.displayName} API key. Add one in Settings → Providers."
                    )
                    return false
                }
                true
            }
        }
    }

    private fun attachProgress(summarizer: SummarizeEngine) {
        progressJob?.cancel()
        progressJob = PROCESS_SCOPE.launch {
            summarizer.progress.collect { p ->
                _ui.value = _ui.value.copy(
                    busy = p.phase != "done",
                    phase = p.phase,
                    progressCurrent = p.current,
                    progressTotal = p.total,
                    liveText = p.live,
                )
            }
        }
    }

    private fun prepareUiForSession(session: SummarySession) {
        _ui.value = _ui.value.copy(
            busy = true,
            error = null,
            result = null,
            qaHistory = emptyList(),
            liveText = "",
            phase = "fetching",
            progressCurrent = 0,
            progressTotal = 0,
            activeSessionId = session.id,
            resumeAvailable = false,
            sessionNotice = "",
        )
    }

    fun resumeSession(id: String? = null) {
        PROCESS_SCOPE.launch {
            val sessionId = id ?: _ui.value.activeSessionId ?: return@launch
            val session = sessionStore.load(sessionId) ?: return@launch
            val model = _ui.value.models.firstOrNull { it.fileName == session.modelFileName }
                ?: _ui.value.model
            val provider = LlmProvider.fromId(session.cloudProviderId)
            val summarizer = makeSummarizer(session.engineChoice, provider)
            attachProgress(summarizer)
            _ui.value = _ui.value.copy(
                busy = true, error = null, liveText = "", sessionNotice = "",
            )
            runPreparedSession(session, summarizer, model)
        }
    }

    fun cancel() {
        summaryJob?.cancel()
        askJob?.cancel()
    }

    private fun friendlyFailure(error: Exception, fallback: String): String {
        val msg = error.message?.trim()
        return when {
            msg.isNullOrBlank() -> fallback
            else -> msg
        }
    }

    private fun initialState(): UiState {
        val models = llm.availableModels()
        val hasAny = models.any { it.present }
        val engine = settings.engine
        val provider = settings.cloudProvider
        return UiState(
            models = models,
            hasAnyModel = hasAny,
            model = models.firstOrNull { it.present } ?: BUNDLED_MODELS.first(),
            engineChoice = engine,
            cloudProvider = provider,
            providerKeySet = settings.hasKeyFor(provider),
            providerModel = settings.modelFor(provider),
        )
    }

    companion object {
        private val PROCESS_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val PROCESS_LOCK = Any()
        @Volatile private var processUi: MutableStateFlow<UiState>? = null
        @Volatile private var processSummaryJob: Job? = null
        @Volatile private var processAskJob: Job? = null
        @Volatile private var processProgressJob: Job? = null
        @Volatile private var processLastSourceText: String = ""
        @Volatile private var processLastSummarizer: SummarizeEngine? = null
        @Volatile private var restoreStarted = false
    }
}
