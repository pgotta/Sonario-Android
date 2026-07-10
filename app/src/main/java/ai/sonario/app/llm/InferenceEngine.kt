package ai.sonario.app.llm

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for a summarization backend. Two implementations exist:
 *
 *   LlmEngine  - on-device via llama.cpp/Llamatik (private, but CPU-slow).
 *   GroqEngine - Groq cloud API (fast, but sends text to Groq's servers).
 *
 * The summarize pipeline talks only to this interface, so switching engines is a
 * matter of handing it a different implementation. `ensureReady` covers whatever
 * setup a backend needs (loading a GGUF for on-device; validating a key for
 * cloud). `stream` emits tokens as they arrive so the UI can show live output.
 */
interface InferenceEngine {
    suspend fun ensureReady(model: ModelInfo)
    fun stream(system: String, user: String, maxTokens: Int = 1024): Flow<String>
}
