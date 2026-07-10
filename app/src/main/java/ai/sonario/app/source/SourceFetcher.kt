package ai.sonario.app.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Turns a pasted link into readable text.
 *
 * YouTube extraction deliberately uses more than one route:
 *  1. WEB /youtubei/v1/next -> /get_transcript (preferred; transcript is JSON)
 *  2. ANDROID /youtubei/v1/player -> captionTracks -> timedtext
 *  3. captionTracks embedded in the watch page
 *
 * The old implementation generated a fake get_transcript protobuf, mixed WEB
 * request bodies with an Android User-Agent, used stale client versions, and
 * truncated signed caption URLs when replacing fmt=. Those issues could yield
 * HTTP 200 with an empty body even when the video visibly had captions.
 */
class SourceFetcher {

    private val cookieJar = MemoryCookieJar()

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(raw: String): FetchedSource = withContext(Dispatchers.IO) {
        when (SourceDetect.detect(raw)) {
            SourceKind.YOUTUBE -> fetchYouTube(raw)
            SourceKind.URL -> fetchWeb(raw.trim())
            SourceKind.TEXT -> FetchedSource(raw.trim(), "Pasted text", "Text")
        }
    }

    // ── YouTube ───────────────────────────────────────────────────────────────

    private fun fetchYouTube(link: String): FetchedSource {
        val videoId = SourceDetect.youtubeId(link)
            ?: return FetchedSource(
                text = "",
                title = "",
                kind = "YouTube video",
                error = "Could not read a YouTube video ID from that link.",
            )

        lastCaptionDiag = ""
        val diagnostics = mutableListOf<String>()

        val bootstrap = fetchYouTubeBootstrap(videoId, diagnostics)
        var title = bootstrap?.title.orEmpty()

        // Preferred path: obtain YouTube's real getTranscriptEndpoint.params,
        // never construct or guess it from the video ID.
        if (bootstrap != null) {
            val transcript = fetchViaGetTranscript(videoId, bootstrap, diagnostics)
            if (transcript.text.isNotBlank()) {
                return FetchedSource(
                    text = transcript.text,
                    title = title.ifBlank { "YouTube video" },
                    kind = "YouTube video",
                    approxMinutes = transcript.minutes,
                )
            }
        }

        // Maintained Android client identity used by youtube-transcript-api.
        // The payload and headers intentionally identify the same client.
        val androidPlayer = fetchAndroidPlayer(videoId, bootstrap, diagnostics)
        if (title.isBlank()) title = androidPlayer?.title.orEmpty()

        // A correctly identified WEB player is a useful fallback. The previous
        // build claimed to be WEB in JSON while sending an Android app UA.
        val webPlayer = if (bootstrap != null && androidPlayer?.tracks.isNullOrEmpty()) {
            fetchWebPlayer(videoId, bootstrap, diagnostics)
        } else {
            null
        }
        if (title.isBlank()) title = webPlayer?.title.orEmpty()

        val tracks = buildList {
            androidPlayer?.let { addAll(it.tracks) }
            webPlayer?.let { addAll(it.tracks) }
            if (isEmpty() && bootstrap != null) {
                addAll(extractCaptionTracksFromWatchHtml(bootstrap.html, diagnostics))
            }
        }.distinctBy { "${it.url}|${it.languageCode}" }

        if (tracks.isNotEmpty()) {
            for (track in rankCaptionTracks(tracks)) {
                val result = fetchTimedText(
                    rawBase = track.url,
                    referer = watchUrl(videoId),
                    diagnostics = diagnostics,
                    label = "${track.languageCode}${if (track.generated) "/auto" else "/manual"}",
                )
                if (result.text.isNotBlank()) {
                    return FetchedSource(
                        text = result.text,
                        title = title.ifBlank { "YouTube video" },
                        kind = "YouTube video",
                        approxMinutes = result.minutes,
                    )
                }
            }

            val detail = diagnostics.joinToString("; ").take(MAX_DIAGNOSTIC_LENGTH)
            lastCaptionDiag = detail
            return FetchedSource(
                text = "",
                title = title.ifBlank { "YouTube video" },
                kind = "YouTube video",
                error = "YouTube reported caption tracks for this video, but returned no " +
                    "usable transcript data to the app.\n\n" +
                    "Extractor build $EXTRACTOR_BUILD diagnostics: $detail",
            )
        }

        val detail = diagnostics.joinToString("; ").take(MAX_DIAGNOSTIC_LENGTH)
        lastCaptionDiag = detail
        return FetchedSource(
            text = "",
            title = title.ifBlank { "YouTube video" },
            kind = "YouTube video",
            error = "Could not retrieve an accessible YouTube transcript for this request. " +
                "The video may require sign-in, a Proof-of-Origin token, or YouTube may have " +
                "rejected this client session.\n\n" +
                "Extractor build $EXTRACTOR_BUILD diagnostics: $detail",
        )
    }

