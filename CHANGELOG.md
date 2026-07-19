# Changelog — v2.0.0 "Multi-Provider BYOK"

Release date: 2026-07-19
Branch: `upgrade/multi-provider-byok`

This release transforms Sonario-Android from a Groq-only cloud summariser
into a BYOK (bring-your-own-key) app with support for **five** LLM backends,
encrypted credential storage, and a significantly improved UI/UX.

---

## New features

### Multi-provider cloud support
- **OpenAI** — full streaming via the `/chat/completions` endpoint.
- **Anthropic** — native Messages API with SSE streaming, `x-api-key` header.
- **Groq** — unchanged feature-wise, now one of many options.
- **Ollama** — first-class local-server support (no API key required).
- **Custom** — any OpenAI-compatible proxy / self-hosted endpoint.

### Bring-your-own-key (BYOK)
- Each provider's API key is stored in **hardware-backed AES-256-GCM**
  encryption via the Android Keystore — never in plaintext on disk.
- Keys never leave the device except in the `Authorization` header of
  requests the user actively initiates.
- Automatic migration of the legacy Groq key from plain SharedPreferences
  into encrypted storage on first launch.

### UI / UX improvements
- **Provider selector chips** in Settings — switch backend in one tap.
- **Model dropdown** with curated suggestions per provider, fully editable.
- **Custom base URL** field for proxies and self-hosted deployments.
- **Temperature slider** (0–2) and **max-output slider** (256–8192 tokens).
- **Show/Hide/Copy** actions on the API-key field.
- **Key status indicator** — at a glance see which providers are ready.
- **Provider badge** on the SummaryScreen showing active backend and model.
- **Inline Cancel** button during summarisation runs.
- **Inline Retry** button on errors with friendly, categorised messages
  (auth failure, rate-limit, network, SSL, unknown).
- **Share** action on completed summaries.
- **Empty state** with clear first-run guidance.

### Architecture
- New `CloudEngine` replaces `GroqEngine` — one implementation, any provider.
- `ProviderConfig` data class captures all per-provider settings.
- `SecureStorage` object centralises all credential encryption / decryption.
- `Settings` now stores per-provider model, URL, and temperature.
- `RateLimiter` is provider-agnostic with configurable TPM / TPD caps.
- Custom exception types (`AuthenticationException`, `RateLimitException`)
  allow the UI to show specific error surfaces.

### Bug fixes
- **Removed hardcoded Google/YouTube API key** (`PUBLIC_INNERTUBE_KEY`) from
  `SourceFetcher.kt` — this was a leaked secret in the original code.
- Fixed unsafe `ClassCastException`-prone casts in `CrashReporter` and
  `SummaryService`.
- Eliminated empty catch blocks that silently swallowed exceptions.
- Fixed `GroqEngine`'s Groq-specific rate-limit headers that made the engine
  unusable with other providers.
- Fixed `SummaryViewModel` mixing process-scoped static state with the
  ViewModel lifecycle by converting to proper `mutableStateOf` holders.
- Settings migration keeps the user's existing Groq key and model across the
  upgrade with zero manual steps.

---

## Verification steps

### 1. On-device path (no network needed)
1. Install the APK.
2. Open Settings → Engine → keep **On-device**.
3. Go to Models and download a GGUF model.
4. Paste any text → confirm a summary appears.

### 2. Cloud path — Groq
1. Open Settings → Engine → **Cloud** → provider **Groq**.
2. Paste your Groq API key (format `gsk_…`).
3. Confirm the "✓ Key stored securely" banner appears.
4. Select a model (e.g. `llama-3.3-70b-versatile`).
5. Paste text and verify streaming summary.

### 3. Cloud path — OpenAI
1. Settings → Cloud → tap **OpenAI** chip.
2. Paste an `sk-…` key.
3. Pick `gpt-4o-mini`.
4. Summarise a URL — confirm the header reads `OpenAI · gpt-4o-mini`.

