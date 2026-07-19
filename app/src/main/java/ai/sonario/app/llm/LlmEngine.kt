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
 * around llama.cpp - no NDK or native build is required by this project).
 *
 * One model stays loaded at a time and is reused for every summarization stage.
 * The model files live only in Sonario's private app storage, so Android removes
 * them automatically when the app is uninstalled.
 */
class LlmEngine private constructor(private val appContext: Context) : InferenceEngine {

    @Volatile var loadedModel: String? = null
        private set

    @Volatile private var legacyCleanupDone = false

    /** InferenceEngine entry point: for on-device this loads the GGUF. */
    override suspend fun ensureReady(model: ModelInfo) = ensureLoaded(model)

    /**
     * Where downloaded GGUF models live. This is Context.filesDir, which is
     * app-specific internal storage and is deleted by Android on uninstall.
     */
    fun modelsDir(): File {
        val dir = File(appContext.filesDir, "models").apply { mkdirs() }
        cleanupLegacyModelsOnce(dir)
        return dir
    }

    fun availableModels(): List<ModelInfo> = BUNDLED_MODELS.map { model ->
        model.copy(present = File(modelsDir(), model.fileName).exists())
    }

    fun isModelPresent(model: ModelInfo): Boolean =
        File(modelsDir(), model.fileName).exists()

    /**
     * Remove the three superseded model downloads from Sonario 1.4 and earlier.
     * They are no longer offered, and silently leaving several gigabytes of
     * unreachable files behind would waste storage after an app update.
     */
    private fun cleanupLegacyModelsOnce(dir: File) {
        if (legacyCleanupDone) return
        synchronized(this) {
            if (legacyCleanupDone) return
            LEGACY_MODEL_FILES.forEach { name ->
                File(dir, name).delete()
                File(dir, "$name.part").delete()
            }
            legacyCleanupDone = true
        }
    }

    /**
     * Load a GGUF into llama.cpp. Idempotent and intentionally performed off the
     * main thread. Memory mapping keeps the resident-RAM cost below copying the
     * complete multi-gigabyte file into memory.
     */
    suspend fun ensureLoaded(model: ModelInfo) = withContext(Dispatchers.Default) {
        if (loadedModel == model.fileName) return@withContext
        val path = File(modelsDir(), model.fileName)
        require(path.exists()) { "Model file missing: ${model.fileName}" }

        if (loadedModel != null) {
            runCatching { LlamaBridge.shutdown() }
            loadedModel = null
        }

        LlamaBridge.updateGenerateParams(
            temperature = 0.35f,
            maxTokens = 1024,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = model.contextTokens,
            numThreads = pickThreads(),
            useMmap = true,
            flashAttention = true,
            batchSize = 256,
            gpuLayers = -1,
        )

        val ok = LlamaBridge.initGenerateModel(path.absolutePath)
        require(ok) {
            "Sonario could not load ${model.label}. The download may be incomplete " +
                "or this phone may not have enough free memory."
        }
        loadedModel = model.fileName
    }

    fun unload() {
        runCatching { LlamaBridge.shutdown() }
        loadedModel = null
    }

    /** Stream one completion while keeping generation work off the UI thread. */
    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> =
        callbackFlow {
            runCatching {
                LlamaBridge.updateGenerateParams(
                    temperature = 0.35f,
                    maxTokens = maxTokens,
                    topP = 0.9f,
                    topK = 40,
                    repeatPenalty = 1.1f,
                    contextLength = currentContext(),
                    numThreads = pickThreads(),
                    useMmap = true,
                    flashAttention = true,
                    batchSize = 256,
                    gpuLayers = -1,
                )
            }

            val job = launch(Dispatchers.Default) {
                try {
                    LlamaBridge.generateWithContextStream(
                        system = system,
                        context = "",
                        user = user,
                        onDelta = { token -> trySend(token) },
                        onDone = { close() },
                        onError = { message -> close(RuntimeException(message)) },
                    )
                } catch (error: Throwable) {
                    close(error)
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
        return (cores - 1).coerceIn(4, 8)
    }

    companion object {
        private val LEGACY_MODEL_FILES = listOf(
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "llama-3.2-3b-instruct-q4_k_m.gguf",
            "phi-3.5-mini-instruct-q4_k_m.gguf",
        )

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
 * Mobile-oriented local models. Q4_K_M is used as the quality/size compromise;
 * context is deliberately capped at 4K to avoid an oversized KV cache on phones.
 */
val BUNDLED_MODELS = listOf(
    ModelInfo(
        label = "Qwen3 4B Instruct 2507",
        fileName = "qwen3-4b-instruct-2507-q4_k_m.gguf",
        sizeMb = 2500,
        contextTokens = 4096,
        note = "Best overall. Strongest summaries, comprehension, and follow-up answers; larger and slower than LFM2.",
        downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/" +
            "resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf?download=true",
    ),
    ModelInfo(
        label = "Gemma 3n E4B Instruct",
        fileName = "gemma-3n-e4b-it-q4_k_m.gguf",
        sizeMb = 4240,
        contextTokens = 4096,
        note = "Best for nuanced long-form summaries and writing. Mobile-designed, but the largest download and slowest option.",
        downloadUrl = "https://huggingface.co/second-state/gemma-3n-E4B-it-GGUF/" +
            "resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf?download=true",
    ),
    ModelInfo(
        label = "LFM2 2.6B",
        fileName = "lfm2-2.6b-q4_k_m.gguf",
        sizeMb = 1560,
        contextTokens = 4096,
        note = "Fastest and smallest. Good for quick summaries and extraction; less capable with difficult or subtle material.",
        downloadUrl = "https://huggingface.co/LiquidAI/LFM2-2.6B-GGUF/" +
            "resolve/main/LFM2-2.6B-Q4_K_M.gguf?download=true",
    ),
)
