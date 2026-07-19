package ai.sonario.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.sonario.app.data.Settings
import ai.sonario.app.data.SessionStore
import ai.sonario.app.llm.EngineChoice
import ai.sonario.app.llm.CloudEngine
import ai.sonario.app.llm.InferenceEngine
import ai.sonario.app.llm.LlmEngine
import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.ModelInfo
import ai.sonario.app.llm.RateLimiter
import ai.sonario.app.llm.SecureStorage
import ai.sonario.app.source.SourceFetcher
import ai.sonario.app.summarize.SummarizeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the summary screen.
 *
 *   1. Fetches source material (URL / file / pasted text) via [SourceFetcher].
 *   2. Summarises it via [SummarizeEngine] using the active [InferenceEngine].
 *   3. Stores / restores sessions via [SessionStore].
 *
 * The active engine is created lazily from [Settings] so changing provider
 * or model takes effect without restarting the app.
 */
class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    val store = SessionStore(app)
    val fetcher = SourceFetcher()
    private val rateLimiter = RateLimiter(app)

    // ── UI state ────────────────────────────────────────────────────────────────

    var isRunning by mutableStateOf(false)
        private set
    var status by mutableStateOf("Ready")
        private set
    var sourceTitle by mutableStateOf("")
        private set
    var sourceText by mutableStateOf("")
        private set
    var summary by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // Cloud-provider state shown in the UI.
    var activeProvider by mutableStateOf(settings.cloudProvider)
        private set
    var providerModel by mutableStateOf(settings.modelFor(settings.cloudProvider))
        private set
    val isOnDevice: Boolean get() = settings.engine == EngineChoice.ON_DEVICE
    val isCloud: Boolean get() = settings.engine == EngineChoice.CLOUD

    private var activeEngine: InferenceEngine? = null
    private var summariseJob: Job? = null
    private var loadedSessionId: String? = null

    /** The currently active engine, rebuilt lazily. */
    fun currentEngine(): InferenceEngine {
        val cached = activeEngine
        return if (cached != null) cached else {
            val built = buildEngine()
            activeEngine = built
            built
        }
    }

    private fun buildEngine(): InferenceEngine = when (settings.engine) {
        EngineChoice.ON_DEVICE -> LlmEngine.get(getApplication())
        EngineChoice.CLOUD -> {
            activeProvider = settings.cloudProvider
            providerModel = settings.modelFor(activeProvider)
            CloudEngine(
                context = getApplication(),
                configProvider = { settings.configFor(activeProvider) },
                apiKeyProvider = { settings.keyFor(activeProvider) },
                rateLimiter = rateLimiter,
            )
        }
    }

    /** Drop the cached engine so the next call to [currentEngine] rebuilds it. */
    fun rebuildEngine() {
        activeEngine = null
    }

    // ── source loading ──────────────────────────────────────────────────────────

    fun loadFromUrl(url: String) {
        updateStatus("Fetching source…")
        resetForNewSource()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = fetcher.fromUrl(url)
                sourceText = content.text
                sourceTitle = content.title
                summarise()
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    fun loadFromFile(file: File, mime: String) {
        updateStatus("Reading file…")
        resetForNewSource()
        sourceTitle = file.name
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sourceText = fetcher.fromFile(file, mime)
                summarise()
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    fun loadFromText(text: String) {
        sourceText = text
        sourceTitle = "Pasted text"
        error = null
        summarise()
    }

    private fun resetForNewSource() {
        sourceText = ""
        sourceTitle = ""
        summary = ""
        error = null
    }

    // ── summarisation ───────────────────────────────────────────────────────────

    fun summarise() {
        val text = sourceText
        if (text.isBlank()) return

        summariseJob?.cancel()

        if (settings.engine == EngineChoice.CLOUD) {
            val est = rateLimiter.estimate(text)
            if (est.exceedsDaily) {
                error = "Daily token limit reached. Try on-device mode or wait until tomorrow."
                isRunning = false
                return
            }
        }

        updateStatus("Starting summary…")
        isRunning = true
        summary = ""
        error = null

        val engine = currentEngine()
        val summariser = SummarizeEngine(engine, bigContext = settings.engine == EngineChoice.CLOUD)

        // Collect progress updates (cancellable so cancel() stops them).
        val progressJob = viewModelScope.launch(Dispatchers.Main) {
            summariser.progress.cancellable().collect { p ->
                status = p.phase.replaceFirstChar { it.uppercase() }
                // Surface partial streaming text when present.
                if (p.live.isNotBlank()) summary = p.live
            }
        }

        summariseJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val model = if (settings.engine == EngineChoice.CLOUD) {
                    ModelInfo(
                        label = providerModel,
                        fileName = "",
                        sizeMb = 0,
                        contextTokens = 131072,
                        note = "",
                        downloadUrl = "",
                    )
                } else {
                    // On-device: pick the first present model as a stub ensureReady arg.
                    LlmEngine.get(getApplication()).availableModels().firstOrNull { it.present }
                        ?: ModelInfo("", "", 0, 4096, "", "")
                }
                val result = summariser.run(
                    model = model,
                    text = text,
                    title = sourceTitle,
                    kind = "article",
                    approxMinutes = null,
                )
                summary = result.normal
                loadedSessionId = store.save(
                    SessionStore.Entry(
                        id = loadedSessionId ?: "s_${System.currentTimeMillis()}",
                        title = sourceTitle,
                        sourceText = text,
                        summary = result.normal,
                        timestamp = System.currentTimeMillis(),
                    )
                )
            } catch (e: Exception) {
                handleFailure(e)
            } finally {
                progressJob.cancel()
                isRunning = false
                if (status.startsWith("synthesiz", ignoreCase = true) ||
                    status.startsWith("condens", ignoreCase = true)
                ) status = "Done"
            }
        }
    }

    fun cancel() {
        summariseJob?.cancel()
        summariseJob = null
        isRunning = false
        status = "Cancelled"
    }

    fun retry() {
        error = null
        summarise()
    }

    fun clearError() { error = null }

    private fun handleFailure(e: Throwable) {
        val msg = when (e) {
            is java.net.UnknownHostException ->
                "No network connection. Check your internet or use on-device mode."
            is java.net.SocketTimeoutException ->
                "Request timed out. Try a shorter source or switch providers."
            is javax.net.ssl.SSLException ->
                "Secure connection failed. Your network may be intercepting traffic."
            is ai.sonario.app.llm.RateLimitException ->
                "Rate-limited by ${activeProvider.displayName}. ${e.message}"
            is ai.sonario.app.llm.AuthenticationException ->
                "Authentication failed. Check your ${activeProvider.displayName} API key."
            else -> e.message ?: "Unknown error: ${e.javaClass.simpleName}"
        }
        error = msg
        isRunning = false
    }

    private fun updateStatus(msg: String) {
        status = msg
    }

    // ── provider switching ────────────────────────────────────────────────────

    fun switchProvider(provider: LlmProvider) {
        settings.cloudProvider = provider
        activeProvider = provider
        providerModel = settings.modelFor(provider)
        rebuildEngine()
    }

    fun setEngineChoice(choice: EngineChoice) {
        settings.engine = choice
        rebuildEngine()
    }

    fun hasKeyFor(provider: LlmProvider): Boolean = settings.hasKeyFor(provider)

    /** Masked form of the current key for display. */
    fun maskedKeyFor(provider: LlmProvider): String =
        SecureStorage.masked(settings.keyFor(provider))

    // ── session management ──────────────────────────────────────────────────────

    fun loadSession(id: String) {
        val entry = store.byId(id) ?: return
        loadedSessionId = entry.id
        sourceText = entry.sourceText
        sourceTitle = entry.title
        summary = entry.summary
        isRunning = false
        error = null
    }

    fun sessions(): List<SessionStore.Entry> = store.recent()
}
