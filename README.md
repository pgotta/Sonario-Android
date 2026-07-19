# Sonario for Android

Summarize YouTube videos, web articles, and pasted text on your phone. Paste a
link (or share one into the app) and get skimmable notes, a detailed prose view,
or a bulleted outline.

**Status:** working. YouTube transcript summarization, web-article and pasted-text
summarization, Groq cloud, on-device inference, saved sessions, resumable
checkpoints, and source Q&A are functional as of version 1.5.0.

Sonario has two engines, and you pick which to use per summary:

- **Groq cloud** (recommended) - sends your text to Groq's API and summarizes
  with Qwen 3.6 27B. Sonario splits large sources into rate-safe requests, waits
  for Groq's minute windows, and checkpoints each completed call. You bring your
  own API key. Your text goes to Groq's servers.
- **On-device** - runs a downloaded GGUF locally through llama.cpp. Source text
  remains private after Sonario fetches it, but phone inference is slower than
  Groq and can warm the device during long summaries.

## Highlights

- Summarizes YouTube captions, web articles, pasted text, PDF, EPUB, DOCX, TXT,
  and Markdown files.
- Uses `qwen/qwen3.6-27b` as the only Groq cloud model, preventing retired model
  IDs from being restored by an old setting or saved session.
- Queues cloud requests against conservative TPM/RPM limits and Groq's live reset
  headers instead of repeatedly failing with minute-based 429 errors.
- Offers three newer local choices with clear quality/speed tradeoffs: Qwen3 4B,
  Gemma 3n E4B, and LFM2 2.6B.
- Keeps long cloud summaries alive with a foreground service and retries
  temporary DNS, timeout, and network-handoff failures.
- Saves up to 12 recent sessions locally, restores the latest session on launch,
  and resumes from checkpoints without repeating completed model calls.
- Includes Normal, Detailed, Bullets, and chapter views, plus source-grounded
  **Ask about this** Q&A.
- Lets users delete one session or clear all locally stored transcripts,
  summaries, checkpoints, and Q&A history.

## Screenshots

<p align="center">
  <img src="docs/screenshots/main.png" width="30%" alt="Main screen" />
  &nbsp;
  <img src="docs/screenshots/summary.png" width="30%" alt="Summary of a YouTube video" />
  &nbsp;
  <img src="docs/screenshots/setup.png" width="30%" alt="First-run engine and model setup" />
</p>

<p align="center">
  <em>Left: paste a link and pick an engine. Center: a summarized YouTube video
  with Normal / Detailed / Bullets views. Right: first-run setup.</em>
</p>

## Quick start (Groq cloud, the fast path)

1. Install the APK (see below) and open Sonario.
2. On the first screen, tap **Use Groq cloud instead** to skip the local-model
   download.
3. Get a Groq API key at console.groq.com and create a key.
4. In Sonario's Settings, paste the key and tap **Save key**.
5. Back on the main screen, make sure the toggle is on **Groq cloud**, paste a
   YouTube link or article URL, and tap **Summarize**.

Qwen 3.6 27B's published Groq free-tier baseline is 8K tokens per minute and
200K tokens per day, applied across the whole Groq organization. Sonario uses
slightly lower internal working limits for safety, follows the provider's live
remaining-token/reset headers, and displays a countdown between calls. Extra
keys in the same organization share the same quota and do not multiply it.

## Quick start (on-device, fully private)

1. Open Sonario and choose a local model:
   - **Qwen3 4B Instruct 2507** for the strongest overall local summaries and Q&A.
   - **Gemma 3n E4B Instruct** for nuanced long-form summaries and writing.
   - **LFM2 2.6B** for the smallest download and fastest local responses.
2. Tap **Get** and keep the phone on Wi-Fi. Downloads range from about 1.6 GB to
   4.3 GB.
3. When it finishes, select **On-device**, paste a source, and tap **Summarize**.

## Supported links

YouTube links in any common shape all work:

- `youtu.be/VIDEO_ID` (short link)
- `youtube.com/watch?v=VIDEO_ID`
- `m.youtube.com/watch?v=VIDEO_ID` (mobile)
- `youtube.com/shorts/VIDEO_ID`, `/live/VIDEO_ID`, `/embed/VIDEO_ID`
- Extra share parameters like `?si=...` are ignored.

You can also share a link from the YouTube or browser app straight into Sonario
via the Android share sheet. Web article URLs and raw pasted text also work.

## Privacy, stated plainly

The two engines differ, and the app shows which is active:

- On-device: summarization happens locally. The only network calls are the
  one-time model download and fetching whatever link you paste.
- Groq cloud: the text to summarize is sent to Groq. Don't use this for anything
  you wouldn't send to a third-party API. Your Groq key is stored only on your
  device and is sent solely to Groq.

## How YouTube transcripts are fetched

Sonario uses a layered extractor rather than relying on one caption URL:

1. Load the watch page in one cookie-preserving session and handle YouTube's
   consent page when it appears.
2. Read the current InnerTube API key, WEB client version, visitor data, and the
   real `getTranscriptEndpoint.params` value from YouTube's page data.
3. Prefer `youtubei/v1/next` -> `youtubei/v1/get_transcript`, which returns the
   transcript segments directly as JSON.
4. Fall back to consistently identified ANDROID and WEB `player` requests, then
   download an available caption track.
5. Parse JSON3, legacy XML, SRV3, TTML, or WebVTT captions.

The extractor preserves signed caption URL parameters when changing formats and
reports Proof-of-Origin-token tracks accurately instead of claiming the video has
no captions. These are still undocumented YouTube endpoints, so a future YouTube
change can require another extractor update. Failed requests show **Extractor
build 2** diagnostics so you can confirm the new APK is actually installed.

