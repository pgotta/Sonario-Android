package ai.sonario.app.data

import android.content.Context
import ai.sonario.app.source.FileTextExtractor
import ai.sonario.app.summarize.SummarizeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable local history and resumable checkpoints for summaries. */
enum class SessionStatus { RUNNING, COMPLETE, FAILED, CANCELLED }

data class StoredQa(val question: String, val answer: String)

data class SummarySession(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val status: SessionStatus = SessionStatus.RUNNING,
    val input: String = "",
    val title: String = "Untitled summary",
    val kind: String = "Source",
    val approxMinutes: Int? = null,
    val sourceText: String = "",
    val chapters: List<FileTextExtractor.Chapter> = emptyList(),
    val engineChoice: EngineChoice = EngineChoice.GROQ,
    val modelFileName: String = "",
    val groqModel: String = Settings.DEFAULT_GROQ_MODEL,
    val phase: String = "fetching",
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val checkpoint: SummarizeEngine.Checkpoint = SummarizeEngine.Checkpoint(),
    val result: SummarizeEngine.Result? = null,
    val qaHistory: List<StoredQa> = emptyList(),
    val error: String? = null,
)

data class SessionPreview(
    val id: String,
    val title: String,
    val kind: String,
    val status: SessionStatus,
    val updatedAt: Long,
    val phase: String,
    val progressCurrent: Int,
    val progressTotal: Int,
    val canResume: Boolean,
)

/**
 * Stores each session in filesDir/sonario_sessions/<id>/.
 *
 * The source text is kept in its own file so checkpoint updates do not rewrite a
 * potentially huge transcript. Metadata writes are atomic (temporary file + rename).
 */
class SessionStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "sonario_sessions")
        .apply { mkdirs() }

    @Synchronized
    fun save(session: SummarySession): SummarySession {
        val normalized = session.copy(updatedAt = System.currentTimeMillis())
        val dir = sessionDir(normalized.id).apply { mkdirs() }

        if (normalized.sourceText.isNotBlank()) {
            val source = File(dir, SOURCE_FILE)
            if (!source.exists()) atomicWrite(source, normalized.sourceText)
        }
        if (normalized.chapters.isNotEmpty()) {
            val chapterFile = File(dir, CHAPTERS_FILE)
            if (!chapterFile.exists()) {
                val array = JSONArray()
                normalized.chapters.forEach { chapter ->
                    array.put(JSONObject()
                        .put("title", chapter.title)
                        .put("text", chapter.text))
                }
                atomicWrite(chapterFile, array.toString())
            }
        }

        atomicWrite(File(dir, META_FILE), toJson(normalized).toString())
        prune(MAX_SESSIONS)
        return normalized
    }

    @Synchronized
    fun load(id: String): SummarySession? {
        val dir = sessionDir(id)
        val meta = File(dir, META_FILE)
        if (!meta.isFile) return null
        return runCatching {
            val source = File(dir, SOURCE_FILE).takeIf { it.isFile }?.readText().orEmpty()
            val chapters = readChapters(File(dir, CHAPTERS_FILE))
            fromJson(JSONObject(meta.readText()), source, chapters)
        }.getOrNull()
    }

    @Synchronized
    fun list(): List<SummarySession> = root.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.mapNotNull { load(it.name) }
        ?.sortedByDescending { it.updatedAt }
        ?.toList()
        ?: emptyList()

    @Synchronized
    fun previews(): List<SessionPreview> = root.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            runCatching {
                val o = JSONObject(File(dir, META_FILE).readText())
                val status = runCatching {
                    SessionStatus.valueOf(o.optString("status"))
                }.getOrDefault(SessionStatus.FAILED)
                val hasSource = File(dir, SOURCE_FILE).isFile &&
                    File(dir, SOURCE_FILE).length() > 0L
                val hasResult = !o.isNull("result")
                SessionPreview(
                    id = o.getString("id"),
                    title = o.optString("title", "Untitled summary"),
                    kind = o.optString("kind", "Source"),
                    status = status,
                    updatedAt = o.optLong("updatedAt", 0L),
                    phase = o.optString("phase", ""),
                    progressCurrent = o.optInt("progressCurrent"),
                    progressTotal = o.optInt("progressTotal"),
                    canResume = hasSource && !hasResult,
                )
            }.getOrNull()
        }
        ?.sortedByDescending { it.updatedAt }
        ?.toList()
        ?: emptyList()

    @Synchronized
    fun delete(id: String) {
        sessionDir(id).deleteRecursively()
    }

    /**
     * Permanently removes every saved session and its private source data.
     *
     * Each session directory contains the saved transcript/source text,
     * chapter data, summary/checkpoint metadata, and question history, so
     * deleting the directory tree removes the complete local session record.
     */
    @Synchronized
    fun clearAll() {
        root.listFiles()?.forEach { child -> child.deleteRecursively() }
        root.mkdirs()
    }

    private fun readChapters(file: File): List<FileTextExtractor.Chapter> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(FileTextExtractor.Chapter(
                        title = obj.optString("title", "Chapter ${i + 1}"),
                        text = obj.optString("text", ""),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun toJson(s: SummarySession): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("id", s.id)
        .put("createdAt", s.createdAt)
        .put("updatedAt", s.updatedAt)
        .put("status", s.status.name)
        .put("input", s.input)
        .put("title", s.title)
        .put("kind", s.kind)
        .put("approxMinutes", s.approxMinutes ?: JSONObject.NULL)
        .put("engineChoice", s.engineChoice.name)
        .put("modelFileName", s.modelFileName)
        .put("groqModel", s.groqModel)
        .put("phase", s.phase)
        .put("progressCurrent", s.progressCurrent)
        .put("progressTotal", s.progressTotal)
        .put("checkpoint", checkpointToJson(s.checkpoint))
        .put("result", s.result?.let(::resultToJson) ?: JSONObject.NULL)
        .put("qaHistory", JSONArray().apply {
            s.qaHistory.forEach { qa ->
                put(JSONObject().put("question", qa.question).put("answer", qa.answer))
            }
        })
        .put("error", s.error ?: JSONObject.NULL)

    private fun fromJson(
        o: JSONObject,
        sourceText: String,
        chapters: List<FileTextExtractor.Chapter>,
    ): SummarySession {
        val qa = o.optJSONArray("qaHistory")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val q = array.getJSONObject(i)
                    add(StoredQa(q.optString("question"), q.optString("answer")))
                }
            }
        }.orEmpty()
        return SummarySession(
            id = o.getString("id"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            status = runCatching { SessionStatus.valueOf(o.optString("status")) }
                .getOrDefault(SessionStatus.FAILED),
            input = o.optString("input"),
            title = o.optString("title", "Untitled summary"),
            kind = o.optString("kind", "Source"),
            approxMinutes = if (o.isNull("approxMinutes")) null else o.optInt("approxMinutes"),
            sourceText = sourceText,
            chapters = chapters,
            engineChoice = runCatching { EngineChoice.valueOf(o.optString("engineChoice")) }
                .getOrDefault(EngineChoice.GROQ),
            modelFileName = o.optString("modelFileName"),
            groqModel = o.optString("groqModel", Settings.DEFAULT_GROQ_MODEL),
            phase = o.optString("phase", ""),
            progressCurrent = o.optInt("progressCurrent"),
            progressTotal = o.optInt("progressTotal"),
            checkpoint = o.optJSONObject("checkpoint")?.let(::checkpointFromJson)
                ?: SummarizeEngine.Checkpoint(),
            result = if (o.isNull("result")) null else o.optJSONObject("result")?.let(::resultFromJson),
            qaHistory = qa,
            error = if (o.isNull("error")) null else o.optString("error"),
        )
    }

    private fun checkpointToJson(c: SummarizeEngine.Checkpoint) = JSONObject()
        .put("notes", JSONArray(c.notes))
        .put("normal", c.normal)
        .put("bullets", c.bullets)
        .put("detailed", c.detailed)
        .put("detailedParts", JSONArray(c.detailedParts))
        .put("chapterParts", JSONArray(c.chapterParts))

    private fun checkpointFromJson(o: JSONObject) = SummarizeEngine.Checkpoint(
        notes = o.optJSONArray("notes").toStringList(),
        normal = o.optString("normal"),
        bullets = o.optString("bullets"),
        detailed = o.optString("detailed"),
        detailedParts = o.optJSONArray("detailedParts").toStringList(),
        chapterParts = o.optJSONArray("chapterParts").toStringList(),
    )

    private fun resultToJson(r: SummarizeEngine.Result) = JSONObject()
        .put("normal", r.normal)
        .put("detailed", r.detailed)
        .put("bullets", r.bullets)
        .put("title", r.title)
        .put("kind", r.kind)
        .put("approxMinutes", r.approxMinutes ?: JSONObject.NULL)
        .put("chapters", r.chapters)

    private fun resultFromJson(o: JSONObject) = SummarizeEngine.Result(
        normal = o.optString("normal"),
        detailed = o.optString("detailed"),
        bullets = o.optString("bullets"),
        title = o.optString("title", "Untitled summary"),
        kind = o.optString("kind", "Source"),
        approxMinutes = if (o.isNull("approxMinutes")) null else o.optInt("approxMinutes"),
        chapters = o.optString("chapters"),
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) add(optString(i))
        }
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    private fun prune(keep: Int) {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
            .sortedByDescending { File(it, META_FILE).lastModified() }
        dirs.drop(keep).forEach { it.deleteRecursively() }
    }

    private fun sessionDir(id: String) = File(root, id)

    companion object {
        private const val SCHEMA = 1
        private const val MAX_SESSIONS = 12
        private const val META_FILE = "session.json"
        private const val SOURCE_FILE = "source.txt"
        private const val CHAPTERS_FILE = "chapters.json"
    }
}
