package ai.sonario.app.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ai.sonario.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Cloud inference through Groq's OpenAI-compatible chat-completions API.
 *
 * Sonario's cloud path is pinned to Qwen 3.6 27B. Responses are buffered before
 * tokens are exposed so a transient failure can be retried without duplicating a
 * partial answer. Provider rate-limit headers are fed back into [RateLimiter],
 * which queues the next call until Groq's real token window has reset.
 */
class GroqEngine(
    context: Context,
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String,
    private val rateLimiter: RateLimiter? = null,
    private val onRateWait: (Long) -> Unit = {},
    private val onNetworkStatus: (String?) -> Unit = {},
) : InferenceEngine {

    private val appContext = context.applicationContext
    private val connectivity = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .build()

    private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

    override suspend fun ensureReady(model: ModelInfo) {
        val key = apiKeyProvider()
        require(!key.isNullOrBlank()) {
            "No Groq API key is set. Open Settings and paste your key from console.groq.com."
        }
    }

    override fun stream(system: String, user: String, maxTokens: Int): Flow<String> =
        flow {
            val key = apiKeyProvider()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("No Groq API key is set.")

            // Reading the provider performs the one-time migration of stale saved
            // model preferences. The request itself is always pinned to Qwen.
            modelProvider()
            val model = Settings.DEFAULT_GROQ_MODEL

            val estimatedInput = RateLimiter.estimateTokens(system) +
                RateLimiter.estimateTokens(user)
            val estimatedTotal = estimatedInput + maxTokens.toLong()

            rateLimiter?.awaitSlot(estimatedTotal) { seconds ->
                onRateWait(seconds)
            }
            onRateWait(0L)

            val payload = buildPayload(model, system, user, maxTokens)
            val parsed = performRequestWithRetry(key, payload, estimatedTotal)

            rateLimiter?.record(parsed.usageTokens ?: estimatedTotal)
            onNetworkStatus(null)

            // Preserve the streaming contract while publishing only a complete,
            // successfully buffered response.
            parsed.text.chunked(96).forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

    private suspend fun performRequestWithRetry(
        key: String,
        payload: String,
        estimatedTotal: Long,
    ): ParsedCompletion {
        val media = "application/json; charset=utf-8".toMediaTypeOrNull()
        val networkDeadline = System.currentTimeMillis() + NETWORK_RETRY_WINDOW_MS
        var networkAttempt = 0
        var rateAttempt = 0
        var serverAttempt = 0

        requestLoop@ while (true) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream, application/json")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(media))
                .build()

            val response = try {
                http.newCall(request).execute()
            } catch (error: IOException) {
                if (!isTransientNetworkError(error) ||
                    System.currentTimeMillis() >= networkDeadline
                ) {
                    throw RuntimeException(friendlyNetworkError(error), error)
                }
                networkAttempt++
                waitForConnection(networkAttempt, networkDeadline, error)
                continue@requestLoop
            }

            val code = response.code
            val retryAfterHeader = response.header("retry-after")
            val resetTokensHeader = response.header("x-ratelimit-reset-tokens")
            val limitTokensHeader = response.header("x-ratelimit-limit-tokens")
            val remainingTokensHeader = response.header("x-ratelimit-remaining-tokens")

            val resetTokenMs = parseDurationMillis(resetTokensHeader)
            rateLimiter?.syncServerTokenWindow(
                limit = limitTokensHeader?.toLongOrNull(),
                remaining = remainingTokensHeader?.toLongOrNull(),
                resetAfterMs = resetTokenMs,
            )

            val body = try {
                response.body?.string().orEmpty()
            } catch (error: IOException) {
                response.close()
                if (!isTransientNetworkError(error) ||
                    System.currentTimeMillis() >= networkDeadline
                ) {
                    throw RuntimeException(friendlyNetworkError(error), error)
                }
                networkAttempt++
                waitForConnection(networkAttempt, networkDeadline, error)
                continue@requestLoop
            } finally {
                response.close()
            }

            if (code == 429) {
                val message = apiMessage(body)
                if (isDailyLimit(message)) {
                    throw RuntimeException(
                        "Groq's organization-wide daily limit has been reached. " +
                            "Sonario saved every completed checkpoint, so resume after Groq resets " +
                            "the limit or use a Developer plan."
                    )
                }

                rateAttempt++
                if (rateAttempt > MAX_RATE_RETRIES) {
                    throw RuntimeException(friendlyError(code, body))
                }

                val waitMs = retryWaitMillis(
                    retryAfter = retryAfterHeader,
                    reset = resetTokensHeader,
                    body = body,
                ).coerceIn(1_000L, MAX_MINUTE_RATE_WAIT_MS)

                rateLimiter?.markServerBackoff(waitMs)
                waitWithCountdown(waitMs)
                continue@requestLoop
            }

            if (code in 500..599 && serverAttempt < MAX_SERVER_RETRIES) {
                serverAttempt++
                val waitMs = min(30_000L, (1L shl serverAttempt) * 1_000L)
                onNetworkStatus(
                    "Groq had a temporary server error. Retrying in ${waitMs / 1000L}s " +
                        "(attempt ${serverAttempt + 1})."
                )
                delay(waitMs)
                continue@requestLoop
            }

            if (code !in 200..299) {
                throw RuntimeException(friendlyError(code, body))
            }

            val parsed = parseCompletion(body)
            if (parsed.text.isBlank()) {
                throw RuntimeException(
                    "Groq returned an empty Qwen answer. Please try again."
                )
            }

            return parsed.copy(usageTokens = parsed.usageTokens ?: estimatedTotal)
        }
    }

    private suspend fun waitWithCountdown(waitMs: Long) {
        var leftMs = waitMs
        while (leftMs > 0L) {
            onRateWait(((leftMs + 999L) / 1_000L).coerceAtLeast(1L))
            val step = minOf(leftMs, 1_000L)
            delay(step)
            leftMs -= step
        }
        onRateWait(0L)
    }

    private suspend fun waitForConnection(
        attempt: Int,
        deadline: Long,
        error: IOException,
    ) {
        if (!hasValidatedInternet()) {
            while (System.currentTimeMillis() < deadline && !hasValidatedInternet()) {
                val remainingMinutes =
                    ((deadline - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L) + 1L
                onNetworkStatus(
                    "Internet connection lost. Sonario is waiting and will keep retrying " +
                        "for about $remainingMinutes more minute${if (remainingMinutes == 1L) "" else "s"}."
                )
                delay(NETWORK_POLL_MS)
            }
            if (!hasValidatedInternet()) {
                throw RuntimeException(
                    "The phone stayed offline too long, so Sonario could not reach Groq.",
                    error,
                )
            }
            onNetworkStatus("Internet is back. Reconnecting to Groq…")
            delay(750L)
            return
        }

        val seconds = min(60L, 1L shl min(attempt, 6))
        val reason = when (error) {
            is UnknownHostException -> "DNS lookup failed"
            is SocketTimeoutException -> "connection timed out"
            else -> "connection was interrupted"
        }
        onNetworkStatus(
            "Groq $reason. Retrying automatically in ${seconds}s " +
                "(attempt ${attempt + 1})."
        )
        delay(seconds * 1_000L)
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun buildPayload(
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))

        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.35)
            .put("top_p", 0.8)
            .put("max_tokens", maxTokens)
            .put("reasoning_effort", "none")
            .put("reasoning_format", "hidden")
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .toString()
    }

    private data class ParsedCompletion(
        val text: String,
        val usageTokens: Long? = null,
    )

    private fun parseCompletion(raw: String): ParsedCompletion {
        if (raw.isBlank()) return ParsedCompletion("")

        val text = StringBuilder()
        var usage: Long? = null
        var sawSse = false

        raw.lineSequence().forEach { line ->
            if (!line.startsWith("data:")) return@forEach
            sawSse = true
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach
            val obj = try {
                JSONObject(data)
            } catch (_: Exception) {
                return@forEach
            }
            apiError(obj)?.let { throw RuntimeException(it) }
            val choice = obj.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("delta")?.optString("content", "")
                .orEmpty()
            if (content.isNotEmpty()) text.append(content)
            usage = usageFrom(obj) ?: usage
        }

        if (sawSse) return ParsedCompletion(text.toString(), usage)

        val obj = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw RuntimeException(
                "Groq returned an unreadable response instead of JSON. Please try again."
            )
        }
        apiError(obj)?.let { throw RuntimeException(it) }
        val choice = obj.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content", "")
            ?.ifBlank { choice?.optJSONObject("delta")?.optString("content", "").orEmpty() }
            .orEmpty()
        return ParsedCompletion(content, usageFrom(obj))
    }

    private fun usageFrom(obj: JSONObject): Long? {
        val direct = obj.optJSONObject("usage")?.optLong("total_tokens", -1L) ?: -1L
        if (direct >= 0L) return direct
        val groq = obj.optJSONObject("x_groq")
            ?.optJSONObject("usage")
            ?.optLong("total_tokens", -1L) ?: -1L
        return groq.takeIf { it >= 0L }
    }

    private fun apiError(obj: JSONObject): String? {
        val error = obj.optJSONObject("error") ?: return null
        val message = error.optString("message").trim()
        return if (message.isBlank()) "Groq returned an API error." else "Groq: $message"
    }

    private fun apiMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.trim()
    } catch (_: Exception) {
        null
    }

    private fun isDailyLimit(message: String?): Boolean {
        val lower = message.orEmpty().lowercase()
        return "tokens per day" in lower || "requests per day" in lower ||
            "daily token" in lower || "daily request" in lower ||
            Regex("\\btpd\\b").containsMatchIn(lower) ||
            Regex("\\brpd\\b").containsMatchIn(lower)
    }

    private fun retryWaitMillis(retryAfter: String?, reset: String?, body: String): Long {
        retryAfter?.trim()?.toDoubleOrNull()?.let {
            return (it * 1_000.0).toLong().coerceAtLeast(1_000L)
        }
        parseDurationMillis(reset)?.let { return it }
        parseDurationMillis(apiMessage(body))?.let { return it }
        return 60_000L
    }

    /** Parses durations such as 7.66s, 1m2.5s, 2h3m, or 250ms. */
    private fun parseDurationMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        var total = 0.0
        var found = false
        UNIT_REGEX.findAll(value.lowercase()).forEach { match ->
            val amount = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            found = true
            total += when (match.groupValues[2]) {
                "d" -> amount * 86_400_000.0
                "h" -> amount * 3_600_000.0
                "m" -> amount * 60_000.0
                "s" -> amount * 1_000.0
                "ms" -> amount
                else -> 0.0
            }
        }
        return if (found) total.toLong().coerceAtLeast(1L) else null
    }

    private fun isTransientNetworkError(error: IOException): Boolean =
        error is UnknownHostException ||
            error is ConnectException ||
            error is NoRouteToHostException ||
            error is SocketTimeoutException ||
            error is SocketException

    private fun friendlyNetworkError(error: IOException): String = when (error) {
        is UnknownHostException ->
            "Sonario could not resolve api.groq.com. It kept retrying, but the phone's " +
                "internet or DNS connection did not recover. Check Wi-Fi/mobile data and try again."
        is SocketTimeoutException ->
            "The connection to Groq timed out after automatic retries. Check the connection and try again."
        else ->
            "The connection to Groq was interrupted after automatic retries: " +
                (error.message ?: error.javaClass.simpleName)
    }

    private fun friendlyError(code: Int, body: String): String {
        val message = apiMessage(body)
        return when (code) {
            400 -> "Groq rejected this Qwen request" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
            401 -> "Groq rejected the API key. Check it in Settings."
            403 -> "Groq denied this request. Check the API key and Qwen access."
            404 -> "Qwen 3.6 27B is not available for this Groq account or region."
            413 -> "This request is too large for Groq. Sonario saved the completed checkpoints."
            429 -> "Groq's minute rate limit is still active after repeated waits. " +
                "Your completed checkpoints are saved; try Resume shortly" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
            in 500..599 -> "Groq had a server error after automatic retries. Try again shortly."
            else -> "Groq request failed ($code)" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
        }
    }

    companion object {
        private const val NETWORK_RETRY_WINDOW_MS = 10L * 60L * 1_000L
        private const val NETWORK_POLL_MS = 2_000L
        private const val MAX_RATE_RETRIES = 8
        private const val MAX_SERVER_RETRIES = 3
        private const val MAX_MINUTE_RATE_WAIT_MS = 10L * 60L * 1_000L
        private val UNIT_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*(ms|d|h|m|s)\\b")
    }
}
