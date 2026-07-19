# Changelog

All notable changes to Sonario for Android are documented here.

## 1.5.0

- Replaces the previous Qwen2.5 1.5B, Llama 3.2 3B, and Phi-3.5 Mini downloads
  with three newer mobile-oriented choices:
  - Qwen3 4B Instruct 2507 for the strongest overall local summaries and Q&A.
  - Gemma 3n E4B Instruct for nuanced long-form summaries and writing.
  - LFM2 2.6B for the smallest download and fastest local responses.
- Shows a plain-language strength/tradeoff description and formatted download size
  before the user downloads each model.
- Updates the Android llama.cpp wrapper to Llamatik 1.8.1 for current Qwen3,
  Gemma 3n, and LFM2 GGUF support.
- Adds a free-storage check and fixes resumable downloads when a host ignores an
  HTTP Range request.
- Removes obsolete local-model and partial-download files left by older versions.
- Keeps all model files in private app storage, explicitly disables uninstall data
  retention, and excludes the `models/` directory from cloud backup and device
  transfer so multi-gigabyte models cannot return after reinstall.

## 1.4.0

- Replaces the retired Llama 4 Scout cloud model with the fixed Groq model
  `qwen/qwen3.6-27b`.
- Automatically ignores and migrates stale model IDs stored by older installs or
  saved sessions.
- Resizes cloud chunks and output budgets so each request fits beneath Qwen's
  free-tier 8K-token-per-minute limit.
- Queues requests against conservative local TPM, RPM, and daily budgets instead
  of repeatedly hitting minute-based 429 errors.
- Reads Groq's live remaining-token and reset headers and displays a countdown
  while waiting for the provider's actual token window.
- Detects organization-wide daily exhaustion, stops without retrying all day, and
  preserves completed checkpoints for Resume.
- Uses Qwen's non-thinking mode for routine summaries to reduce unnecessary token
  consumption.

## 1.3.3

- Keeps the Ask field visible when the software keyboard opens.
- Adds two-to-four-line question editing and a keyboard Send action.
- Preserves unfinished questions across ordinary Activity recreation.

## 1.3.2

- Restored the model-label and summary-view helper components required to build
  the 1.3.x interface.

## 1.3.1

- Added a confirmed **Clear** action for all locally stored recent sessions.
- Clears saved source text, summaries, checkpoints, chapter data, and Q&A while
  leaving deliberately exported files untouched.

## 1.3.0

- Added up to 12 locally saved recent sessions.
- Restores the latest session after app or Activity recreation.
- Checkpoints completed model calls so interrupted jobs can resume without
  repeating already completed Groq requests.
- Saves source text, summaries, chapter results, and Ask history.
- Posts a completion notification when a background summary finishes.

## 1.2.0

- Added a foreground service, partial wake lock, temporary Wi-Fi lock, and
  connectivity-aware retries for long summaries.
- Improved Groq errors, rate-limit waits, cancellation, and response buffering.
- Made Ask failures visible and grounded questions against relevant portions of
  the complete source.

## 1.1.2

- Added the missing `FileTextExtractor` import required by the file picker.

## 1.1.1

- Added the missing `RateLimiter` import and fixed cascading type errors.

## 1.1.0

- Replaced the original YouTube extractor with a cookie-preserving layered
  InnerTube implementation.
- Uses live transcript parameters and client configuration when available.
- Preserves signed caption URL parameters and parses JSON3, legacy XML, SRV3,
  TTML, and WebVTT.
- Added clearer diagnostics for restricted and Proof-of-Origin-token tracks.