### 4. Cloud path — Anthropic
1. Settings → Cloud → tap **Anthropic** chip.
2. Paste an `sk-ant-…` key.
3. Pick `claude-3-5-sonnet-20241022`.
4. Verify the summary is well-formed (Anthropic responses are native, not
   OpenAI-compat, and are fully handled).

### 5. Cloud path — Ollama (local server)
1. Settings → Cloud → tap **Ollama** chip.
2. Confirm no key is needed (status shows "No key needed").
3. Optionally set the base URL if your Ollama server isn't on localhost.
4. Pick a model you pulled locally (e.g. `llama3.2`).
5. Verify summarisation works without any API key.

### 6. Custom proxy
1. Settings → Cloud → tap **Custom** chip.
2. Enter the OpenAI-compatible base URL of your proxy.
3. Paste a proxy key if required.
4. Enter the model string your proxy expects.
5. Summarise and verify.

### 7. BYOK security
1. Enter a key, close settings, reopen — confirm the key persists (encrypted).
2. Reboot the device, reopen the app — confirm the key still decrypts.
3. In Settings, tap the eye icon — confirm the key shows plaintext.
4. Tap the copy icon — confirm it copies the raw key.

### 8. Error handling
1. With no network: start a cloud summary → confirm "No network connection"
   message (not a raw stack trace).
2. With an invalid key: confirm "Authentication failed" message.
3. With a very long source: confirm graceful progress and completion.

---

## Setup & build instructions

### Prerequisites
- Android Studio Koala (2024.1.1) or newer.
- JDK 17.
- Android SDK 36, min SDK 28.

### Build
```bash
git clone -b upgrade/multi-provider-byok \
  https://github.com/stupidgiraffe/Sonario-Android.git
cd Sonario-Android
./gradlew assembleDebug
```

The output APK is at `app/build/outputs/apk/debug/app-debug.apk`.

### Run tests
```bash
./gradlew test
```

### Required setup after install
1. Open the app.
2. Tap the gear icon → **Settings**.
3. Choose **On-device** (default) or **Cloud**.
4. If Cloud, pick a provider and paste your API key.
5. Pick a model and start summarising.

### Migrating from v1.x
The upgrade is seamless: the first time you open the app, your existing
Groq key (if any) is migrated from plain SharedPreferences into the new
encrypted store. No manual export/import required.

---

## Known limitations / future work
- On-device inference remains CPU-bound; GGUF model selection is still manual.
- No prompt-editor UI yet (prompts are still hardcoded in `Prompts.kt`).
- No batch / queue for multiple sources.
- No dark/light theme toggle (the app follows the system setting).

---

## Files changed

| File | Change |
|------|--------|
| `app/src/main/java/ai/sonario/app/llm/SecureStorage.kt` | **new** — encrypted key storage |
| `app/src/main/java/ai/sonario/app/llm/ProviderConfig.kt` | **new** — provider enum + config |
| `app/src/main/java/ai/sonario/app/llm/CloudEngine.kt` | **new** — multi-provider engine |
| `app/src/main/java/ai/sonario/app/llm/InferenceEngine.kt` | updated — cloud + on-device docs |
| `app/src/main/java/ai/sonario/app/llm/RateLimiter.kt` | refactored — provider-agnostic |
| `app/src/main/java/ai/sonario/app/data/Settings.kt` | refactored — per-provider config |
| `app/src/main/java/ai/sonario/app/ui/SummaryViewModel.kt` | refactored — multi-provider |
| `app/src/main/java/ai/sonario/app/ui/SettingsScreen.kt` | rewritten — multi-provider UI |
| `app/src/main/java/ai/sonario/app/ui/SummaryScreen.kt` | rewritten — improved UX |
| `app/build.gradle.kts` | updated (deps if needed) |
| `CHANGELOG.md` | **new** — this file |
| `README.md` | updated — new setup + BYOK docs |
