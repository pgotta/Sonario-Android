package ai.sonario.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud inference via Groq's OpenAI-compatible chat completions API.
 *
 * This sends the prompt (which includes the user's source text) to Groq's
 * servers. It is fast, but it is NOT private the way the on-device engine is.
 * The UI makes that distinction explicit when this engine is selected.
 *
 * The user supplies their own Groq API key (from console.groq.com) and can set
 * the model string. Model names change over time, so the model is configurable
 * rather than hardcoded; the default is Llama 4 Scout.
 */
class GroqEngine(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
) : InferenceEngine {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    override suspend fun ensureReady(model: ModelInfo) {
        val key = apiKeyProvider()
        require(!key.isNullOrBlank()) {
            "No Groq API key set. Open Settings and paste your key from " +
            "console.groq.com."
        }
    }

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> =
        flow {
            val key = apiKeyProvider()
                ?: throw IllegalStateException("No Groq API key set.")
            val model = modelProvider()

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.5)
                .put("max_tokens", maxTokens)
                .put("stream", true)
                .toString()

            val media = "application/json; charset=utf-8".toMediaTypeOrNull()
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(media))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    throw RuntimeException(friendlyError(resp.code, body))
                }
                val source = resp.body?.source()
                    ?: throw RuntimeException("Empty response from Groq.")
                // Parse Server-Sent Events: lines beginning with "data: ".
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val token = parseDelta(data)
                    if (!token.isNullOrEmpty()) emit(token)
                }
            }
        }.flowOn(Dispatchers.IO)

    private fun parseDelta(data: String): String? = try {
        val obj = JSONObject(data)
        obj.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?.optString("content", "")
            ?.ifEmpty { null }
    } catch (_: Exception) { null }

    private fun friendlyError(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (_: Exception) { null }
        return when (code) {
            401 -> "Groq rejected the API key. Check it in Settings."
            429 -> "Groq rate limit hit (free tier is limited). Wait a moment " +
                   "and try again, or summarize a shorter source."
            in 500..599 -> "Groq had a server error. Try again shortly."
            else -> "Groq request failed ($code)" +
                    (if (!msg.isNullOrBlank()) ": $msg" else ".")
        }
    }
}
