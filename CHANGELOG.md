# Changelog

All notable changes to Sonario for Android are documented here.

## 1.4.0 — Multi-provider BYOK

### New features
- **Multi-provider cloud inference.** The old Groq-only engine is replaced by
  a generic `CloudEngine` that supports **Groq, OpenAI, Anthropic (Claude),
  Ollama, and any OpenAI-compatible proxy** from a single settings screen.
- **Bring-your-own-key (BYOK).** Paste an API key for any provider in
  **Settings → Providers**. Keys are encrypted with AES-256-GCM in the
  Android Keystore and never stored in plaintext.
- **Per-provider configuration.** Each provider stores its own model, custom
  base URL, and temperature. Switching providers restores its config.
- **Custom base URL.** Point at a self-hosted Ollama server, a corporate
  proxy, or any OpenAI-compatible endpoint.
- **Anthropic native path.** Claude models use the Anthropic Messages API
  (`x-api-key` header, `anthropic-version: 2023-06-01`, native SSE parsing).
- **Ollama support.** Run models on your local network — no API key needed.
- **Provider dropdown** in Settings with suggested models per provider.
- **Masked key preview** — shows `sk-a…x7Q` instead of the full key.
- **Daily budget tracking** retained and generalized for any provider.

### Security
- **Removed hardcoded Google / YouTube API key** from `SourceFetcher`. The
  bootstrap now relies solely on the key extracted from the watch page; if
  absent, the transcript path fails gracefully instead of using a shared key.
- **Encrypted key storage.** All provider API keys are stored in
  `EncryptedSharedPreferences`-equivalent hardware-backed storage
  (`SecureStorage`), replacing the old plain-Text SharedPreferences.

### Improvements
- **Engine toggle** now reads "Cloud" (provider-agnostic) instead of
  "Groq cloud".
- **SummaryScreen header** shows the selected cloud provider name.
- **ProgressCard** shows an indeterminate spinner for non-condensing phases
  and a cancel button.
- **RateLimiter** is now provider-agnostic with configurable TPM/TPD caps.
- **SessionStore** persists `cloudProviderId` + `cloudModel` so resuming a
  session restores the right provider.
- **Legacy key migration.** On first launch, a Groq key stored in the old
  plain-Text prefs is automatically moved to encrypted storage.
- **Unsafe casts** in `CrashReporter` and `SummaryService` replaced with
  safe alternatives.
- **Empty catch blocks** now log instead of silently swallowing.

### Breaking changes
- `EngineChoice.GROQ` is replaced by `EngineChoice.CLOUD`. The selected
  provider is a separate field (`cloudProvider`). Custom integrations using
  the old enum must update.
- `Settings.groqApiKey` / `Settings.groqModel` are replaced by
  `Settings.keyFor(provider)` / `Settings.modelFor(provider)`. A migration
  runs automatically for existing Groq keys.
- `GroqEngine` is replaced by `CloudEngine`. The constructor signature is
  different; existing code must be updated.

## 1.3.3

- Keeps the Ask field visible when the software keyboard opens.
- Adds two-to-four-line question editing and a keyboard-safe Ask field.

## 1.3.2

- Fixes YouTube extraction regressions (consent, client identity, URL
  truncation).

## 1.3.1

- Adds the Models screen for switching GGUF models.

## 1.3.0

- First release with in-app GGUF download (no adb push required).
