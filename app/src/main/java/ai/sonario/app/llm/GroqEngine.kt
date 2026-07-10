package ai.sonario.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Sends the prompt (which includes the user's source text) to Groq's servers.
 * Fast, but NOT private the way the on-device engine is.
 *
 * Rate limiting: Groq's free tier caps tokens per minute (~30k) and per day
 * (~500k). A long video summarized in several chunks would blow the per-minute
 * cap if fired back-to-back, so this engine paces requests through a RateLimiter:
 * it waits for a per-minute slot before each call and, if Groq still returns 429,
 * honors the Retry-After header and retries. Waits are reported via onRateWait so
 * the UI can show "waiting for rate limit... Ns".
 */
class GroqEngine(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
    private val rateLimiter: RateLimiter? = null,
    private val onRateWait: (Long) -> Unit = {},
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

            // Estimate this request's cost (input + expected output) and pace it.
            val estIn = RateLimiter.estimateTokens(system) +
                RateLimiter.estimateTokens(user)
            val estTotal = estIn + maxTokens
            rateLimiter?.let { rl ->
                if (rl.wouldExceedDaily(estTotal)) {
                    val u = rl.dailyUsage()
                    throw RuntimeException(
                        "This would exceed today's Groq free-tier budget " +
                        "(${u.used}/${u.limit} tokens used). Try a shorter source " +
                        "or wait until tomorrow.")
                }
                rl.awaitSlot(estTotal) { secs -> onRateWait(secs) }
            }

            val payload = buildPayload(model, system, user, maxTokens)
            val media = "application/json; charset=utf-8".toMediaTypeOrNull()

            var attempt = 0
            while (true) {
                attempt++
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $key")
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody(media))
                    .build()

                val resp = http.newCall(req).execute()
                if (resp.code == 429 && attempt <= 5) {
                    // Honor Retry-After (seconds), else back off a minute.
                    val retryAfter = resp.header("retry-after")?.toLongOrNull()
                        ?: resp.header("x-ratelimit-reset-tokens")
                            ?.removeSuffix("s")?.toDoubleOrNull()?.toLong()
                        ?: 60L
                    resp.close()
                    var left = retryAfter.coerceAtLeast(1)
                    while (left > 0) { onRateWait(left); delay(1000); left-- }
                    continue
                }
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    resp.close()
                    throw RuntimeException(friendlyError(resp.code, body))
                }

                // Record actual usage if Groq reports it; else the estimate.
                val spent = resp.header("x-ratelimit-used-tokens")?.toLongOrNull()
                rateLimiter?.record(spent ?: estTotal)

                resp.use { r ->
                    val source = r.body?.source()
                        ?: throw RuntimeException("Empty response from Groq.")
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
                break
            }
        }.flowOn(Dispatchers.IO)

    private fun buildPayload(
        model: String, system: String, user: String, maxTokens: Int,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.5)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .toString()
    }

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
            429 -> "Groq rate limit hit and retries were exhausted. Try again " +
                   "shortly or use a shorter source."
            in 500..599 -> "Groq had a server error. Try again shortly."
            else -> "Groq request failed ($code)" +
                    (if (!msg.isNullOrBlank()) ": $msg" else ".")
        }
    }
}
