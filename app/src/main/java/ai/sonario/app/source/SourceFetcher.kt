package ai.sonario.app.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * Turns a pasted link into text, the mobile analogue of sources.py.
 *
 * Desktop Sonario used youtube_transcript_api (a Python package). There is no
 * drop-in Android equivalent, so YouTube captions are fetched by querying
 * YouTube's internal InnerTube player API (with a watch-page scrape as fallback)
 * and parsing the returned timedtext track. This mirrors what that library does
 * internally, but it depends on undocumented endpoints and can break if YouTube
 * changes them. Web pages use Jsoup in place of BeautifulSoup, with the same
 * strip-then-prefer-article strategy.
 */
class SourceFetcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val UA = "Mozilla/5.0 (Linux; Android 14) Sonario/1.0"

    suspend fun fetch(raw: String): FetchedSource = withContext(Dispatchers.IO) {
        when (SourceDetect.detect(raw)) {
            SourceKind.YOUTUBE -> fetchYouTube(raw)
            SourceKind.URL -> fetchWeb(raw.trim())
            SourceKind.TEXT -> FetchedSource(raw.trim(), "Pasted text", "Text")
        }
    }

    // ── YouTube ───────────────────────────────────────────────────────────────
    private fun fetchYouTube(link: String): FetchedSource {
        val vid = SourceDetect.youtubeId(link)
            ?: return FetchedSource("", "", "YouTube video",
                error = "Could not read a video ID from that link.")

        val title = youtubeTitle(vid)

        // 1) Discover available caption tracks (InnerTube, then watch-page).
        val track = findCaptionTrack(vid)
            ?: return FetchedSource("", title, "YouTube video",
                error = "Couldn't find a transcript for this video. It may have " +
                        "captions turned off, or YouTube didn't return them. Try " +
                        "another video, or paste the transcript text directly.")

        // 2) Pull the timedtext track and flatten to text.
        val (text, minutes) = fetchTimedText(track)
        if (text.isBlank()) {
            return FetchedSource("", title, "YouTube video",
                error = "Found a caption track but couldn't read its contents. " +
                        "YouTube may have changed its format. Try another video.")
        }
        return FetchedSource(text, title.ifBlank { "YouTube video" },
            "YouTube video", approxMinutes = minutes)
    }

    /** Best-effort title via YouTube's public oEmbed endpoint (no key). */
    private fun youtubeTitle(vid: String): String = try {
        val url = "https://www.youtube.com/oembed?url=" +
            "https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$vid&format=json"
        val body = httpGet(url) ?: ""
        if (body.isBlank()) "" else JSONObject(body).optString("title", "").trim()
    } catch (_: Exception) { "" }

    /**
     * Find a caption track URL. YouTube increasingly withholds captionTracks from
     * the bare watch-page HTML, so we query the internal InnerTube player endpoint
     * (the same one the mobile app uses), which reliably returns caption metadata.
     * Falls back to scraping the watch page if InnerTube fails.
     */
    private fun findCaptionTrack(vid: String): String? {
        return findCaptionViaInnerTube(vid) ?: findCaptionViaWatchPage(vid)
    }

    private fun findCaptionViaInnerTube(vid: String): String? {
        // Public InnerTube key used by the web/mobile clients. The ANDROID client
        // context returns captionTracks without requiring a consent cookie.
        val apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        val url = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
        val payload = """
            {"context":{"client":{"clientName":"ANDROID","clientVersion":"19.09.37",
            "androidSdkVersion":34,"hl":"en","gl":"US"}},"videoId":"$vid"}
        """.trimIndent()
        val body = httpPostJson(url, payload) ?: return null
        return try {
            val root = JSONObject(body)
            val tracks = root.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks") ?: return null
            if (tracks.length() == 0) return null
            // prefer English
            var chosen: String? = null
            var firstUrl: String? = null
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val baseUrl = t.optString("baseUrl", "")
                if (baseUrl.isBlank()) continue
                if (firstUrl == null) firstUrl = baseUrl
                val lang = t.optString("languageCode", "")
                if (lang.startsWith("en")) { chosen = baseUrl; break }
            }
            val base = (chosen ?: firstUrl) ?: return null
            if (base.contains("fmt=")) base else "$base&fmt=json3"
        } catch (_: Exception) { null }
    }

    /** Fallback: parse the watch page's player config for a caption track URL. */
    private fun findCaptionViaWatchPage(vid: String): String? {
        val page = httpGet("https://www.youtube.com/watch?v=$vid&hl=en") ?: return null
        val m = Regex(""""captionTracks":(\[.*?\])""").find(page) ?: return null
        val arr = m.groupValues[1]
        val urls = Regex(""""baseUrl":"(.*?)"""").findAll(arr)
            .map { it.groupValues[1].replace("\\u0026", "&").replace("\\/", "/") }
            .toList()
        if (urls.isEmpty()) return null
        val langs = Regex(""""languageCode":"(.*?)"""").findAll(arr)
            .map { it.groupValues[1] }.toList()
        val enIdx = langs.indexOfFirst { it.startsWith("en") }
        val base = if (enIdx >= 0 && enIdx < urls.size) urls[enIdx] else urls[0]
        return if (base.contains("fmt=")) base else "$base&fmt=json3"
    }

    private fun fetchTimedText(url: String): Pair<String, Int> {
        val body = httpGet(url) ?: return "" to 0
        if (body.isBlank()) return "" to 0
        // Try JSON3 first (events/segs), then fall back to XML (<text> tags).
        val json = parseJson3(body)
        if (json.first.isNotBlank()) return json
        return parseXmlCaptions(body)
    }

    private fun parseJson3(body: String): Pair<String, Int> {
        return try {
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return "" to 0
            val sb = StringBuilder()
            var lastMs = 0L
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                lastMs = maxOf(lastMs, ev.optLong("tStartMs", lastMs))
                val segs = ev.optJSONArray("segs") ?: continue
                for (j in 0 until segs.length()) {
                    val t = segs.optJSONObject(j)?.optString("utf8") ?: continue
                    if (t.isNotBlank()) sb.append(t)
                }
                sb.append(' ')
            }
            val text = sb.toString().replace(Regex("""\s+"""), " ").trim()
            text to (lastMs / 60000L).toInt()
        } catch (_: Exception) { "" to 0 }
    }

    /** XML transcript fallback: <text start="12.3" ...>caption</text> */
    private fun parseXmlCaptions(body: String): Pair<String, Int> {
        return try {
            val matches = Regex("""<text[^>]*start="([\d.]+)"[^>]*>(.*?)</text>""",
                RegexOption.DOT_MATCHES_ALL).findAll(body).toList()
            if (matches.isEmpty()) return "" to 0
            val sb = StringBuilder()
            var lastSec = 0.0
            for (m in matches) {
                lastSec = maxOf(lastSec, m.groupValues[1].toDoubleOrNull() ?: 0.0)
                val raw = m.groupValues[2]
                    .replace("&amp;", "&").replace("&#39;", "'")
                    .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                    .replace(Regex("<[^>]+>"), " ")
                if (raw.isNotBlank()) sb.append(raw).append(' ')
            }
            val text = sb.toString().replace(Regex("""\s+"""), " ").trim()
            text to (lastSec / 60.0).toInt()
        } catch (_: Exception) { "" to 0 }
    }

    // ── Web page ────────────────────────────────────────────────────────────────
    // Ported from sources.fetch_webpage: strip non-content, prefer <article>/<main>,
    // collect block-level text, fall back to whole-page text if too short.
    private fun fetchWeb(url: String): FetchedSource {
        val html = httpGet(url)
            ?: return FetchedSource("", url, "Web page",
                error = "Could not retrieve that page.")
        val doc = Jsoup.parse(html, url)
        val title = doc.title().ifBlank { url }
        doc.select("script,style,nav,header,footer,aside,form,noscript,iframe,svg").remove()
        val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val blocks = main.select("h1,h2,h3,p,li,blockquote")
            .map { it.text().trim() }
            .filter { it.length > 1 }
        var text = blocks.joinToString("\n").trim()
        if (text.length < 80) text = main.text().trim()
        return if (text.isBlank())
            FetchedSource("", title, "Web page", error = "No readable content found.")
        else FetchedSource(text, title, "Web page")
    }

    private fun httpGet(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }

    private fun httpPostJson(url: String, json: String): String? = try {
        val media = "application/json; charset=utf-8".toMediaTypeOrNull()
        val req = Request.Builder().url(url)
            .header("User-Agent",
                "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(media))
            .build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }
}
