# Sonario for Android

An on-device / cloud **source summariser** powered by large language models.
Summarise web articles, YouTube videos, EPUBs, PDFs, or pasted text with a
single tap.

> **v2.0** — Now with multi-provider BYOK: OpenAI, Anthropic, Groq, Ollama,
> and any OpenAI-compatible proxy. Bring your own API key, stored encrypted
> via Android Keystore.

---

## Features

- **Two engines**
  - **On-device** — GGUF models via llama.cpp. No data ever leaves your phone.
  - **Cloud** — any major LLM provider, true streaming responses.
- **Five cloud providers**
  - OpenAI · Anthropic · Groq · Ollama · Custom (OpenAI-compatible proxy)
- **BYOK (bring-your-own-key)**
  - API keys are encrypted with AES-256-GCM via the Android Keystore.
  - Keys never leave the device except in the request you initiate.
- **Multiple source types**
  - Web articles, YouTube (transcript), EPUB, PDF, DOCX, pasted text.
- **Export** — save summaries as Markdown, plain text, or PDF.
- **Local sessions** — recent summaries are persisted on-device.
- **Configurable** — per-provider model, base URL, temperature, max tokens.

---

## Setup

```bash
git clone https://github.com/stupidgiraffe/Sonario-Android.git
cd Sonario-Android
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on your phone.

### First-run
1. Open the app → tap the gear icon.
2. Choose **On-device** or **Cloud**.
3. If you chose Cloud, pick a provider and paste your API key.
4. Pick a model and start summarising.

### Getting API keys

| Provider | Key format | Where to get one |
|----------|-----------|-----------------|
| OpenAI | `sk-…` | <https://platform.openai.com/api-keys> |
| Anthropic | `sk-ant-…` | <https://console.anthropic.com/settings/keys> |
| Groq | `gsk_…` | <https://console.groq.com/keys> |
| Ollama | *(none)* | <https://ollama.com/> |
| Custom | *(varies)* | Your proxy / self-hosted |

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  UI  (Jetpack Compose, Material 3)                        │
│  ├─ SummaryScreen ──────────────────────────┐            │
│  ├─ SettingsScreen (provider picker, BYOK)  │            │
│  └─ SetupScreen / ModelsScreen              │            │
└─────────────────────────────────────────────┼────────────┘
                                              │
┌─────────────────────────────────────────────┼────────────┐
│  ViewModel                                   │            │
│  ├─ SummaryViewModel                         │            │
│  │   ├─ settings: Settings ──────┐           │            │
│  │   ├─ currentEngine()          │           │            │
│  │   └─ loadFromUrl/File/Text()  │           │            │
└─────────────────────────────────────────────┼────────────┘
                  │                           │
┌─────────────────┼───────────────────────────┼────────────┐
│  Engine layer    │                           │            │
│  ├─ InferenceEngine (interface)              │            │
│  ├─ LlmEngine     (on-device, llama.cpp)     │            │
│  └─ CloudEngine   (OpenAI / Anthropic /      │            │
│                    Groq / Ollama / Custom)    │            │
└──────────────────────────────────────────────┼────────────┘
                  │                           │
┌─────────────────┼───────────────────────────┼────────────┐
│  Data layer      │                           │            │
│  ├─ Settings (per-provider config)           │            │
│  ├─ SecureStorage (encrypted API keys)       │            │
│  ├─ SessionStore (recent summaries)          │            │
│  └─ Exporter (MD / TXT / PDF)                │            │
└──────────────────────────────────────────────────────────┘
```

---

## Privacy

- **On-device mode**: no network access is required or performed. All
  processing runs locally via llama.cpp.
- **Cloud mode**: the source text is sent to the provider you choose, in the
  request you actively initiate. API keys are stored encrypted on-device
  with hardware-backed AES-256-GCM. Sonario does not proxy, log, or collect
  your keys or data.

---

## License

Same license as the original pgotta/Sonario-Android project.

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full v2.0.0 release notes.
