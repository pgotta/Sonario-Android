# Sonario for Android

A native Android port of the Sonario summarizer that runs a language model
**fully on the phone** via llama.cpp. Paste a YouTube link, an article URL, or
text, and it produces skimmable notes, a detailed prose view, or a bulleted
outline. Nothing you summarize leaves the device.

## Status: working proof-of-concept, not production-usable

Be aware before you build this: **it works, but it is too slow to use day to
day.** The model runs on the phone's CPU. The Llamatik/llama.cpp build used here
has no Adreno (Qualcomm) GPU backend, so on a Snapdragon phone every token is
generated on CPU. In practice that means:

- A short article or transcript takes minutes, not seconds.
- The phone gets warm and the CPU sits near 100% during a summary (the built-in
  meter shows this).
- Large documents are deliberately capped so a job can't run for hours.

This project is published as an honest proof-of-concept: the full pipeline works
end to end (model download, source fetching, on-device summarization, three
summary views), and the inference layer is isolated behind one small interface
(`LlmEngine`) so it can be swapped later. If and when a fast on-device backend
with GPU/NPU support becomes available for Android (or you point it at a remote
server), this becomes genuinely usable with a change to that one file.

If you want a usable version today, the realistic path is to change `LlmEngine`
to call a remote llama.cpp/Ollama server on a PC over your LAN or Tailscale,
keeping this UI. See "Swapping the engine" below.

## What works

- **YouTube** - fetches captions via YouTube's InnerTube player API (no audio
  download) and summarizes them. This scrapes an undocumented endpoint, so it can
  break if YouTube changes it.
- **Web pages** - extracts article text with Jsoup and summarizes it.
- **Pasted text** - summarizes any text.
- **Three views**, generated up front so toggling is instant: Normal (skimmable
  Markdown notes), Detailed (longer prose), Bullets (plain outline).
- **First-run model picker** - downloads one of three GGUF models in-app with a
  progress bar and resume. No adb, no manual file copying.
- **Share target** - share a link from YouTube or a browser into Sonario.
- **System meter** - a small bottom-left readout of CPU load and RAM. GPU% and
  VRAM are intentionally absent: Android exposes no API for them without root.

## Models (downloaded on first run, not bundled)

| Model | Size | Notes |
| --- | --- | --- |
| Qwen2.5 1.5B Instruct | ~1.1 GB | Fastest of the three; the least-slow default |
| Llama 3.2 3B Instruct | ~2.0 GB | Better output, noticeably slower |
| Phi-3.5 Mini Instruct | ~2.3 GB | Larger context; slowest |

All are Q4_K_M GGUF from public Hugging Face repos. The model is never bundled in
the APK (it would blow past install-size limits); it downloads at first launch to
`Android/data/ai.sonario.app/files/models/`.

## Architecture

The app is a Kotlin/Jetpack Compose reimplementation of Sonario desktop's
summarize pipeline. It shares no code with the Python/Flask desktop app; it
mirrors its logic (chunk -> condense -> combine map-reduce) and reuses its
prompts so output style matches.

Key pieces:

- `llm/LlmEngine.kt` - the entire on-device inference layer, behind a small
  interface. **This is the one file to change to swap backends.**
- `llm/ModelDownloader.kt` - resumable GGUF download.
- `source/SourceFetcher.kt` - YouTube (InnerTube) and web-article fetching.
- `summarize/SummarizeEngine.kt` - the map-reduce summarizer with an on-device
  work cap (bounded chunk count so a huge PDF can't run for hours).
- `summarize/Prompts.kt` - prompts carried over from Sonario desktop.
- `ui/` - Compose screens, theme, the radar `DepthMark`, and the `SysMeter`.

## Swapping the engine

Everything inference-related lives in `LlmEngine.kt` behind these methods:
`ensureLoaded`, `stream(system, user)`, `complete(...)`, `unload`. To retarget
the app at a remote server (a PC running llama.cpp's server or Ollama), replace
the Llamatik calls in those methods with HTTP calls to that server and keep the
rest of the app unchanged. The summarize pipeline, UI, and source fetching don't
need to know where the tokens come from.

## Build

See BUILD.md. Short version: open in Android Studio, let it sync, and use
**Build -> Build APK(s)**. Requires a recent Android Studio (Kotlin 2.2.x,
Android Gradle Plugin 8.7.x, compileSdk 36). No NDK needed; Llamatik ships
prebuilt native binaries.

## Privacy

The summarizer is fully local. Bytes leave the device only for the one-time model
download and for fetching a link you paste. Pasted text never touches the network.

## Credits

Port of Sonario desktop by pgotta. On-device inference via llama.cpp through the
Llamatik library.

## License

MIT. See LICENSE.
