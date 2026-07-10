package ai.sonario.app.llm

import android.content.Context
import kotlinx.coroutines.delay

/**
 * Paces Groq requests to stay within the free-tier limits:
 *   - ~30,000 tokens per minute (TPM)
 *   - ~500,000 tokens per day  (TPD)
 *
 * Before each request we estimate its token cost and, if sending it now would
 * exceed the per-minute budget, we wait until the rolling minute window has room.
 * Actual usage is reconciled from Groq's response headers when present. The daily
 * total is persisted so it survives app restarts within the same day.
 *
 * These limits are conservative defaults; Groq may grant a given model more. They
 * are safe lower bounds so we err toward not getting 429'd.
 */
class RateLimiter(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("groq_rate", Context.MODE_PRIVATE)

    // Conservative free-tier caps. Leave headroom below the real ceilings.
    private val tpmLimit = 28_000L      // under the ~30k/min cap
    private val tpdLimit = 480_000L     // under the ~500k/day cap

    // Rolling per-minute window: timestamps (ms) paired with tokens spent.
    private val window = ArrayDeque<Pair<Long, Long>>()

    data class DailyUsage(val used: Long, val limit: Long) {
        val remaining: Long get() = (limit - used).coerceAtLeast(0)
    }

    // ── daily tracking (persisted) ─────────────────────────────────────────────

    private fun today(): String {
        // yyyyDDD-ish key from epoch day; no formatting deps needed.
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

    /** True if this many tokens would blow the daily cap (can't be waited out). */
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

    /**
     * Milliseconds to wait before a request costing [tokens] can be sent without
     * exceeding the per-minute budget. 0 if it can go now.
     */
    fun waitMillisFor(tokens: Long): Long {
        val now = System.currentTimeMillis()
        val inWindow = tokensInWindow(now)
        if (inWindow + tokens <= tpmLimit) return 0
        // Wait until the oldest entries age out enough to make room.
        var needed = inWindow + tokens - tpmLimit
        var waitUntil = now
        for ((ts, tok) in window) {
            needed -= tok
            if (needed <= 0) { waitUntil = ts + 60_000L; break }
        }
        return (waitUntil - now).coerceAtLeast(0)
    }

    /** Suspend until a request of [tokens] can proceed; reports wait seconds. */
    suspend fun awaitSlot(tokens: Long, onWaiting: (Long) -> Unit) {
        var wait = waitMillisFor(tokens)
        while (wait > 0) {
            onWaiting((wait + 999) / 1000)  // ceil to seconds, for the UI
            val step = minOf(wait, 1000L)
            delay(step)
            wait = waitMillisFor(tokens)
        }
    }

    /** Record tokens actually spent (estimate up front, reconcile from headers). */
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
