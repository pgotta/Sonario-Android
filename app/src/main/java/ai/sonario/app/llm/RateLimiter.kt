package ai.sonario.app.llm

import android.content.Context
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

/**
 * App-side pacing for Groq's free Qwen 3.6 27B limits.
 *
 * Groq currently publishes 30 RPM, 8,000 TPM and 200,000 TPD for
 * qwen/qwen3.6-27b. Sonario keeps a little headroom, queues requests instead of
 * repeatedly receiving 429 responses, and also honors the token-window headers
 * returned by Groq. Limits are organization-wide, so activity outside Sonario
 * can still reduce what is available.
 */
class RateLimiter(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("groq_rate_qwen36", Context.MODE_PRIVATE)

    private val tokenWindow = ArrayDeque<Pair<Long, Long>>()
    private val requestWindow = ArrayDeque<Long>()

    @Volatile private var serverRemainingTokens: Long? = null
    @Volatile private var serverTokenResetAtMs: Long = 0L
    @Volatile private var serverBlockedUntilMs: Long = 0L

    data class DailyUsage(val used: Long, val limit: Long) {
        val remaining: Long get() = (limit - used).coerceAtLeast(0)
    }

    data class Estimate(
        val inputTokens: Long,
        val totalTokens: Long,
        val dailyRemaining: Long,
        val dailyLimit: Long,
        val exceedsDaily: Boolean,
        val etaSeconds: Long,
    ) {
        val percentOfRemaining: Int =
            if (dailyRemaining > 0)
                ((totalTokens.toDouble() / dailyRemaining) * 100).toInt().coerceIn(0, 999)
            else 100
    }

    private fun today(): String =
        (System.currentTimeMillis() / 86_400_000L).toString()

    private fun dailyUsed(): Long {
        if (prefs.getString("day", null) != today()) return 0L
        return prefs.getLong("used", 0L)
    }

    private fun addDaily(tokens: Long) {
        val used = if (prefs.getString("day", null) == today()) {
            prefs.getLong("used", 0L)
        } else {
            0L
        }
        prefs.edit()
            .putString("day", today())
            .putLong("used", used + tokens.coerceAtLeast(0L))
            .apply()
    }

    fun dailyUsage(): DailyUsage = DailyUsage(dailyUsed(), DAILY_WORKING_LIMIT)

    fun resetDaily() {
        prefs.edit().putString("day", today()).putLong("used", 0L).apply()
        synchronized(this) {
            tokenWindow.clear()
            requestWindow.clear()
            serverRemainingTokens = null
            serverTokenResetAtMs = 0L
            serverBlockedUntilMs = 0L
        }
    }

    /**
     * Estimate the whole Sonario job, not just its first request. Long cloud jobs
     * normally read the source once for notes and again for the detailed view.
     */
    fun estimate(sourceText: String): Estimate {
        val input = estimateTokens(sourceText)
        val total = (input * 2.4).toLong() + 5_000L
        val remaining = dailyUsage().remaining
        val minuteWindows = ceil(total.toDouble() / TOKEN_WORKING_LIMIT).toLong()
        val etaSec = (minuteWindows * 60L).coerceAtLeast(4L)
        return Estimate(
            inputTokens = input,
            totalTokens = total,
            dailyRemaining = remaining,
            dailyLimit = DAILY_WORKING_LIMIT,
            exceedsDaily = total > remaining,
            etaSeconds = etaSec,
        )
    }

    fun wouldExceedDaily(tokens: Long): Boolean =
        dailyUsed() + tokens > DAILY_WORKING_LIMIT

    /**
     * Wait until both the local rolling windows and Groq's last reported server
     * window can accept this request. The callback receives a live countdown.
     */
    suspend fun awaitSlot(tokens: Long, onWaiting: (Long) -> Unit) {
        if (tokens > MAX_REQUEST_TOKENS) {
            throw IllegalStateException(
                "This Groq request is too large for Qwen's 8K-token-per-minute free limit. " +
                    "Sonario should have split it automatically; please report this source."
            )
        }
        if (wouldExceedDaily(tokens)) {
            throw IllegalStateException(
                "Sonario's conservative daily Groq budget has been reached. " +
                    "Your completed checkpoints are saved; continue after the daily limit resets " +
                    "or use a Groq Developer plan."
            )
        }

        while (true) {
            val waitMs = synchronized(this) { waitMillisForLocked(tokens) }
            if (waitMs <= 0L) break
            onWaiting(ceil(waitMs / 1000.0).toLong().coerceAtLeast(1L))
            delay(minOf(waitMs, 1_000L))
        }
        onWaiting(0L)

        synchronized(this) {
            val now = System.currentTimeMillis()
            trimLocked(now)
            requestWindow.addLast(now)
        }
    }

    /** Record actual tokens after a successful response. */
    fun record(tokens: Long) {
        val safe = tokens.coerceAtLeast(0L)
        synchronized(this) {
            val now = System.currentTimeMillis()
            trimLocked(now)
            tokenWindow.addLast(now to safe)
        }
        addDaily(safe)
    }

    /**
     * Synchronize with Groq's x-ratelimit-* token headers. These headers describe
     * the provider's real organization-wide minute window and are more reliable
     * than Sonario's local estimate when the same organization is used elsewhere.
     */
    fun syncServerTokenWindow(limit: Long?, remaining: Long?, resetAfterMs: Long?) {
        if (remaining == null || resetAfterMs == null || resetAfterMs <= 0L) return
        synchronized(this) {
            // Ignore obviously unrelated/invalid values, but accept account-specific
            // limits rather than assuming every organization has the base free tier.
            if (limit != null && limit <= 0L) return
            serverRemainingTokens = remaining.coerceAtLeast(0L)
            serverTokenResetAtMs = System.currentTimeMillis() + resetAfterMs
        }
    }

    fun markServerBackoff(waitMs: Long) {
        if (waitMs <= 0L) return
        synchronized(this) {
            serverBlockedUntilMs = max(
                serverBlockedUntilMs,
                System.currentTimeMillis() + waitMs,
            )
        }
    }

    private fun waitMillisForLocked(tokens: Long): Long {
        val now = System.currentTimeMillis()
        trimLocked(now)

        var waitMs = 0L

        val localTokens = tokenWindow.sumOf { it.second }
        if (localTokens + tokens > TOKEN_WORKING_LIMIT && tokenWindow.isNotEmpty()) {
            var needed = localTokens + tokens - TOKEN_WORKING_LIMIT
            for ((timestamp, spent) in tokenWindow) {
                needed -= spent
                if (needed <= 0L) {
                    waitMs = max(waitMs, timestamp + WINDOW_MS - now)
                    break
                }
            }
        }

        if (requestWindow.size >= REQUEST_WORKING_LIMIT && requestWindow.isNotEmpty()) {
            waitMs = max(waitMs, requestWindow.first() + WINDOW_MS - now)
        }

        if (serverBlockedUntilMs > now) {
            waitMs = max(waitMs, serverBlockedUntilMs - now)
        }

        if (serverTokenResetAtMs <= now) {
            serverRemainingTokens = null
            serverTokenResetAtMs = 0L
        } else {
            val remaining = serverRemainingTokens
            if (remaining != null && tokens > remaining) {
                waitMs = max(waitMs, serverTokenResetAtMs - now)
            }
        }

        return waitMs.coerceAtLeast(0L)
    }

    private fun trimLocked(now: Long) {
        while (tokenWindow.isNotEmpty() && now - tokenWindow.first().first >= WINDOW_MS) {
            tokenWindow.removeFirst()
        }
        while (requestWindow.isNotEmpty() && now - requestWindow.first() >= WINDOW_MS) {
            requestWindow.removeFirst()
        }
    }

    companion object {
        const val PUBLISHED_TPM = 8_000L
        const val PUBLISHED_TPD = 200_000L
        const val PUBLISHED_RPM = 30

        // Small buffers account for prompt/token-estimation error and other use in
        // the same Groq organization.
        const val MAX_REQUEST_TOKENS = 7_400L
        private const val TOKEN_WORKING_LIMIT = 7_600L
        private const val DAILY_WORKING_LIMIT = 195_000L
        private const val REQUEST_WORKING_LIMIT = 28
        private const val WINDOW_MS = 60_000L

        /** Rough estimate: about four UTF-16 characters per model token. */
        fun estimateTokens(text: String): Long = (text.length / 4L) + 16L
    }
}
