# Sonario for Android

Summarize YouTube videos, web articles, and pasted text on your phone. Paste a
link (or share one into the app) and get skimmable notes, a detailed prose view,
or a bulleted outline — powered by the AI provider **you** choose.

## What's new in 1.4.0

Sonario is now a **bring-your-own-key (BYOK) multi-provider** app:

- **Any provider** — Groq, OpenAI, Anthropic (Claude), Ollama, or any
  OpenAI-compatible proxy — from one settings screen.
- **Hardware-backed key storage** — your API keys are encrypted with an
  AES-256-GCM key in the Android Keystore. They never leave the device except
  in the `Authorization` header of requests **you** initiate.
- **Per-provider model + base URL + temperature** — pick the model, set a
  custom endpoint (self-hosted, proxy, local Ollama), and tune randomness.
- **On-device still included** — run a GGUF model locally for full privacy.

See [CHANGELOG.md](CHANGELOG.md) for the full list and [docs/BYOK.md](docs/BYOK.md)
for setup instructions.

## Features

- **YouTube transcripts** — multi-route extraction (get_transcript, Android
  player, WEB player, watch-page captions) with consent handling.
- **Web articles** — fetched and cleaned with Jsoup.
- **Local files** — PDF, EPUB, DOCX, TXT, MD via the system file picker.
- **Three summary views** — Normal (skimmable Markdown), Detailed (prose),
  Bullets (outline), plus per-chapter for EPUBs.
- **Ask** — question-answering grounded in the source with `[n]` citations.
- **Resumable** — summaries checkpoint after each section; kill the app and
  resume without re-spending tokens.
- **Export** — TXT, Markdown, or PDF via the Storage Access Framework.
- **On-device** — runs a GGUF model via Llamatik (llama.cpp) with no NDK.

## Getting started

1. Clone the repo and open in Android Studio (Hedgehog or newer).
2. Run on a device or emulator (API 28+).
3. On first launch, choose:
   - **Cloud** — pick a provider, paste your API key, pick a model.
   - **On-device** — download a GGUF model (1–2 GB, use Wi-Fi).

## BYOK setup

Each provider needs an API key. Get one, then paste it in
**Settings → Providers**.

| Provider  | Free tier | Key location |
|-----------|-----------|--------------|
| Groq      | ✅        | console.groq.com |
| OpenAI    | —         | platform.openai.com/api-keys |
| Anthropic | —         | console.anthropic.com |
| Ollama    | ✅ (local) | no key needed |
| Custom    | —         | your proxy |

See [docs/BYOK.md](docs/BYOK.md) for step-by-step instructions.

## Build

```bash
./gradlew assembleDebug
```

See [BUILD.md](BUILD.md) for details.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose, Material 3)                         │
│  SummaryScreen · SetupScreen · SettingsScreen · Models   │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│  SummaryViewModel                                        │
│  (engine selection, session state, progress)             │
└──────┬───────────────────────────────┬───────────────────┘
       │                               │
┌──────▼────────┐            ┌─────────▼──────────────────┐
│  LlmEngine    │            │  CloudEngine               │
│  (on-device)  │            │  (Groq/OpenAI/Anthropic/   │
│  Llamatik /   │            │   Ollama / Custom)         │
│  llama.cpp    │            │  ↳ SecureStorage (keys)    │
└───────────────┘            └────────────────────────────┘
       │                               │
┌──────▼───────────────────────────────▼──────────────────┐
│  SummarizeEngine (map-reduce, chunk, condense, derive)   │
└─────────────────────────────────────────────────────────┘
```

- **`InferenceEngine`** — small interface; `LlmEngine` (on-device) and
  `CloudEngine` (any provider) both implement it.
- **`CloudEngine`** — one implementation, many providers. Reads
  `ProviderConfig` (model, base URL, temperature) and the API key from
  `SecureStorage`.
- **`SecureStorage`** — AES-256-GCM via Android Keystore. Keys never stored
  in plaintext.
- **`SessionStore`** — durable, resumable sessions on disk.
- **`SourceFetcher`** — YouTube (multi-route), web articles, pasted text.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports and feature requests go
through the templates in `.github/ISSUE_TEMPLATE/`.

## License

MIT — see [LICENSE](LICENSE).
