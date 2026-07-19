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
import java.io.IOException
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
    private var lastCaptionDiag: String = ""

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
        // SECURITY: no hardcoded key. Use the key from the page, or empty.
        val apiKey = extractJsonConfigString(response.body, "INNERTUBE_API_KEY") ?: ""
        val clientVersion = extractJsonConfigString(response.body, "INNERTUBE_CLIENT_VERSION")
            ?: extractJsonConfigString(response.body, "INNERTUBE_CONTEXT_CLIENT_VERSION")
            ?: contextClient?.optString("clientVersion")?.takeIf { it.isNotBlank() }
            ?: FALLBACK_WEB_CLIENT_VERSION
        val visitorData = extractJsonConfigString(response.body, "VISITOR_DATA")
            ?: contextClient?.optString("visitorData") ?: ""
        val title = extractTitleFromWatchHtml(response.body)

        diagnostics += "bootstrap=key:${if (apiKey.isBlank()) "none" else "page"}" +
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
        if (bootstrap.apiKey.isBlank()) {
            diagnostics += "transcript-params=no-key"
            return TranscriptResult()
        }
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
            // SECURITY: no hardcoded key. Use empty string if none from page.
            val apiKey = bootstrap?.apiKey ?: ""
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
                !english && !track.generated -> 2
                else -> 3
            }
        }
        return tracks.sortedWith(compareBy(::rank))
    }

    private fun fetchTimedText(
        rawBase: String,
        referer: String,
        diagnostics: MutableList<String>,
        label: String,
    ): TranscriptResult {
        return try {
            val base = withCaptionFormat(rawBase)
            val text = parseTranscriptPayload(base, referer, diagnostics, label)
            TranscriptResult(text, minutesFromMs(estimateDurationMs(text)))
        } catch (e: Exception) {
            diagnostics += "timedtext-error=$label:${diagnosticMessage(e)}"
            TranscriptResult()
        }
    }

    private fun parseTranscriptPayload(
        base: String,
        referer: String,
        diagnostics: MutableList<String>,
        label: String,
    ): String {
        val result = generalGet(base, referer)
        diagnostics += "timedtext=$label:${result.code}/${result.body.length}"
        if (result.code !in 200..299 || result.body.isBlank()) return ""
        return when {
            result.body.trimStart().startsWith("{") -> parseJson3(result.body, diagnostics, label)
            result.body.trimStart().startsWith("<") -> {
                if (result.body.contains("<?xml") || result.body.contains("<tt"))
                    parseXmlCaptions(result.body, diagnostics, label)
                else
                    parseWebVtt(result.body, diagnostics, label)
            }
            else -> parseWebVtt(result.body, diagnostics, label)
        }
    }

    private fun parseJson3(
        body: String,
        diagnostics: MutableList<String>,
        label: String,
    ): String {
        return try {
            val events = JSONObject(body).optJSONArray("events") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val segs = event.optJSONArray("segs") ?: continue
                for (j in 0 until segs.length()) {
                    val seg = segs.optJSONObject(j) ?: continue
                    val utf8 = seg.optString("utf8").trim()
                    if (utf8.isNotEmpty()) sb.append(utf8)
                }
            }
            val text = sb.toString().normalizeWhitespace()
            diagnostics += "timedtext-json3=$label:${text.length}chars"
            text
        } catch (e: Exception) {
            diagnostics += "timedtext-json3-error=$label:${diagnosticMessage(e)}"
            ""
        }
    }

    private fun parseXmlCaptions(
        body: String,
        diagnostics: MutableList<String>,
        label: String,
    ): String {
        return try {
            val doc = Jsoup.parse(body, "", Parser.xmlParser())
            val texts = doc.select("text")
            val sb = StringBuilder()
            for (el in texts) {
                val text = el.text().trim()
                if (text.isNotEmpty()) sb.append(' ').append(text)
            }
            val text = sb.toString().trim().normalizeWhitespace()
            diagnostics += "timedtext-xml=$label:${text.length}chars"
            text
        } catch (e: Exception) {
            diagnostics += "timedtext-xml-error=$label:${diagnosticMessage(e)}"
            ""
        }
    }

    private fun parseWebVtt(
        body: String,
        diagnostics: MutableList<String>,
        label: String,
    ): String {
        return try {
            val sb = StringBuilder()
            var lastText = ""
            for (line in body.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("WEBVTT") ||
                    trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE") ||
                    trimmed.startsWith("REGION") || trimmed.contains("-->"))
                    continue
                if (trimmed == lastText) continue
                lastText = trimmed
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(trimmed)
            }
            val text = sb.toString().normalizeWhitespace()
            diagnostics += "timedtext-vtt=$label:${text.length}chars"
            text
        } catch (e: Exception) {
            diagnostics += "timedtext-vtt-error=$label:${diagnosticMessage(e)}"
            ""
        }
    }

    private fun elementStartSeconds(el: Element): Double =
        parseTimeValue(el.attr("begin")) ?: parseTimeValue(el.attr("t")) ?: 0.0

    private fun elementEndSeconds(el: Element): Double {
        val end = parseTimeValue(el.attr("end"))
        if (end != null) return end
        val dur = parseTimeValue(el.attr("d")) ?: return 0.0
        return elementStartSeconds(el) + dur
    }

    private fun parseTimeValue(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        return try {
            val parts = value.split(":")
            when (parts.size) {
                3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun collectTranscriptSegments(
        root: JSONObject,
        segments: MutableList<TranscriptSegment>,
    ) {
        val actions = root.optJSONArray("actions") ?: return
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            val updatePanel = action.optJSONObject("updateEngagementPanelAction")
            val content = updatePanel?.optJSONObject("content") ?: continue
            val transcript = content.optJSONObject("transcriptRenderer")
            val body = transcript?.optJSONObject("transcriptBodyRenderer")
            val runs = body?.optJSONObject("cueGroupPresentationRenderer")
                ?.optJSONObject("transcriptCueGroupRenderer")
                ?.optJSONArray("cues") ?: continue
            for (j in 0 until runs.length()) {
                val cue = runs.optJSONObject(j) ?: continue
                val cueRenderer = cue.optJSONObject("transcriptCueRenderer") ?: continue
                val text = extractTranscriptRendererText(cueRenderer)
                val startMs = cueRenderer.optLong("startOffsetMs", 0)
                val endMs = cueRenderer.optLong("endOffsetMs", startMs + 2000)
                if (text.isNotBlank()) {
                    segments.add(TranscriptSegment(text, startMs, endMs))
                }
            }
        }
    }

    private fun extractTranscriptRendererText(cueRenderer: JSONObject): String {
        val cue = cueRenderer.optJSONObject("cue") ?: return ""
        val runs = cue.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            sb.append(run.optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun deduplicateSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        if (segments.size <= 1) return segments
        val result = mutableListOf(segments.first())
        for (i in 1 until segments.size) {
            val prev = result.last()
            val cur = segments[i]
            if (cur.text == prev.text && cur.startMs - prev.startMs < 500) {
                result[result.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, cur.endMs))
            } else {
                result.add(cur)
            }
        }
        return result
    }

    private fun findTranscriptParams(root: JSONObject): String? {
        val panels = root.optJSONArray("engagementPanels") ?: return null
        for (i in 0 until panels.length()) {
            val panel = panels.optJSONObject(i) ?: continue
            val identifier = panel.optJSONObject("engagementPanelSectionListRenderer")
                ?.optJSONObject("header")
                ?.optJSONObject("engagementPanelTitleHeaderRenderer")
                ?.optString("panelIdentifier") ?: continue
            if (identifier == "engagement-panel-searchable-transcript") {
                val content = panel.optJSONObject("engagementPanelSectionListRenderer")
                    ?.optJSONObject("content") ?: continue
                val renderer = content.optJSONObject("continuationItemRenderer") ?: continue
                val endpoint = renderer.optJSONObject("continuationEndpoint") ?: continue
                return endpoint.optJSONObject("continuationCommand")?.optString("token")
            }
        }
        return null
    }

    private fun extractInnertubeContextClient(html: String): JSONObject? {
        val key = "INNERTUBE_CONTEXT"
        val idx = html.indexOf(key)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        val json = extractBalancedJson(html, start, '{', '}') ?: return null
        return try { JSONObject(json) } catch (_: Exception) { null }
    }

    private fun extractGetTranscriptParams(html: String): String? {
        val key = "getTranscriptEndpoint"
        val idx = html.indexOf(key)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        val json = extractBalancedJson(html, start, '{', '}') ?: return null
        return try {
            JSONObject(json).optJSONObject("params")?.optString("params")
        } catch (_: Exception) { null }
    }

    private fun extractJsonConfigString(html: String, key: String): String? {
        val pattern = "$key:\\s*\"([^\"]*)\""
        return Regex(pattern).find(html)?.groupValues?.getOrNull(1)
    }

    private fun decodeJsonString(s: String): String = s
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003e", ">")
        .replace("\\u003c", "<")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\t", "\t")

    private fun extractTitleFromWatchHtml(html: String): String {
        // Try og:title first, then <title>.
        val og = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"").find(html)
        if (og != null) return decodeJsonString(og.groupValues[1]).trim()
        val title = Regex("<title>([^<]*)</title>").find(html)
        if (title != null) {
            return decodeJsonString(title.groupValues[1]).trim()
                .replace(" - YouTube", "").trim()
        }
        return ""
    }

    private fun extractBalancedJson(html: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inString) { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == open) depth++
            if (c == close) {
                depth--
                if (depth == 0) return html.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractText(name: JSONObject?): String {
        if (name == null) return ""
        val simple = name.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = name.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun isConsentPage(html: String): Boolean {
        return html.contains("consent.youtube.com") || html.contains("CONSENT")
    }

    private fun extractConsentValue(html: String): String? {
        val patterns = listOf(
            "action=\"https://consent\\.youtube\\.com/s\\?([^\"]*)\"",
            "action=\"https://consent\\.youtube\\.com/s([^^\"]*)\"",
        )
        for (p in patterns) {
            val m = Regex(p).find(html) ?: continue
            val query = m.groupValues.getOrNull(1) ?: continue
            val params = query.removePrefix("?").split("&")
            for (param in params) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "continue") return parts[1]
            }
        }
        return null
    }

    private fun withCaptionFormat(baseUrl: String): String {
        return baseUrl.replace(Regex("&fmt=[^&]*"), "")
            .let { if (it.contains("&")) "$it&" else "$it?" }
            .let { "${it}fmt=json3" }
    }

    private fun absolutize(url: String): String {
        if (url.startsWith("http")) return url
        return if (url.startsWith("//")) "https:$url" else "https://www.youtube.com$url"
    }

    private fun fetchWeb(url: String): FetchedSource {
        return try {
            val result = generalGet(url, null)
            if (result.code !in 200..299 || result.body.isBlank()) {
                return FetchedSource(
                    text = "", title = "", kind = "Web article",
                    error = "Could not fetch that web page (HTTP ${result.code}).",
                )
            }
            val doc = Jsoup.parse(result.body, url)
            val title = doc.title().ifBlank { "Web article" }
            doc.select("script, style, nav, footer, header, aside").remove()
            val article = doc.select("article, [role=main], main, .post-content, .entry-content").firstOrNull()
            val text = article?.text()?.trim() ?: doc.body()?.text()?.trim() ?: ""
            if (text.isBlank()) {
                FetchedSource(
                    text = "", title = title, kind = "Web article",
                    error = "Could not extract readable text from that page.",
                )
            } else {
                FetchedSource(text = text, title = title, kind = "Web article")
            }
        } catch (e: Exception) {
            FetchedSource(
                text = "", title = "", kind = "Web article",
                error = "Could not fetch that web page: ${e.message}",
            )
        }
    }

    private data class HttpResult(val code: Int, val body: String) {
        val length: Int get() = body.length
    }

    private fun youtubeGet(url: String, userAgent: String): HttpResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return execute(request)
    }

    private fun generalGet(url: String, referer: String?): HttpResult {
        val builder = Request.Builder().url(url)
        if (referer != null) builder.header("Referer", referer)
        return execute(builder.build())
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
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", "")
            .header("X-YouTube-Client-Name", clientNameHeader)
            .header("X-YouTube-Client-Version", clientVersion)
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (visitorData.isNotBlank()) builder.header("X-Goog-Visitor-Id", visitorData)
        if (referer != null) builder.header("Referer", referer)
        if (includeWebHeaders) {
            builder.header("Origin", "https://www.youtube.com")
            builder.header("Accept", "*/*")
        }
        return execute(builder.build())
    }

    private fun execute(request: Request): HttpResult {
        return try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                HttpResult(resp.code, body)
            }
        } catch (e: IOException) {
            HttpResult(0, "")
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val key = "${url.host}${url.encodedPath}"
            store.getOrPut(key) { mutableListOf() }.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val key = "${url.host}${url.encodedPath}"
            return store[key] ?: emptyList()
        }
        fun put(cookie: Cookie) {
            val key = "${cookie.domain}${cookie.path}"
            val list = store.getOrPut(key) { mutableListOf() }
            list.removeAll { it.name == cookie.name }
            list.add(cookie)
        }
    }

    private fun String.normalizeWhitespace(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun minutesFromMs(ms: Long): Int = ceil(ms / 60000.0).toInt()

    private fun estimateDurationMs(text: String): Long {
        // Rough: ~15 words per second of speech.
        val words = text.split(Regex("\\s+")).size
        return (words / 15.0 * 1000).toLong()
    }

    private fun diagnosticMessage(e: Exception): String = e.message
        ?.replace(';', ',')
        .replace('\\n', ' ')
        ?.take(180) ?: e.javaClass.simpleName

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
        // SECURITY: no hardcoded Google/YouTube API key. The key must come
        // from the watch page; if absent, the get_transcript path is skipped.

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"

        const val ANDROID_YOUTUBE_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
