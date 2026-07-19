package ai.sonario.app.ui

import android.app.Application
import android.content.Context
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
import ai.sonario.app.llm.ProviderConfig
import ai.sonario.app.llm.RateLimiter
import ai.sonario.app.llm.SecureStorage
import ai.sonario.app.source.SourceContent
import ai.sonario.app.source.SourceFetcher
import ai.sonario.app.summarize.SummarizeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the summary screen. Owns three responsibilities:
 *   1. Fetching source material (URL/file/text) via [SourceFetcher].
 *   2. Summarising it via [SummarizeEngine] using the active [InferenceEngine].
 *   3. Storing / restoring the session via [SessionStore].
 *
 * The active engine is created lazily from [Settings] so changing the provider
 * or model takes effect without restarting the app.
 */
class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    val settings = Settings(app)
    val store = SessionStore(app)
    val fetcher = SourceFetcher()
    private val rateLimiter = RateLimiter(app)

    var state by mutableStateOf(SummaryState())
        private set

    // Read-only state exposed for UI.
    var activeProvider by mutableStateOf(settings.cloudProvider)
        private set
    var providerConfig by mutableStateOf(settings.configFor(settings.cloudProvider))
        private set

    private var activeEngine: InferenceEngine? = null
    private var summariseJob: Job? = null
    private var loadedSessionId: String? = null

    // Publicly-readable copies of the state machine values
    val isOnDevice: Boolean get() = settings.engine == EngineChoice.ON_DEVICE
    val isCloud: Boolean get() = settings.engine == EngineChoice.CLOUD

    /** The currently active engine, rebuilt lazily. */
    fun currentEngine(): InferenceEngine {
        val cached = activeEngine
        return if (cached != null) cached else {
            val built = buildEngine()
            activeEngine = built
            built
        }
    }

    private fun buildEngine(): InferenceEngine {
        return when (settings.engine) {
            EngineChoice.ON_DEVICE -> LlmEngine()
            EngineChoice.CLOUD -> {
                activeProvider = settings.cloudProvider
                providerConfig = settings.configFor(activeProvider)
                val key = settings.keyFor(activeProvider)
                CloudEngine(
                    config = providerConfig,
                    apiKey = key,
                    rateLimiter = rateLimiter,
                )
            }
        }
    }

    /** Drop the cached engine so the next call to [currentEngine] rebuilds it. */
    fun rebuildEngine() {
        activeEngine = null
    }

    // ── source loading ──────────────────────────────────────────────────────────

    fun loadFromUrl(url: String) {
        updateStatus("Fetching source…")
        state = state.copy(sourceText = "", sourceTitle = "", summary = "", error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = fetcher.fromUrl(url)
                state = state.copy(sourceText = content.text, sourceTitle = content.title)
                summarise()
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    fun loadFromFile(file: File, mime: String) {
        updateStatus("Reading file…")
        state = state.copy(sourceText = "", sourceTitle = file.name, summary = "", error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = fetcher.fromFile(file, mime)
                state = state.copy(sourceText = text)
                summarise()
            } catch (e: Exception) {
                handleFailure(e)
            }
        }
    }

    fun loadFromText(text: String) {
        state = state.copy(sourceText = text, sourceTitle = "Pasted text", summary = "", error = null)
        summarise()
    }

    // ── summarisation ───────────────────────────────────────────────────────────

    fun summarise() {
        val text = state.sourceText
        if (text.isBlank()) return

        // Cancel any in-flight run
        summariseJob?.cancel()

        if (settings.engine == EngineChoice.CLOUD) {
            val est = rateLimiter.estimate(text)
            if (est.exceedsDaily) {
                state = state.copy(
                    error = "Daily token limit reached (${est.dailyUsed}/${est.dailyLimit}). " +
                        "Try on-device mode or wait until tomorrow.",
                    isRunning = false,
                )
                return
            }
        }

        updateStatus("Starting summary…")
        state = state.copy(isRunning = true, summary = "", error = null)

        summariseJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = currentEngine()
                val summariser = SummarizeEngine(engine)
                summariser.summarise(text)
                    .onEach { event -> applyEvent(event) }
                    .onCompletion { e -> if (e != null) handleFailure(e) }
                    .collect()

                // Persist to session store once we have a full summary
                if (state.summary.isNotBlank()) {
                    store.save(
                        SessionStore.Entry(
                            id = loadedSessionId ?: "s_${System.currentTimeMillis()}",
                            title = state.sourceTitle,
                            sourceText = text,
                            summary = state.summary,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    loadedSessionId = store.latest()?.id
                }
            } catch (e: Exception) {
                handleFailure(e)
            } finally {
                if (state.isRunning) {
                    state = state.copy(isRunning = false)
                }
            }
        }
    }

    fun cancel() {
        summariseJob?.cancel()
        summariseJob = null
        state = state.copy(isRunning = false, status = "Cancelled")
    }

    fun retry() {
        state = state.copy(error = null)
        summarise()
    }

    fun clearError() { state = state.copy(error = null) }

    private fun applyEvent(event: SummarizeEngine.Event) {
        when (event) {
            is SummarizeEngine.Event.Progress -> updateStatus(event.message)
            is SummarizeEngine.Event.Done -> {
                state = state.copy(
                    summary = event.summary,
                    isRunning = false,
                    status = "Done",
                )
            }
        }
    }

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
        state = state.copy(error = msg, isRunning = false)
    }

    private fun updateStatus(msg: String) {
        state = state.copy(status = msg)
    }

    // ── provider switching ────────────────────────────────────────────────────

    fun switchProvider(provider: LlmProvider) {
        settings.cloudProvider = provider
        activeProvider = provider
        providerConfig = settings.configFor(provider)
        rebuildEngine()
    }

    fun setEngineChoice(choice: EngineChoice) {
        settings.engine = choice
        rebuildEngine()
    }

    fun hasKeyFor(provider: LlmProvider): Boolean =
        settings.hasKeyFor(provider)

    /** Masked form of the current key for display. */
    fun maskedKeyFor(provider: LlmProvider): String =
        SecureStorage.masked(settings.keyFor(provider))

    // ── session management ──────────────────────────────────────────────────────

    fun loadSession(id: String) {
        val entry = store.byId(id) ?: return
        loadedSessionId = entry.id
        state = state.copy(
            sourceText = entry.sourceText,
            sourceTitle = entry.title,
            summary = entry.summary,
            isRunning = false,
            error = null,
        )
    }

    fun sessions(): List<SessionStore.Entry> = store.recent()

    // ── state data class ────────────────────────────────────────────────────────

    data class SummaryState(
        val isRunning: Boolean = false,
        val status: String = "Ready",
        val sourceTitle: String = "",
        val sourceText: String = "",
        val summary: String = "",
        val error: String? = null,
    )
}

/** Custom exception types the UI can match on for friendly messages. */
class RateLimitException(message: String) : Exception(message)
class AuthenticationException(message: String) : Exception(message)
