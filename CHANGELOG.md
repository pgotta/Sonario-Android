# Changelog

All notable changes to Sonario for Android are documented here.

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