    private data class YouTubeBootstrap(
        val html: String,
        val apiKey: String,
        val webClientVersion: String,
        val visitorData: String,
        val title: String,
    )

    private data class TranscriptResult(
        val text: String = "",
        val minutes: Int = 0,
    )

    private data class CaptionTrack(
        val url: String,
        val languageCode: String,
        val name: String,
        val generated: Boolean,
    )

    private data class PlayerResult(
        val title: String,
        val tracks: List<CaptionTrack>,
    )

    private data class TranscriptSegment(
        val text: String,
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * Fetches a normal watch page, keeps YouTube cookies in one OkHttp session,
     * and resolves the consent form once when present.
     */
    private fun fetchYouTubeBootstrap(
        videoId: String,
        diagnostics: MutableList<String>,
    ): YouTubeBootstrap? {
        var response = youtubeGet(watchUrl(videoId), DESKTOP_UA)
        diagnostics += "watch=${response.code}/${response.body.length}"
        if (response.code !in 200..299 || response.body.isBlank()) return null

        if (isConsentPage(response.body)) {
            val consentValue = extractConsentValue(response.body)
            if (consentValue.isNullOrBlank()) {
                diagnostics += "consent=value-missing"
                return null
            }
            cookieJar.put(
                Cookie.Builder()
                    .name("CONSENT")
                    .value("YES+$consentValue")
                    .domain("youtube.com")
                    .path("/")
                    .build(),
            )
            // SOCS=CAI is the current minimal "accepted" consent state. The
            // CONSENT cookie above remains the important compatibility cookie.
            cookieJar.put(
                Cookie.Builder()
                    .name("SOCS")
                    .value("CAI")
                    .domain("youtube.com")
                    .path("/")
                    .build(),
            )
            response = youtubeGet(watchUrl(videoId), DESKTOP_UA)
            diagnostics += "watch-after-consent=${response.code}/${response.body.length}"
            if (response.code !in 200..299 || response.body.isBlank() ||
                isConsentPage(response.body)
            ) {
                diagnostics += "consent=failed"
                return null
            }
        }

        if (response.body.contains("class=\"g-recaptcha\"", ignoreCase = true)) {
            diagnostics += "watch=recaptcha"
            return null
        }

        val contextClient = extractInnertubeContextClient(response.body)
        val apiKey = extractJsonConfigString(response.body, "INNERTUBE_API_KEY")
            ?: PUBLIC_INNERTUBE_KEY
        val clientVersion = extractJsonConfigString(response.body, "INNERTUBE_CLIENT_VERSION")
            ?: extractJsonConfigString(response.body, "INNERTUBE_CONTEXT_CLIENT_VERSION")
            ?: contextClient?.optString("clientVersion")?.takeIf { it.isNotBlank() }
            ?: FALLBACK_WEB_CLIENT_VERSION
        val visitorData = extractJsonConfigString(response.body, "VISITOR_DATA")
            ?: contextClient?.optString("visitorData")
            ?: ""
        val title = extractTitleFromWatchHtml(response.body)

        diagnostics += "bootstrap=key:${if (apiKey == PUBLIC_INNERTUBE_KEY) "fallback" else "page"}" +
            ",web:$clientVersion,visitor:${visitorData.isNotBlank()}"

        return YouTubeBootstrap(
            html = response.body,
            apiKey = apiKey,
            webClientVersion = clientVersion,
            visitorData = visitorData,
            title = title,
        )
    }

    /**
     * Uses the real transcript continuation token. We first look in watch-page
     * JSON, then ask /next, where YouTube normally exposes the transcript panel.
     */
    private fun fetchViaGetTranscript(
        videoId: String,
        bootstrap: YouTubeBootstrap,
        diagnostics: MutableList<String>,
    ): TranscriptResult {
        return try {
            var params = extractGetTranscriptParams(bootstrap.html)
            if (params.isNullOrBlank()) {
                val nextPayload = JSONObject()
                    .put("context", webContext(bootstrap))
                    .put("videoId", videoId)

                val nextResponse = youtubePostJson(
                    url = youtubeiUrl("next", bootstrap.apiKey),
                    json = nextPayload,
                    userAgent = DESKTOP_UA,
                    clientNameHeader = WEB_CLIENT_NAME_HEADER,
                    clientVersion = bootstrap.webClientVersion,
                    visitorData = bootstrap.visitorData,
                    referer = watchUrl(videoId),
                    includeWebHeaders = true,
                )
                diagnostics += "next=${nextResponse.code}/${nextResponse.body.length}"
                if (nextResponse.code in 200..299 && nextResponse.body.isNotBlank()) {
                    params = findTranscriptParams(JSONObject(nextResponse.body))
                }
            } else {
                diagnostics += "transcript-params=watch"
            }

            if (params.isNullOrBlank()) {
                diagnostics += "transcript-params=missing"
                return TranscriptResult()
            }

            val payload = JSONObject()
                .put("context", webContext(bootstrap))
                .put("params", params)

            val response = youtubePostJson(
                url = youtubeiUrl("get_transcript", bootstrap.apiKey),
                json = payload,
                userAgent = DESKTOP_UA,
                clientNameHeader = WEB_CLIENT_NAME_HEADER,
                clientVersion = bootstrap.webClientVersion,
                visitorData = bootstrap.visitorData,
                referer = watchUrl(videoId),
                includeWebHeaders = true,
            )
            diagnostics += "get-transcript=${response.code}/${response.body.length}"
            if (response.code !in 200..299 || response.body.isBlank()) {
                return TranscriptResult()
            }

            val segments = mutableListOf<TranscriptSegment>()
            collectTranscriptSegments(JSONObject(response.body), segments)
            val cleaned = deduplicateSegments(segments)
            val text = cleaned.joinToString(" ") { it.text }.normalizeWhitespace()
            val lastMs = cleaned.maxOfOrNull { maxOf(it.startMs, it.endMs) } ?: 0L
            diagnostics += "get-transcript-segments=${cleaned.size}"
            TranscriptResult(text, minutesFromMs(lastMs))
        } catch (e: Exception) {
            diagnostics += "get-transcript-error=${diagnosticMessage(e)}"
            TranscriptResult()
        }
    }

    private fun webContext(bootstrap: YouTubeBootstrap): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", bootstrap.webClientVersion)
            .put("hl", "en")
            .put("gl", "US")
            .put("userAgent", DESKTOP_UA)

        if (bootstrap.visitorData.isNotBlank()) {
            client.put("visitorData", bootstrap.visitorData)
        }
        return JSONObject().put("client", client)
    }