## Qwen cloud and rate-aware queueing (1.4.0)

- The Groq cloud path is pinned to `qwen/qwen3.6-27b`; old model preferences and
  saved Scout session IDs can no longer control the request model.
- Source chunks, detailed-output budgets, chapter excerpts, and Ask excerpts are
  sized so one call fits beneath the free-tier 8K TPM ceiling.
- Sonario leaves headroom below the published TPM/RPM/daily limits, reads Groq's
  `x-ratelimit-remaining-tokens` and reset headers, and waits with a visible
  countdown before the next call.
- Routine summaries use Qwen's non-thinking mode to avoid spending output tokens
  on hidden reasoning that is unnecessary for summarization.
- When Groq reports daily exhaustion, Sonario stops rather than waiting all day.
  Every completed call remains checkpointed so Resume continues later.

## Background reliability and Ask fixes (1.2.0)

- Cloud requests retry transient DNS, Wi-Fi/mobile-data handoff, connection, and
  timeout failures for up to ten minutes instead of immediately ending with
  `Unable to resolve host api.groq.com`.
- A foreground service holds a partial CPU wake lock and a temporary Wi-Fi lock
  only while a summary or source question is active. The notification displays
  rate-limit waits and network-retry status.
- Groq responses are buffered before being committed to a summary, so a failed
  connection can be retried without duplicating a partial response.
- The Ask box shows the actual API/network error in place, keeps the typed
  question after a failure, and searches relevant passages across the whole
  source instead of sending only the first portion of a long video or book.
- Long jobs have a visible Cancel control, stale errors clear when a new source is
  entered, and the app no longer treats a failed Ask call as a successful generic
  answer.

## On-device models (downloaded, never bundled)

| Model | Download | Best for | Tradeoff |
| --- | ---: | --- | --- |
| Qwen3 4B Instruct 2507 Q4_K_M | ~2.5 GB | Best overall local summaries, comprehension, and follow-up answers | Slower than LFM2 |
| Gemma 3n E4B Instruct Q4_K_M | ~4.24 GB | Nuanced long-form summaries and polished writing | Largest and slowest option |
| LFM2 2.6B Q4_K_M | ~1.56 GB | Fast summaries, extraction, and lower storage use | Weaker on difficult or subtle material |

Sonario downloads the GGUF files from public Hugging Face repositories into
`filesDir/models`, Android's private app-specific storage. The app checks free
space before starting, supports resumable downloads, and removes obsolete models
from versions 1.4 and earlier.

Android deletes app-specific files when Sonario is uninstalled. Sonario also sets
`hasFragileUserData=false` and excludes `models/` from cloud backup and
phone-to-phone transfer, so multi-gigabyte model files are not retained or
restored after reinstall.

The app uses Llamatik 1.8.1, an Android wrapper around llama.cpp. It requests
available acceleration but may fall back to CPU depending on the device and the
runtime build. Local models remain useful for privacy and offline work, while the
27B Groq model remains substantially stronger and faster for everyday use.

## Crash diagnostics

If the app ever crashes, it writes the stack trace to its private app files and
shows it on the next launch with a Copy button instead of silently closing.

## Architecture

Kotlin/Jetpack Compose. Both engines implement one interface, `InferenceEngine`,
and the summarize pipeline talks only to that:

- `llm/InferenceEngine.kt` - shared interface (`ensureReady`, `stream`).
- `llm/LlmEngine.kt` - on-device via Llamatik/llama.cpp.
- `llm/GroqEngine.kt` - Qwen 3.6 through Groq's OpenAI-compatible streaming API.
- `llm/RateLimiter.kt` - local pacing plus synchronization with Groq reset headers.
- `llm/ModelDownloader.kt` - resumable GGUF downloads with free-space checks.
- `data/Settings.kt` - engine choice and Groq key (local preferences).
- `source/SourceFetcher.kt` - YouTube (InnerTube) and web-article fetching.
- `summarize/SummarizeEngine.kt` - map-reduce summarizer with bounded cloud calls
  and small, phone-safe local chunks.
- `summarize/Prompts.kt` - prompts carried over from Sonario desktop.
- `ui/` - Compose screens, theme, settings, system meter, and crash screen.
- `CrashReporter.kt` - global uncaught-exception logger.

To add another backend, implement `InferenceEngine` and hand it to
`SummarizeEngine`. `GroqEngine` is the template for an HTTP-based engine.

## Build

See BUILD.md.

## Credits

Port of Sonario desktop by pgotta. On-device inference via llama.cpp/Llamatik;
cloud inference via Groq.

## License

MIT. See LICENSE.

## Clear all saved sessions (1.3.1)

The Recent sessions card has a **Clear** button. After confirmation, it
permanently deletes all locally saved session folders, including source
transcripts, chapter data, summaries, checkpoints, and saved Q&A. Exported files
outside the app are left alone.

## Saved and resumable sessions (1.3.0)

Sonario saves summaries locally instead of keeping the only copy in an
Activity/ViewModel. The most recent session is restored after an app or Activity
restart, and a Recent sessions panel can open, resume, or delete prior work.

For long summaries, Sonario checkpoints after every completed LLM call. If
Android or the network interrupts the run, Resume skips already-completed
condensed chunks and derived views so those Groq tokens are not spent twice.
Completed source text and Ask history are stored with the session. Up to 12
recent sessions are retained in the app's private files directory.

## Keyboard-safe Ask field (1.3.3)

The Ask field follows the software keyboard into view, supports two to four
lines of editing, offers the keyboard Send action, and preserves unfinished text
across ordinary Activity recreation.
