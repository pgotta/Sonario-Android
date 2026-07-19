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
     * Verify the engine is ready. For on-device this loads the GGUF file;
     * for cloud this validates that an API key is present (if needed).
     * Throws [IllegalStateException] with a user-friendly message if not ready.
     */
    suspend fun ensureReady(model: ModelInfo)

    /**
     * Generate a single completion given a [system] steering prompt and a
     * [user] payload prompt, capped at [maxTokens] output tokens.
     *
     * The returned [Flow] emits incremental text chunks as they arrive
     * (true streaming for cloud, simulated chunking for on-device). The
     * flow completes when the full response has been emitted.
     */
    fun stream(system: String, user: String, maxTokens: Int): Flow<String>
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
