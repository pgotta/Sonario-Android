package ai.sonario.app.summarize

import ai.sonario.app.llm.InferenceEngine
import ai.sonario.app.llm.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The four-stage summarizer, ported from pipeline.summarize_text.
 *
 *   short text  -> single pass
 *   long text   -> chunk -> condense each chunk -> combine (map-reduce)
 *
 * Then Bullets and Detailed views are derived, the way the desktop app
 * generated all views up front so the UI could toggle instantly.
 *
 * The engine is an InferenceEngine, so this works with the on-device model or
 * the Groq cloud engine. Chunking adapts: on-device models have a small (~4k
 * token) context so chunks are small and the total work is hard-capped; the
 * cloud path (Llama 4 Scout, 128k context) uses much larger chunks and rarely
 * needs to chunk at all.
 */
class SummarizeEngine(
    private val engine: InferenceEngine,
    private val bigContext: Boolean = false,
) {

    // On-device: ~2800 chars/chunk (~800 tokens). Cloud: much larger, since a
    // 128k-context model swallows most sources in one or a few passes.
    private val chunkChars = if (bigContext) 40000 else 2800
    private val singlePassLimit = if (bigContext) 120000 else 3200

    // Work cap. On-device this bounds runtime (CPU is slow). Cloud can afford
    // more passes, but we still cap so a giant book stays within rate limits.
    private val maxChunks = if (bigContext) 40 else 20
    private val maxChunkChars = if (bigContext) 48000 else 6000

    data class Progress(
        val phase: String,      // "fetching" | "chunking" | "condensing" | "synthesizing" | "deriving" | "done"
        val current: Int = 0,
        val total: Int = 0,
        val live: String = "",  // streaming tokens for the live view
    )

    data class Result(
        val normal: String,
        val detailed: String,
        val bullets: String,
        val title: String,
        val kind: String,
        val approxMinutes: Int?,
    )

    private val _progress = MutableStateFlow(Progress("idle"))
    val progress: StateFlow<Progress> = _progress

    suspend fun run(
        model: ModelInfo,
        text: String,
        title: String,
        kind: String,
        approxMinutes: Int?,
    ): Result {
        engine.ensureReady(model)

        val normal: String
        if (text.length <= singlePassLimit) {
            // Single pass — the small/short path.
            _progress.value = Progress("synthesizing", 1, 1)
            normal = Cleaner.clean(streamCollect(Prompts.SUMMARY, source(text)))
        } else {
            // Map: chunk and condense each section, but bounded.
            // Choose a chunk size that keeps us under maxChunks. For very large
            // sources this means each chunk grows (up to maxChunkChars); if even
            // that isn't enough, we cap the material processed so a job can't run
            // for hours on-device.
            val chunks = chunkTextCapped(text)
            _progress.value = Progress("condensing", 0, chunks.size)
            val notes = ArrayList<String>(chunks.size)
            chunks.forEachIndexed { i, chunk ->
                val note = streamCollect(Prompts.CHUNK, chunk)
                notes.add(Cleaner.clean(note))
                _progress.value = Progress("condensing", i + 1, chunks.size)
            }
            // Reduce: combine the section notes into the final one-page summary.
            _progress.value = Progress("synthesizing", chunks.size, chunks.size)
            val joined = notes.joinToString("\n\n")
            normal = Cleaner.clean(finalCombine(joined))
        }

        // Derive the Bullets and Detailed views (as desktop did up front).
        _progress.value = Progress("deriving")
        val bullets = Cleaner.clean(streamCollect(Prompts.BULLETS, normal))
        // Detailed is built from the richest material available: for short
        // sources the source itself; for long sources the section notes feed it.
        val detailedSource = if (text.length <= singlePassLimit) text else normal
        val detailed = Cleaner.clean(buildDetailed(detailedSource))

        _progress.value = Progress("done")
        return Result(normal, detailed, bullets, title, kind, approxMinutes)
    }

    // ── stages ──────────────────────────────────────────────────────────────────

    /** pipeline._final_combine, simplified: one-shot, else hierarchical batches. */
    private suspend fun finalCombine(joined: String): String {
        // 1) one-shot
        runCatching {
            return streamCollect(Prompts.REDUCE, "Section notes, in order:\n\n$joined")
        }
        // 2) hierarchical: batch the note-blocks, condense each, then combine
        val blocks = joined.split("\n\n").filter { it.isNotBlank() }
        val partials = ArrayList<String>()
        var i = 0
        while (i < blocks.size) {
            val batch = blocks.subList(i, minOf(i + 5, blocks.size)).joinToString("\n\n")
            partials.add(runCatching { streamCollect(Prompts.CHUNK, batch) }
                .getOrDefault(batch.take(1500)))
            i += 5
        }
        val small = partials.joinToString("\n\n").take(singlePassLimit)
        runCatching {
            return streamCollect(Prompts.REDUCE, "Section notes, in order:\n\n$small")
        }
        // 3) pure-local fallback: never throw away a finished run
        return "**Combined from section notes.**\n\n$small"
    }

    /** pipeline._build_detailed: single pass when it fits, else batch then merge. */
    private suspend fun buildDetailed(src: String): String {
        if (src.length <= singlePassLimit * 2) {
            return streamCollect(Prompts.DETAILED, source(src), maxTokens = 1600)
        }
        val chunks = chunkTextCapped(src)
        val parts = chunks.map { streamCollect(Prompts.DETAILED, source(it), maxTokens = 1200) }
        return parts.joinToString("\n\n")
    }

    private suspend fun streamCollect(system: String, user: String, maxTokens: Int = 1024): String {
        val sb = StringBuilder()
        engine.stream(system, user, maxTokens).collect { token ->
            sb.append(token)
            // surface streaming text to the UI without disturbing phase counters
            val p = _progress.value
            _progress.value = p.copy(live = sb.toString())
        }
        return sb.toString()
    }

    private fun source(t: String) = "Source:\n\"\"\"\n$t\n\"\"\""

    // ── chunking, ported from pipeline._chunk_text / _hard_split ────────────────
    // Bounded chunking: pick a chunk size so the total number of chunks stays
    // under maxChunks, growing chunk size as needed up to maxChunkChars. If the
    // source is so large that even maxChunks * maxChunkChars can't hold it, we
    // process only that leading portion so a single job stays time-bounded on
    // CPU. This is what prevents the "condensing section 0 of 972" runaway.
    private fun chunkTextCapped(text: String): List<String> {
        val cap = maxChunks * maxChunkChars
        val material = if (text.length > cap) text.substring(0, cap) else text
        // size that fits the material into at most maxChunks pieces
        var size = (material.length + maxChunks - 1) / maxChunks
        if (size < chunkChars) size = chunkChars
        if (size > maxChunkChars) size = maxChunkChars
        val chunks = chunkText(material, size)
        // safety: if rounding produced more than maxChunks, keep only the first ones
        return if (chunks.size > maxChunks) chunks.take(maxChunks) else chunks
    }

    private fun chunkText(text: String, size: Int): List<String> {
        val chunks = ArrayList<String>()
        var cur = StringBuilder()
        for (line in text.split("\n")) {
            if (line.length > size) {
                if (cur.isNotEmpty()) { chunks.add(cur.toString()); cur = StringBuilder() }
                chunks.addAll(hardSplit(line, size))
                continue
            }
            if (cur.length + line.length + 1 > size && cur.isNotEmpty()) {
                chunks.add(cur.toString()); cur = StringBuilder(line)
            } else {
                if (cur.isEmpty()) cur.append(line) else cur.append("\n").append(line)
            }
        }
        if (cur.toString().isNotBlank()) chunks.add(cur.toString())
        return chunks
    }

    private fun hardSplit(s0: String, size: Int): List<String> {
        var s = s0
        val out = ArrayList<String>()
        while (s.length > size) {
            val window = s.substring(0, size)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "),
                            window.lastIndexOf("! "))
            if (cut < size * 0.5) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = size
            out.add(s.substring(0, cut + 1).trim())
            s = s.substring(cut + 1).trim()
        }
        if (s.isNotEmpty()) out.add(s)
        return out
    }
}
