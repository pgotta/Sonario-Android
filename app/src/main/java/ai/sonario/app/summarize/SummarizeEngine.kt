package ai.sonario.app.summarize

import ai.sonario.app.llm.InferenceEngine
import ai.sonario.app.llm.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

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
 * the Groq cloud engine. Cloud chunks are intentionally much smaller than
 * Qwen's 131k context window because Groq's free tier limits the organization
 * to 8k tokens per minute. Each individual request must fit comfortably inside
 * that minute budget before the rate limiter queues the next request.
 */
class SummarizeEngine(
    private val engine: InferenceEngine,
    private val bigContext: Boolean = false,
) {

    // Qwen cloud calls stay below the 8K TPM ceiling even after prompt and output
    // tokens are included. On-device chunks remain small for CPU performance.
    private val chunkChars = if (bigContext) 14000 else 2800
    private val singlePassLimit = if (bigContext) 16000 else 3200

    // Fourteen cloud chunks keep a full Normal + Detailed run within roughly one
    // free-tier daily allowance while still covering long videos/documents.
    private val maxChunks = if (bigContext) 14 else 20
    private val maxChunkChars = if (bigContext) 16000 else 6000

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
        val chapters: String = "",   // per-chapter EPUB summary, empty if N/A
    )

    /**
     * Durable stage output. Saving this after each completed model call means a
     * killed activity/process can resume without paying for finished chunks again.
     */
    data class Checkpoint(
        val notes: List<String> = emptyList(),
        val normal: String = "",
        val bullets: String = "",
        val detailed: String = "",
        val detailedParts: List<String> = emptyList(),
        val chapterParts: List<String> = emptyList(),
    )

    private val _progress = MutableStateFlow(Progress("idle"))
    val progress: StateFlow<Progress> = _progress

    suspend fun run(
        model: ModelInfo,
        text: String,
        title: String,
        kind: String,
        approxMinutes: Int?,
        initialCheckpoint: Checkpoint = Checkpoint(),
        onCheckpoint: suspend (Checkpoint) -> Unit = {},
    ): Result {
        engine.ensureReady(model)
        var checkpoint = initialCheckpoint

        val normal: String
        if (checkpoint.normal.isNotBlank()) {
            normal = checkpoint.normal
        } else if (text.length <= singlePassLimit) {
            // Single pass — at worst this one call is repeated after an interruption.
            _progress.value = Progress("synthesizing", 1, 1)
            normal = Cleaner.clean(streamCollect(Prompts.SUMMARY, source(text)))
            checkpoint = checkpoint.copy(normal = normal)
            onCheckpoint(checkpoint)
        } else {
            // Resume the map stage at the first unfinished chunk.
            val chunks = chunkTextCapped(text)
            val notes = checkpoint.notes.take(chunks.size).toMutableList()
            _progress.value = Progress("condensing", notes.size, chunks.size)
            for (i in notes.size until chunks.size) {
                val note = streamCollect(Prompts.CHUNK, chunks[i])
                notes.add(Cleaner.clean(note))
                checkpoint = checkpoint.copy(notes = notes.toList())
                onCheckpoint(checkpoint)
                _progress.value = Progress("condensing", i + 1, chunks.size)
            }

            _progress.value = Progress("synthesizing", chunks.size, chunks.size)
            val joined = notes.joinToString("\n\n")
            normal = Cleaner.clean(finalCombine(joined))
            checkpoint = checkpoint.copy(normal = normal, notes = notes.toList())
            onCheckpoint(checkpoint)
        }

        _progress.value = Progress("deriving")
        val bullets = if (checkpoint.bullets.isNotBlank()) {
            checkpoint.bullets
        } else {
            Cleaner.clean(streamCollect(Prompts.BULLETS, normal)).also {
                checkpoint = checkpoint.copy(bullets = it)
                onCheckpoint(checkpoint)
            }
        }

        val detailedSource = when {
            text.length <= singlePassLimit -> text
            bigContext -> text
            else -> normal
        }
        val detailed = if (checkpoint.detailed.isNotBlank()) {
            checkpoint.detailed
        } else {
            buildDetailed(
                src = detailedSource,
                existingParts = checkpoint.detailedParts,
                onParts = { parts ->
                    checkpoint = checkpoint.copy(detailedParts = parts)
                    onCheckpoint(checkpoint)
                },
            ).also {
                checkpoint = checkpoint.copy(detailed = it)
                onCheckpoint(checkpoint)
            }
        }

        _progress.value = Progress("done")
        return Result(normal, detailed, bullets, title, kind, approxMinutes)
    }

    /**
     * Per-chapter summary for EPUBs. Produces a one-line whole-book overview at
     * the top, then a short summary under each chapter title. Returned as Markdown
     * for the Chapter view. Runs after the main summary so the overview can reuse
     * the finished [bookOverview] (the Normal summary's gist line).
     */
    suspend fun summarizeChapters(
        chapters: List<ai.sonario.app.source.FileTextExtractor.Chapter>,
        bookOverview: String,
        existingParts: List<String> = emptyList(),
        onCheckpoint: suspend (List<String>) -> Unit = {},
    ): String {
        if (chapters.isEmpty()) return ""
        val parts = existingParts.take(chapters.size).toMutableList()
        for (i in parts.size until chapters.size) {
            val ch = chapters[i]
            _progress.value = Progress("chapters", i + 1, chapters.size)
            val summary = runCatching {
                val boundedChapter = ch.text.take(singlePassLimit)
                Cleaner.clean(
                    streamCollect(Prompts.CHAPTER, source(boundedChapter), maxTokens = 300)
                )
            }.getOrDefault("")
            val section = buildString {
                append("## ${ch.title}\n\n")
                append(if (summary.isNotBlank()) summary else "(Couldn't summarize this chapter.)")
            }
            parts.add(section)
            onCheckpoint(parts.toList())
        }

        val sb = StringBuilder()
        val gist = bookOverview.lineSequence()
            .map { it.trim().removePrefix("**").removeSuffix("**").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.take(300)
        if (!gist.isNullOrBlank()) sb.append("**$gist**\n\n")
        sb.append(parts.joinToString("\n\n"))
        return sb.toString().trimEnd()
    }

    /**
     * Answer a question grounded in the source text, with inline [n] citations.
     * The source is chunked into numbered excerpts so the model can cite them;
     * for very long sources we select relevant passages from across the source.
     */
    suspend fun answer(question: String, sourceText: String): String {
        _progress.value = Progress("answering", 0, 1)

        // Keep the Qwen request under the same free-tier TPM budget used by the
        // summary pipeline while still sampling relevant material across the source.
        val excerpts = selectRelevantExcerpts(question, sourceText)
        val numbered = excerpts.mapIndexed { i, excerpt ->
            "[${i + 1}] $excerpt"
        }.joinToString("\n\n")
        val user = "Source excerpts:\n\n$numbered\n\nQuestion: $question"

        val answer = Cleaner.clean(
            streamCollect(
                Prompts.ASK,
                user,
                maxTokens = if (bigContext) 1000 else 700,
            )
        )
        _progress.value = Progress("done")
        if (answer.isBlank()) {
            throw IllegalStateException("The model returned an empty answer.")
        }
        return answer
    }

    private fun selectRelevantExcerpts(question: String, sourceText: String): List<String> {
        val excerptSize = if (bigContext) 1400 else 1000
        val maxExcerpts = if (bigContext) 8 else 6
        val all = chunkText(sourceText, excerptSize)
        if (all.isEmpty()) return listOf(sourceText.take(excerptSize))
        if (all.size <= maxExcerpts) return all

        val terms = WORD_REGEX.findAll(question.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in ASK_STOP_WORDS }
            .distinct()
            .toList()

        // With no useful keywords, use evenly spaced coverage rather than only the
        // start of the source.
        if (terms.isEmpty()) {
            return evenlySpaced(all, maxExcerpts)
        }

        val scored = all.mapIndexed { index, chunk ->
            val lower = chunk.lowercase()
            var score = 0.0
            for (term in terms) {
                var from = 0
                var occurrences = 0
                while (true) {
                    val found = lower.indexOf(term, from)
                    if (found < 0) break
                    occurrences++
                    from = found + term.length
                }
                score += occurrences * (1.0 + (term.length.coerceAtMost(10) / 10.0))
            }
            // Small coverage bonuses prevent all excerpts from clustering around
            // one repeated phrase and preserve some beginning/end context.
            if (index == 0 || index == all.lastIndex) score += 0.35
            Triple(index, chunk, score)
        }

        val top = scored
            .sortedWith(compareByDescending<Triple<Int, String, Double>> { it.third }
                .thenBy { it.first })
            .take(maxExcerpts)
            .sortedBy { it.first }
            .map { it.second }

        return if (top.any { chunk -> terms.any { it in chunk.lowercase() } }) {
            top
        } else {
            evenlySpaced(all, maxExcerpts)
        }
    }

    private fun evenlySpaced(chunks: List<String>, count: Int): List<String> {
        if (chunks.size <= count) return chunks
        if (count <= 1) return listOf(chunks.first())
        val indexes = (0 until count).map { i ->
            ((i.toDouble() * (chunks.lastIndex)) / (count - 1)).toInt()
        }.distinct()
        return indexes.map { chunks[it] }
    }

    // ── stages ──────────────────────────────────────────────────────────────────
    /** pipeline._final_combine, simplified: one-shot, else hierarchical batches. */
    private suspend fun finalCombine(joined: String): String {
        // A one-shot combine is only safe when the notes themselves fit the
        // per-request free-tier token budget.
        if (joined.length <= singlePassLimit) {
            runCatching {
                return streamCollect(Prompts.REDUCE, "Section notes, in order:\n\n$joined")
            }
        }

        // Hierarchical: batch the note-blocks, condense each, then combine.
        val blocks = joined.split("\n\n").filter { it.isNotBlank() }
        val partials = ArrayList<String>()
        var i = 0
        while (i < blocks.size) {
            val batch = blocks.subList(i, minOf(i + 5, blocks.size)).joinToString("\n\n")
            partials.add(
                runCatching { streamCollect(Prompts.CHUNK, batch) }
                    .getOrDefault(batch.take(1500))
            )
            i += 5
        }
        val small = partials.joinToString("\n\n").take(singlePassLimit)
        runCatching {
            return streamCollect(Prompts.REDUCE, "Section notes, in order:\n\n$small")
        }
        // Pure-local fallback: never throw away a finished run.
        return "**Combined from section notes.**\n\n$small"
    }

    /** pipeline._build_detailed: single pass when it fits, else batch then merge. */
    private suspend fun buildDetailed(
        src: String,
        existingParts: List<String> = emptyList(),
        onParts: suspend (List<String>) -> Unit = {},
    ): String {
        val onePassCap = if (bigContext) 2200 else 1600
        val chunkCap = if (bigContext) 1600 else 1200
        val onePassSourceLimit = if (bigContext) singlePassLimit else singlePassLimit * 2
        if (src.length <= onePassSourceLimit) {
            return streamCollect(Prompts.DETAILED, source(src), maxTokens = onePassCap)
        }
        val chunks = chunkTextCapped(src)
        val parts = existingParts.take(chunks.size).toMutableList()
        for (i in parts.size until chunks.size) {
            parts.add(streamCollect(Prompts.DETAILED, source(chunks[i]), maxTokens = chunkCap))
            onParts(parts.toList())
        }
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
    // process only that leading portion so a single job stays within its daily
    // work budget.
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
                if (cur.isNotEmpty()) {
                    chunks.add(cur.toString())
                    cur = StringBuilder()
                }
                chunks.addAll(hardSplit(line, size))
                continue
            }
            if (cur.length + line.length + 1 > size && cur.isNotEmpty()) {
                chunks.add(cur.toString())
                cur = StringBuilder(line)
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
            var cut = maxOf(
                window.lastIndexOf(". "),
                window.lastIndexOf("? "),
                window.lastIndexOf("! "),
            )
            if (cut < size * 0.5) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = size
            out.add(s.substring(0, cut + 1).trim())
            s = s.substring(cut + 1).trim()
        }
        if (s.isNotEmpty()) out.add(s)
        return out
    }

    companion object {
        private val WORD_REGEX = Regex("[\\p{L}\\p{N}']+")
        private val ASK_STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "with", "from", "what", "when",
            "where", "which", "who", "why", "how", "does", "did", "was", "were",
            "are", "is", "can", "could", "would", "should", "about", "into", "than",
            "then", "they", "them", "their", "there", "have", "has", "had", "you",
            "your", "its", "but", "not", "all", "any", "some", "more", "most",
        )
    }
}
