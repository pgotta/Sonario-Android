package ai.sonario.app.llm

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for a summarization backend. Three implementations exist:
 *
 *   [LlmEngine]   - on-device via llama.cpp/Llamatik (private, but CPU-slow).
 *   [CloudEngine] - any cloud provider (OpenAI, Anthropic, Groq, Ollama, custom).
 *
 * The summarize pipeline talks only to this interface, so adding a new
 * provider requires no changes outside the llm package.
 */
interface InferenceEngine {
    /**
     * Generate a single completion for [prompt]. The returned [Flow] emits
     * incremental text chunks as they arrive from the provider (true streaming
     * for cloud, simulated chunking for on-device). The flow completes when
     * the full response has been emitted.
     */
    fun complete(prompt: String): Flow<String>

    /**
     * Free native / network resources. Called when the engine is rebuilt
     * (provider change) or the ViewModel is cleared.
     */
    fun close() {}
}

/**
 * Thrown when the provider returns 401 / 403. The UI catches this to show
 * a friendly "check your API key" message.
 */
class AuthenticationException(message: String) : Exception(message)

/**
 * Thrown when the provider returns 429 or the local rate-limiter predicts
 * the daily cap would be exceeded. The UI catches this to show a friendly
 * cooldown message.
 */
class RateLimitException(message: String) : Exception(message)
