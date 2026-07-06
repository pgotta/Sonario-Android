package ai.sonario.app.source

/** What kind of input the user pasted. Mirrors sources.detect_kind. */
enum class SourceKind { YOUTUBE, URL, TEXT }

data class FetchedSource(
    val text: String,
    val title: String,
    val kind: String,        // human label: "YouTube video", "Web page", "Text"
    val approxMinutes: Int? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && text.isNotBlank()
}

object SourceDetect {
    // Ported from sources._YT_RE
    private val YT = Regex(
        """(?:youtube\.com/(?:watch\?v=|embed/|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""")

    fun detect(raw: String): SourceKind {
        val t = raw.trim()
        if (YT.containsMatchIn(t)) return SourceKind.YOUTUBE
        if (Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return SourceKind.URL
        return SourceKind.TEXT
    }

    /** Ported from sources.youtube_id. */
    fun youtubeId(link: String): String? {
        YT.find(link)?.let { return it.groupValues[1] }
        val t = link.trim()
        return if (Regex("""^[A-Za-z0-9_-]{11}$""").matches(t)) t else null
    }
}
