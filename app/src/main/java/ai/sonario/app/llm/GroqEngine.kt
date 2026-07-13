package ai.sonario.app.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * Requests are deliberately buffered before tokens are emitted. That lets Sonario
 * safely retry a request when Android briefly suspends Wi-Fi, changes networks, or
 * loses DNS while the app is in the background, without appending a duplicated
 * partial answer to the UI.
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
            val model = modelProvider().trim()
            if (model.isEmpty()) throw IllegalStateException("No Groq model is selected.")

            val estimatedInput = RateLimiter.estimateTokens(system) +
                RateLimiter.estimateTokens(user)
            val estimatedTotal = estimatedInput + maxTokens

            rateLimiter?.awaitSlot(estimatedTotal) { seconds ->
                onRateWait(seconds)
            }
            onRateWait(0)

            val payload = buildPayload(model, system, user, maxTokens)
            val parsed = performRequestWithRetry(key, payload, estimatedTotal)

            rateLimiter?.record(parsed.usageTokens ?: estimatedTotal)
            onNetworkStatus(null)

            // Preserve the InferenceEngine streaming contract while only exposing
            // a response after it has completed successfully.
            parsed.text.chunked(96).forEach { emit(it) }
        }.flowOn(Dispatchers.IO)

    private suspend fun performRequestWithRetry(
        key: String,
        payload: String,
        estimatedTotal: Long,
    ): ParsedCompletion {
        val media = "application/json; charset=utf-8".toMediaTypeOrNull()
        val deadline = System.currentTimeMillis() + NETWORK_RETRY_WINDOW_MS
        var networkAttempt = 0
        var rateAttempt = 0

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
                if (!isTransientNetworkError(error) || System.currentTimeMillis() >= deadline) {
                    throw RuntimeException(friendlyNetworkError(error), error)
                }
                networkAttempt++
                waitForConnection(networkAttempt, deadline, error)
                continue@requestLoop
            }

            val code = response.code
            val retryAfterHeader = response.header("retry-after")
            val resetHeader = response.header("x-ratelimit-reset-tokens")
            val usedHeader = response.header("x-ratelimit-used-tokens")

            val body = try {
                response.body?.string().orEmpty()
            } catch (error: IOException) {
                response.close()
                if (!isTransientNetworkError(error) || System.currentTimeMillis() >= deadline) {
                    throw RuntimeException(friendlyNetworkError(error), error)
                }
                networkAttempt++
                waitForConnection(networkAttempt, deadline, error)
                continue@requestLoop
            } finally {
                response.close()
            }

            if (code == 429) {
                rateAttempt++
                if (rateAttempt > MAX_RATE_RETRIES) {
                    throw RuntimeException(friendlyError(code, body))
                }
                val seconds = retrySeconds(retryAfterHeader, resetHeader, body)
                var left = seconds.coerceIn(1L, MAX_RATE_WAIT_SECONDS)
                while (left > 0) {
                    onRateWait(left)
                    delay(1000)
                    left--
                }
                onRateWait(0)
                continue@requestLoop
            }

            if (code !in 200..299) {
                throw RuntimeException(friendlyError(code, body))
            }

            val parsed = parseCompletion(body)
            if (parsed.text.isBlank()) {
                throw RuntimeException(
                    "Groq returned an empty answer. Check that the selected model is available, " +
                        "then try again.")
            }

            val usage = parsed.usageTokens
                ?: usedHeader?.toLongOrNull()
                ?: estimatedTotal
            return parsed.copy(usageTokens = usage)
        }
    }

    private suspend fun waitForConnection(
        attempt: Int,
        deadline: Long,
        error: IOException,
    ) {
        // When Android reports no validated network, wait for it to return instead
        // of burning through retries immediately. Otherwise use exponential backoff
        // for transient DNS/socket failures while the network still looks connected.
        if (!hasValidatedInternet()) {
            while (System.currentTimeMillis() < deadline && !hasValidatedInternet()) {
                val remainingMinutes =
                    ((deadline - System.currentTimeMillis()).coerceAtLeast(0) / 60_000L) + 1
                onNetworkStatus(
                    "Internet connection lost. Sonario is waiting and will keep retrying " +
                        "for about $remainingMinutes more minute${if (remainingMinutes == 1L) "" else "s"}.")
                delay(NETWORK_POLL_MS)
            }
            if (!hasValidatedInternet()) {
                throw RuntimeException(
                    "The phone stayed offline too long, so Sonario could not reach Groq.", error)
            }
            onNetworkStatus("Internet is back. Reconnecting to Groq…")
            delay(750)
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
                "(attempt ${attempt + 1}).")
        delay(seconds * 1000L)
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
            .put("max_tokens", maxTokens)
            .put("stream", true)
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
                "Groq returned an unreadable response instead of JSON. Please try again.")
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
        if (direct >= 0) return direct
        val groq = obj.optJSONObject("x_groq")
            ?.optJSONObject("usage")
            ?.optLong("total_tokens", -1L) ?: -1L
        return groq.takeIf { it >= 0 }
    }

    private fun apiError(obj: JSONObject): String? {
        val error = obj.optJSONObject("error") ?: return null
        val message = error.optString("message").trim()
        return if (message.isBlank()) "Groq returned an API error." else "Groq: $message"
    }

    private fun retrySeconds(retryAfter: String?, reset: String?, body: String): Long {
        retryAfter?.trim()?.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(1) }
        parseDurationSeconds(reset)?.let { return it }
        val bodyMessage = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (_: Exception) {
            null
        }
        parseDurationSeconds(bodyMessage)?.let { return it }
        return 60L
    }

    /** Parses values such as "1m2.5s", "42s", or messages containing them. */
    private fun parseDurationSeconds(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val match = DURATION_REGEX.find(value.lowercase()) ?: return null
        val minutes = match.groups[1]?.value?.toDoubleOrNull() ?: 0.0
        val seconds = match.groups[2]?.value?.toDoubleOrNull() ?: 0.0
        val total = (minutes * 60.0 + seconds).toLong()
        return total.coerceAtLeast(1)
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
        val message = try {
            JSONObject(body).optJSONObject("error")?.optString("message")?.trim()
        } catch (_: Exception) {
            null
        }
        return when (code) {
            400 -> "Groq rejected this request" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
            401 -> "Groq rejected the API key. Check it in Settings."
            403 -> "Groq denied this request. Check the API key and model access."
            404 -> "The selected Groq model was not found. Choose a current model in Settings."
            413 -> "This source is too large for one Groq request. Try the normal summary or a shorter source."
            429 -> "Groq's rate limit is still active after repeated waits. Try again later or use a shorter source" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
            in 500..599 -> "Groq had a server error after automatic retries. Try again shortly."
            else -> "Groq request failed ($code)" +
                (if (!message.isNullOrBlank()) ": $message" else ".")
        }
    }

    companion object {
        private const val NETWORK_RETRY_WINDOW_MS = 10L * 60L * 1000L
        private const val NETWORK_POLL_MS = 2_000L
        private const val MAX_RATE_RETRIES = 8
        private const val MAX_RATE_WAIT_SECONDS = 10L * 60L
        private val DURATION_REGEX = Regex("(?:(\\d+(?:\\.\\d+)?)m)?\\s*(\\d+(?:\\.\\d+)?)s")
    }
}
