package ai.sonario.app.llm

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Paces cloud requests so free-tier token caps are respected.
 *
 * Provider-agnostic: the per-minute and per-day caps are read from
 * [RateLimiter.Config] so the same limiter works for Groq, OpenAI, and
 * anyone else. The conservative defaults match Groq's free tier; users of
 * other providers can widen them via [RateLimiter.Config].
 *
 *   - ~30,000 tokens per minute (TPM)
 *   - ~500,000 tokens per day  (TPD)
 *
 * Before each request we estimate its token cost and, if sending it now would
 * exceed the per-minute budget, we wait until the rolling minute window has room.
 * Actual usage is reconciled from response headers when present. The daily total
 * is persisted so it survives app restarts within the same day.
 */
class RateLimiter(context: Context, private val config: Config = Config()) {

    private val prefs = context.applicationContext
        .getSharedPreferences("groq_rate", Context.MODE_PRIVATE)

    private val tpmLimit = config.tokensPerMinute
    private val tpdLimit = config.tokensPerDay

    // Rolling per-minute window: timestamps (ms) paired with tokens spent.
    private val window = ArrayDeque<Pair<Long, Long>>()

    data class Config(
        val tokensPerMinute: Long = 28_000L,     // under the ~30k/min cap
        val tokensPerDay: Long = 480_000L,      // under the ~500k/day cap
    )

    data class DailyUsage(val used: Long, val limit: Long) {
        val remaining: Long get() = (limit - used).coerceAtLeast(0)
    }

    // ── daily tracking (persisted) ─────────────────────────────────────────────

    private fun today(): String {
        val epochDay = System.currentTimeMillis() / 86_400_000L
        return epochDay.toString()
    }

    private fun dailyUsed(): Long {
        val day = prefs.getString("day", null)
        if (day != today()) return 0
        return prefs.getLong("used", 0)
    }

    private fun addDaily(tokens: Long) {
        val used = if (prefs.getString("day", null) == today())
            prefs.getLong("used", 0) else 0
        prefs.edit()
            .putString("day", today())
            .putLong("used", used + tokens)
            .apply()
    }

    fun dailyUsage(): DailyUsage = DailyUsage(dailyUsed(), tpdLimit)

    fun resetDaily() {
        prefs.edit().putString("day", today()).putLong("used", 0).apply()
        window.clear()
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

    fun estimate(sourceText: String): Estimate {
        val input = estimateTokens(sourceText)
        // Map-reduce overhead: prompts on each chunk + a combine pass + output.
        // Empirically ~1.3x the input plus generated summary tokens.
        val total = (input * 1.3).toLong() + 1200
        val remaining = dailyUsage().remaining
        val minutes = total.toDouble() / tpmLimit
        val etaSec = (minutes * 60).toLong().coerceAtLeast(2) + 4
        return Estimate(
            inputTokens = input,
            totalTokens = total,
            dailyRemaining = remaining,
            dailyLimit = tpdLimit,
            exceedsDaily = total > remaining,
            etaSeconds = etaSec,
        )
    }

    fun wouldExceedDaily(tokens: Long): Boolean = dailyUsed() + tokens > tpdLimit

    // ── per-minute pacing ──────────────────────────────────────────────────────

    private fun trimWindow(now: Long) {
        while (window.isNotEmpty() && now - window.first().first >= 60_000L) {
            window.removeFirst()
        }
    }

    private fun tokensInWindow(now: Long): Long {
        trimWindow(now)
        return window.sumOf { it.second }
    }

    fun waitMillisFor(tokens: Long): Long {
        val now = System.currentTimeMillis()
        val inWindow = tokensInWindow(now)
        if (inWindow + tokens <= tpmLimit) return 0
        var needed = inWindow + tokens - tpmLimit
        var waitUntil = now
        for ((ts, tok) in window) {
            needed -= tok
            if (needed <= 0) { waitUntil = ts + 60_000L; break }
        }
        return (waitUntil - now).coerceAtLeast(0)
    }

    suspend fun awaitSlot(tokens: Long, onWaiting: (Long) -> Unit) {
        var wait = waitMillisFor(tokens)
        while (wait > 0) {
            onWaiting((wait + 999) / 1000)
            val step = minOf(wait, 1000L)
            delay(step)
            wait = waitMillisFor(tokens)
        }
    }

    fun record(tokens: Long) {
        val now = System.currentTimeMillis()
        trimWindow(now)
        window.addLast(now to tokens)
        addDaily(tokens)
    }

    companion object {
        /** Rough token estimate: ~4 chars per token, plus a small overhead. */
        fun estimateTokens(text: String): Long = (text.length / 4L) + 16
    }
}