    private fun androidContext(visitorData: String): JSONObject {
        val client = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", ANDROID_CLIENT_VERSION)
            .put("androidSdkVersion", 34)
            .put("hl", "en")
            .put("gl", "US")
            .put("userAgent", ANDROID_YOUTUBE_UA)

        if (visitorData.isNotBlank()) client.put("visitorData", visitorData)
        return JSONObject().put("client", client)
    }

    private fun fetchAndroidPlayer(
        videoId: String,
        bootstrap: YouTubeBootstrap?,
        diagnostics: MutableList<String>,
    ): PlayerResult? {
        return try {
            val apiKey = bootstrap?.apiKey ?: PUBLIC_INNERTUBE_KEY
            val visitorData = bootstrap?.visitorData.orEmpty()
            val payload = JSONObject()
                .put("context", androidContext(visitorData))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)

            val response = youtubePostJson(
                url = youtubeiUrl("player", apiKey),
                json = payload,
                userAgent = ANDROID_YOUTUBE_UA,
                clientNameHeader = ANDROID_CLIENT_NAME_HEADER,
                clientVersion = ANDROID_CLIENT_VERSION,
                visitorData = visitorData,
                referer = null,
            )
            diagnostics += "android-player=${response.code}/${response.body.length}"
            if (response.code !in 200..299 || response.body.isBlank()) return null

            val root = JSONObject(response.body)
            val status = root.optJSONObject("playabilityStatus")
            val statusName = status?.optString("status").orEmpty()
            val reason = status?.optString("reason").orEmpty().take(100)
            diagnostics += "playability=${statusName.ifBlank { "unknown" }}" +
                if (reason.isBlank()) "" else "/$reason"

            val title = root.optJSONObject("videoDetails")
                ?.optString("title")
                .orEmpty()
            val tracksArray = root.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
            val tracks = parseCaptionTracks(tracksArray)
            diagnostics += "android-tracks=${tracks.size}"
            PlayerResult(title, tracks)
        } catch (e: Exception) {
            diagnostics += "android-player-error=${diagnosticMessage(e)}"
            null
        }
    }

    private fun fetchWebPlayer(
        videoId: String,
        bootstrap: YouTubeBootstrap,
        diagnostics: MutableList<String>,
    ): PlayerResult? {
        return try {
            val payload = JSONObject()
                .put("context", webContext(bootstrap))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)

            val response = youtubePostJson(
                url = youtubeiUrl("player", bootstrap.apiKey),
                json = payload,
                userAgent = DESKTOP_UA,
                clientNameHeader = WEB_CLIENT_NAME_HEADER,
                clientVersion = bootstrap.webClientVersion,
                visitorData = bootstrap.visitorData,
                referer = watchUrl(videoId),
                includeWebHeaders = true,
            )
            diagnostics += "web-player=${response.code}/${response.body.length}"
            if (response.code !in 200..299 || response.body.isBlank()) return null

            val root = JSONObject(response.body)
            val status = root.optJSONObject("playabilityStatus")
            val statusName = status?.optString("status").orEmpty()
            diagnostics += "web-playability=${statusName.ifBlank { "unknown" }}"

            val title = root.optJSONObject("videoDetails")
                ?.optString("title")
                .orEmpty()
            val tracksArray = root.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks")
            val tracks = parseCaptionTracks(tracksArray)
            diagnostics += "web-tracks=${tracks.size}"
            PlayerResult(title, tracks)
        } catch (e: Exception) {
            diagnostics += "web-player-error=${diagnosticMessage(e)}"
            null
        }
    }

    private fun extractCaptionTracksFromWatchHtml(
        html: String,
        diagnostics: MutableList<String>,
    ): List<CaptionTrack> {
        return try {
            val marker = "\"captionTracks\""
            val markerIndex = html.indexOf(marker)
            if (markerIndex < 0) {
                diagnostics += "watch-tracks=missing"
                return emptyList()
            }
            val arrayStart = html.indexOf('[', markerIndex + marker.length)
            if (arrayStart < 0) return emptyList()
            val json = extractBalancedJson(html, arrayStart, '[', ']') ?: return emptyList()
            val tracks = parseCaptionTracks(JSONArray(json))
            diagnostics += "watch-tracks=${tracks.size}"
            tracks
        } catch (e: Exception) {
            diagnostics += "watch-tracks-error=${diagnosticMessage(e)}"
            emptyList()
        }
    }

    private fun parseCaptionTracks(array: JSONArray?): List<CaptionTrack> {
        if (array == null) return emptyList()
        val result = mutableListOf<CaptionTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("baseUrl").trim()
            val languageCode = item.optString("languageCode").trim()
            if (url.isBlank() || languageCode.isBlank()) continue
            val name = extractText(item.optJSONObject("name"))
                .ifBlank { languageCode }
            val generated = item.optString("kind") == "asr" ||
                item.optString("vssId").startsWith("a.")
            result += CaptionTrack(
                url = absolutize(url),
                languageCode = languageCode,
                name = name,
                generated = generated,
            )
        }
        return result.distinctBy { "${it.url}|${it.languageCode}" }
    }

    private fun rankCaptionTracks(tracks: List<CaptionTrack>): List<CaptionTrack> {
        fun rank(track: CaptionTrack): Int {
            val english = track.languageCode.equals("en", true) ||
                track.languageCode.startsWith("en-", true)
            return when {
                english && !track.generated -> 0
                english && track.generated -> 1
                !track.generated -> 2
                else -> 3
            }
        }
        return tracks.sortedWith(compareBy<CaptionTrack>({ rank(it) }, { it.languageCode }, { it.name }))
    }

    private fun fetchTimedText(
        rawBase: String,
        referer: String,
        diagnostics: MutableList<String>,
        label: String,
    ): TranscriptResult {
        if (rawBase.contains("exp=xpe") && !rawBase.contains("pot=")) {
            diagnostics += "$label=po-token-required"
            return TranscriptResult()
        }

        // Keep every signed/query parameter. Only fmt is replaced through
        // HttpUrl.Builder; substringBefore("&fmt=") used to corrupt the URL.
        val variants = linkedMapOf<String, String>()
        variants[rawBase] = "original"
        variants[withCaptionFormat(rawBase, null)] = "default"
        variants[withCaptionFormat(rawBase, "json3")] = "json3"
        variants[withCaptionFormat(rawBase, "srv3")] = "srv3"
        variants[withCaptionFormat(rawBase, "ttml")] = "ttml"

        for ((url, formatLabel) in variants) {
            val response = youtubeGet(url, DESKTOP_UA, referer)
            val contentType = response.contentType.substringBefore(';').take(40)
            diagnostics += "$label/$formatLabel=${response.code}/${response.body.length}" +
                if (contentType.isBlank()) "" else "/$contentType"
            if (response.code !in 200..299 || response.body.isBlank()) continue

            val parsed = parseTranscriptPayload(response.body)
            if (parsed.text.isNotBlank()) return parsed
        }
        return TranscriptResult()
    }

    private fun parseTranscriptPayload(body: String): TranscriptResult {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{")) {
            val json = parseJson3(body)
            if (json.text.isNotBlank()) return json
        }
        if (trimmed.startsWith("WEBVTT")) {
            val vtt = parseWebVtt(body)
            if (vtt.text.isNotBlank()) return vtt
        }
        return parseXmlCaptions(body)
    }

    private fun parseJson3(body: String): TranscriptResult {
        return try {
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return TranscriptResult()
            val pieces = mutableListOf<String>()
            var lastMs = 0L
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val startMs = event.optLong("tStartMs", 0L)
                val durationMs = event.optLong("dDurationMs", 0L)
                lastMs = maxOf(lastMs, startMs + durationMs)
                val segs = event.optJSONArray("segs") ?: continue
                val eventText = buildString {
                    for (j in 0 until segs.length()) {
                        append(segs.optJSONObject(j)?.optString("utf8").orEmpty())
                    }
                }.normalizeWhitespace()
                if (eventText.isNotBlank()) pieces += eventText
            }
            TranscriptResult(
                text = pieces.joinToString(" ").normalizeWhitespace(),
                minutes = minutesFromMs(lastMs),
            )
        } catch (_: Exception) {
            TranscriptResult()
        }
    }

    /** Parses legacy XML, srv3, and TTML without regexing XML. */
    private fun parseXmlCaptions(body: String): TranscriptResult {
        return try {
            val document = Jsoup.parse(body, "", Parser.xmlParser())
            val elements = when {
                document.select("text").isNotEmpty() -> document.select("text")
                document.select("p").isNotEmpty() -> document.select("p")
                else -> return TranscriptResult()
            }

            val pieces = mutableListOf<String>()
            var lastSeconds = 0.0
            for (element in elements) {
                val text = element.text().normalizeWhitespace()
                if (text.isBlank()) continue
                pieces += text

                val start = elementStartSeconds(element)
                val end = elementEndSeconds(element, start)
                lastSeconds = maxOf(lastSeconds, end, start)
            }

            TranscriptResult(
                text = pieces.joinToString(" ").normalizeWhitespace(),
                minutes = if (lastSeconds <= 0.0) 0 else ceil(lastSeconds / 60.0).toInt(),
            )
        } catch (_: Exception) {
            TranscriptResult()
        }
    }

    private fun parseWebVtt(body: String): TranscriptResult {
        val pieces = mutableListOf<String>()
        var lastSeconds = 0.0
        var inNote = false
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("NOTE")) {
                inNote = true
                continue
            }
            if (inNote && line.isBlank()) {
                inNote = false
                continue
            }
            if (inNote || line.isBlank() || line == "WEBVTT" || line.all(Char::isDigit)) continue
            if ("-->" in line) {
                val end = line.substringAfter("-->").trim().substringBefore(' ')
                lastSeconds = maxOf(lastSeconds, parseTimeValue(end))
                continue
            }
            if (line.startsWith("STYLE") || line.startsWith("REGION")) continue
            val clean = Jsoup.parse(line).text().normalizeWhitespace()
            if (clean.isNotBlank()) pieces += clean
        }
        return TranscriptResult(
            text = pieces.joinToString(" ").normalizeWhitespace(),
            minutes = if (lastSeconds <= 0.0) 0 else ceil(lastSeconds / 60.0).toInt(),
        )
    }

    private fun elementStartSeconds(element: Element): Double {
        return when {
            element.hasAttr("start") -> parseTimeValue(element.attr("start"))
            element.hasAttr("begin") -> parseTimeValue(element.attr("begin"))
            element.hasAttr("t") -> element.attr("t").toDoubleOrNull()?.div(1000.0) ?: 0.0
            else -> 0.0
        }
    }

    private fun elementEndSeconds(element: Element, start: Double): Double {
        return when {
            element.hasAttr("end") -> parseTimeValue(element.attr("end"))
            element.hasAttr("dur") -> start + parseTimeValue(element.attr("dur"))
            element.hasAttr("d") -> start +
                (element.attr("d").toDoubleOrNull()?.div(1000.0) ?: 0.0)
            else -> start
        }
    }

    private fun parseTimeValue(raw: String): Double {
        val value = raw.trim()
        if (value.isBlank()) return 0.0
        if (value.endsWith("ms", true)) {
            return value.dropLast(2).toDoubleOrNull()?.div(1000.0) ?: 0.0
        }
        if (value.endsWith("s", true)) {
            return value.dropLast(1).toDoubleOrNull() ?: 0.0
        }
        if (':' !in value) return value.toDoubleOrNull() ?: 0.0

        val parts = value.split(':')
        var multiplier = 1.0
        var seconds = 0.0
        for (part in parts.asReversed()) {
            seconds += (part.toDoubleOrNull() ?: 0.0) * multiplier
            multiplier *= 60.0
        }
        return seconds
    }

    private fun collectTranscriptSegments(node: Any?, output: MutableList<TranscriptSegment>) {
        when (node) {
            is JSONObject -> {
                val renderer = node.optJSONObject("transcriptSegmentRenderer")
                    ?: node.optJSONObject("transcriptCueRenderer")
                if (renderer != null) {
                    val text = extractTranscriptRendererText(renderer)
                    if (text.isNotBlank()) {
                        val start = renderer.optString("startMs").toLongOrNull()
                            ?: renderer.optLong("startMs", 0L)
                        val end = renderer.optString("endMs").toLongOrNull()
                            ?: renderer.optLong("endMs", start)
                        output += TranscriptSegment(text, start, maxOf(start, end))
                    }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectTranscriptSegments(node.opt(keys.next()), output)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectTranscriptSegments(node.opt(i), output)
            }
        }
    }

    private fun extractTranscriptRendererText(renderer: JSONObject): String {
        val candidates = listOf("snippet", "cue", "text")
        for (key in candidates) {
            val objectValue = renderer.optJSONObject(key)
            if (objectValue != null) {
                val text = extractText(objectValue)
                if (text.isNotBlank()) return text.normalizeWhitespace()
            }
            val stringValue = renderer.optString(key)
            if (stringValue.isNotBlank()) return stringValue.normalizeWhitespace()
        }
        return renderer.optString("content").normalizeWhitespace()
    }

    private fun deduplicateSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        val result = mutableListOf<TranscriptSegment>()
        for (segment in segments) {
            val normalized = segment.copy(text = segment.text.normalizeWhitespace())
            if (normalized.text.isBlank()) continue
            val previous = result.lastOrNull()
            if (previous != null && previous.text == normalized.text &&
                previous.startMs == normalized.startMs
            ) {
                continue
            }
            result += normalized
        }
        return result
    }

    private fun findTranscriptParams(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val endpoint = node.optJSONObject("getTranscriptEndpoint")
                val params = endpoint?.optString("params").orEmpty()
                if (params.isNotBlank()) return params
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findTranscriptParams(node.opt(keys.next()))
                    if (!found.isNullOrBlank()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findTranscriptParams(node.opt(i))
                    if (!found.isNullOrBlank()) return found
                }
            }
        }
        return null
    }

    private fun extractInnertubeContextClient(html: String): JSONObject? {
        val marker = "\"INNERTUBE_CONTEXT\""
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val objectStart = html.indexOf('{', markerIndex + marker.length)
        if (objectStart < 0) return null
        val json = extractBalancedJson(html, objectStart, '{', '}') ?: return null
        return try {
            JSONObject(json).optJSONObject("client")
        } catch (_: Exception) {
            null
        }
    }

    private fun extractGetTranscriptParams(html: String): String? {
        val regex = Regex(
            "\\\"getTranscriptEndpoint\\\"\\s*:\\s*\\{[^{}]*" +
                "\\\"params\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val raw = regex.find(html)?.groupValues?.getOrNull(1) ?: return null
        return decodeJsonString(raw)
    }

    private fun extractJsonConfigString(html: String, key: String): String? {
        val regex = Regex(
            "\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"",
        )
        val raw = regex.find(html)?.groupValues?.getOrNull(1) ?: return null
        return decodeJsonString(raw)
    }

    private fun decodeJsonString(raw: String): String {
        return try {
            JSONObject("{\"value\":\"$raw\"}").getString("value")
        } catch (_: Exception) {
            raw.replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }

    private fun extractTitleFromWatchHtml(html: String): String {
        val marker = "\"videoDetails\""
        val markerIndex = html.indexOf(marker)
        if (markerIndex >= 0) {
            val objectStart = html.indexOf('{', markerIndex + marker.length)
            val json = if (objectStart >= 0) {
                extractBalancedJson(html, objectStart, '{', '}')
            } else null
            if (!json.isNullOrBlank()) {
                try {
                    val title = JSONObject(json).optString("title").trim()
                    if (title.isNotBlank()) return title
                } catch (_: Exception) {
                    // Fall through to a direct title search.
                }
            }
        }
        val regex = Regex("\\\"title\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
        return regex.find(html)?.groupValues?.getOrNull(1)?.let(::decodeJsonString).orEmpty()
    }

    private fun extractBalancedJson(
        source: String,
        start: Int,
        open: Char,
        close: Char,
    ): String? {
        if (start !in source.indices || source[start] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val char = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun extractText(value: JSONObject?): String {
        if (value == null) return ""
        val simple = value.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = value.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
    }

    private fun isConsentPage(html: String): Boolean =
        html.contains("consent.youtube.com", ignoreCase = true) &&
            html.contains("name=\"v\"", ignoreCase = true)

    private fun extractConsentValue(html: String): String? {
        return try {
            Jsoup.parse(html)
                .selectFirst("form[action*=consent.youtube.com] input[name=v]")
                ?.attr("value")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            Regex("name=[\\\"']v[\\\"'][^>]*value=[\\\"']([^\\\"']+)")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
        }
    }

    private fun withCaptionFormat(rawUrl: String, format: String?): String {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return rawUrl
        val builder = parsed.newBuilder().removeAllQueryParameters("fmt")
        if (!format.isNullOrBlank()) builder.addQueryParameter("fmt", format)
        return builder.build().toString()
    }

    private fun absolutize(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.youtube.com$url"
        else -> "https://www.youtube.com/$url"
    }

    /** Diagnostic detail from the last failed transcript request. */
    var lastCaptionDiag: String = ""
        private set

    // ── Web pages ─────────────────────────────────────────────────────────────

    private fun fetchWeb(url: String): FetchedSource {
        val response = generalGet(url)
        if (response.code !in 200..299 || response.body.isBlank()) {
            return FetchedSource(
                text = "",
                title = url,
                kind = "Web page",
                error = "Could not retrieve that page (HTTP ${response.code}).",
            )
        }
        val doc = Jsoup.parse(response.body, response.finalUrl.ifBlank { url })
        val title = doc.title().ifBlank { url }
        doc.select("script,style,nav,header,footer,aside,form,noscript,iframe,svg").remove()
        val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val blocks = main.select("h1,h2,h3,p,li,blockquote")
            .map { it.text().trim() }
            .filter { it.length > 1 }
        var text = blocks.joinToString("\n").trim()
        if (text.length < 80) text = main.text().trim()
        return if (text.isBlank()) {
            FetchedSource("", title, "Web page", error = "No readable content found.")
        } else {
            FetchedSource(text, title, "Web page")
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private data class HttpResult(
        val code: Int,
        val body: String,
        val contentType: String = "",
        val finalUrl: String = "",
        val error: String = "",
    )

    private fun youtubeGet(
        url: String,
        userAgent: String,
        referer: String? = null,
    ): HttpResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "*/*")
            .header("DNT", "1")
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return execute(builder.get().build())
    }

    private fun generalGet(url: String): HttpResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        return execute(request)
    }

    private fun youtubePostJson(
        url: String,
        json: JSONObject,
        userAgent: String,
        clientNameHeader: String,
        clientVersion: String,
        visitorData: String,
        referer: String?,
        includeWebHeaders: Boolean = false,
    ): HttpResult {
        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-YouTube-Client-Name", clientNameHeader)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Content-Type", "application/json")
        if (includeWebHeaders) {
            builder.header("Origin", "https://www.youtube.com")
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            builder.header("X-Goog-AuthUser", "0")
            builder.header("X-YouTube-Bootstrap-Logged-In", "false")
        }
        if (visitorData.isNotBlank()) builder.header("X-Goog-Visitor-Id", visitorData)
        return execute(builder.post(body).build())
    }

    private fun execute(request: Request): HttpResult {
        return try {
            http.newCall(request).execute().use { response ->
                HttpResult(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    contentType = response.header("Content-Type").orEmpty(),
                    finalUrl = response.request.url.toString(),
                )
            }
        } catch (e: Exception) {
            HttpResult(
                code = -1,
                body = "",
                finalUrl = request.url.toString(),
                error = diagnosticMessage(e),
            )
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) put(cookie)
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }

        @Synchronized
        fun put(cookie: Cookie) {
            cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            cookies += cookie
        }
    }

    private fun String.normalizeWhitespace(): String =
        replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun minutesFromMs(milliseconds: Long): Int =
        if (milliseconds <= 0L) 0 else ceil(milliseconds / 60_000.0).toInt()

    private fun diagnosticMessage(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName)
            .replace(';', ',')
            .replace('\n', ' ')
            .take(180)

    private fun watchUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId&hl=en&gl=US&persist_hl=1&persist_gl=1"

    private fun youtubeiUrl(endpoint: String, apiKey: String): String =
        "https://www.youtube.com/youtubei/v1/$endpoint?key=$apiKey&prettyPrint=false"

    private companion object {
        const val EXTRACTOR_BUILD = "2"
        const val MAX_DIAGNOSTIC_LENGTH = 2_000

        // youtube-transcript-api's current Android client identity.
        const val ANDROID_CLIENT_VERSION = "20.10.38"
        const val ANDROID_CLIENT_NAME_HEADER = "3"
        const val WEB_CLIENT_NAME_HEADER = "1"

        const val FALLBACK_WEB_CLIENT_VERSION = "2.20240726.00.00"
        const val PUBLIC_INNERTUBE_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        const val ANDROID_YOUTUBE_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
