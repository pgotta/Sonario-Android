package ai.sonario.app.llm

import android.content.Context
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device LLM engine, backed by Llamatik (a Maven-published Kotlin wrapper
 * around llama.cpp - no NDK, no native build on your side).
 *
 * This is the mobile analogue of Sonario desktop's providers.py. Desktop talked
 * to Ollama over HTTP; here the model runs in-process. One model stays loaded at
 * a time, reused for every stage (condense + synthesis), because 8GB-class
 * phones don't have headroom to hold two and reloading mid-job stalls badly.
 *
 * The whole app is written against this small interface, so swapping inference
 * backends later means editing only this file.
 */
class LlmEngine private constructor(private val appContext: Context) : InferenceEngine {

    @Volatile var loadedModel: String? = null
        private set

    /** InferenceEngine entry point: for on-device this loads the GGUF. */
    override suspend fun ensureReady(model: ModelInfo) = ensureLoaded(model)

    /** Where downloaded GGUF models live. */
    fun modelsDir(): File =
        File(appContext.filesDir, "models").apply { mkdirs() }

    fun availableModels(): List<ModelInfo> = BUNDLED_MODELS.map { m ->
        m.copy(present = File(modelsDir(), m.fileName).exists())
    }

    fun isModelPresent(model: ModelInfo): Boolean =
        File(modelsDir(), model.fileName).exists()

    /**
     * Load a GGUF into llama.cpp. Idempotent. Heavy - call off the main thread
     * (the summarize pipeline already does).
     */
    suspend fun ensureLoaded(model: ModelInfo) = withContext(Dispatchers.Default) {
        if (loadedModel == model.fileName) return@withContext
        val path = File(modelsDir(), model.fileName)
        require(path.exists()) { "Model file missing: ${model.fileName}" }

        if (loadedModel != null) {
            // switching models: free the old context first
            runCatching { LlamaBridge.shutdown() }
            loadedModel = null
        }

        // Load-time params must be set before initGenerateModel.
        LlamaBridge.updateGenerateParams(
            temperature = 0.5f,        // summaries want low randomness
            maxTokens = 1024,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = model.contextTokens,
            numThreads = pickThreads(),
            useMmap = true,            // memory-map weights; lighter on RAM
            flashAttention = false,
            batchSize = 256,
            gpuLayers = -1,            // best-effort GPU offload; falls back to CPU if unsupported
        )

        val ok = LlamaBridge.initGenerateModel(path.absolutePath)
        require(ok) { "Llamatik failed to load ${model.fileName}" }
        loadedModel = model.fileName
    }

    fun unload() {
        runCatching { LlamaBridge.shutdown() }
        loadedModel = null
    }

    /**
     * Stream a completion. Mirrors providers.Provider.chat(system, user): the
     * system prompt steers, the user prompt carries the source. Emits token by
     * token so the UI shows the summary forming live.
     *
     * Uses Llamatik's generateWithContextStream with an empty context block;
     * Sonario folds everything into system + user, matching the desktop app.
     */
    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> =
        callbackFlow {
            // maxTokens can change per call without a reload.
            runCatching {
                LlamaBridge.updateGenerateParams(
                    temperature = 0.5f, maxTokens = maxTokens, topP = 0.95f,
                    topK = 40, repeatPenalty = 1.1f,
                    contextLength = currentContext(), numThreads = pickThreads(),
                    useMmap = true, flashAttention = false, batchSize = 256,
                    gpuLayers = -1,
                )
            }
            // Run generation in its own job. Llamatik's streaming call blocks the
            // calling thread until generation completes (it drives the callbacks
            // inline), so launching it here lets awaitClose register first and
            // keeps this dispatcher free to deliver cancellation.
            val job = launch(Dispatchers.Default) {
                try {
                    LlamaBridge.generateWithContextStream(
                        system = system,
                        context = "",
                        user = user,
                        onDelta = { token -> trySend(token) },
                        onDone = { close() },
                        onError = { msg -> close(RuntimeException(msg)) },
                    )
                } catch (e: Throwable) {
                    close(e)
                }
            }
            awaitClose {
                runCatching { LlamaBridge.nativeCancelGenerate() }
                job.cancel()
            }
        }.flowOn(Dispatchers.Default)

    private fun currentContext(): Int =
        BUNDLED_MODELS.firstOrNull { it.fileName == loadedModel }?.contextTokens ?: 4096

    private fun pickThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Use the big cores. Modern flagships have 8 cores; leaving one free
        // keeps the UI responsive while giving inference most of the CPU.
        return (cores - 1).coerceIn(4, 8)
    }

    companion object {
        @Volatile private var INSTANCE: LlmEngine? = null
        fun get(context: Context): LlmEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmEngine(context.applicationContext).also { INSTANCE = it }
            }
    }
}

data class ModelInfo(
    val label: String,
    val fileName: String,
    val sizeMb: Int,
    val contextTokens: Int,
    val note: String,
    val downloadUrl: String,
    val present: Boolean = false,
)

/**
 * Models that suit an 8GB-class phone (Snapdragon 8 Elite, ~12-16GB RAM). All
 * are small enough to load fully into RAM and run at usable speed via llama.cpp
 * with Q4_K_M quantization. The download URLs point at public GGUF repos on
 * Hugging Face. The first-run picker downloads exactly one.
 */
val BUNDLED_MODELS = listOf(
    ModelInfo(
        label = "Qwen2.5 1.5B Instruct",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeMb = 1100,
        contextTokens = 4096,
        note = "Fast, good summaries. The safe default for any 8GB+ phone.",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/" +
            "resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
    ),
    ModelInfo(
        label = "Llama 3.2 3B Instruct",
        fileName = "llama-3.2-3b-instruct-q4_k_m.gguf",
        sizeMb = 2000,
        contextTokens = 4096,
        note = "Stronger synthesis, comfortable on the S26 Ultra. Slower.",
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/" +
            "resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true",
    ),
    ModelInfo(
        label = "Phi-3.5 Mini Instruct",
        fileName = "phi-3.5-mini-instruct-q4_k_m.gguf",
        sizeMb = 2300,
        contextTokens = 4096,
        note = "Roomy context comfort for long transcripts.",
        downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/" +
            "resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true",
    ),
)
