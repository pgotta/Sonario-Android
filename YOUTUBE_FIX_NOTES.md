# YouTube transcript fix — Sonario 1.1.0

This build replaces the original Claude-generated YouTube extraction code.

## What was wrong

- It generated a fake `get_transcript` parameter from the video ID. YouTube's
  endpoint requires the real opaque `getTranscriptEndpoint.params` token.
- It mixed WEB/IOS/MWEB request bodies with an Android YouTube-app User-Agent.
- Its Android client version was stale.
- It removed every query parameter after `fmt=` from signed caption URLs.
- It requested SRV3 captions but only understood old `<text>` XML.
- The installed APK could not be distinguished from an older cached build.

## What changed

- One cookie-preserving YouTube session, including consent-page handling.
- Dynamic API key, WEB client version, visitor data, and transcript parameters.
- Preferred `next` -> `get_transcript` JSON route.
- Current consistent ANDROID player fallback (`20.10.38`) plus a consistent WEB
  player fallback and watch-page caption tracks.
- Safe `fmt` replacement through OkHttp's URL builder.
- JSON3, legacy XML, SRV3, TTML, and WebVTT parsers.
- Explicit Proof-of-Origin-token diagnostics.
- App version raised to 1.1.0 / versionCode 2; Settings displays extractor 2.

## Test checklist

1. Extract into a new folder and let Android Studio finish Gradle sync.
2. Uninstall the old `ai.sonario.app` from the phone.
3. Build and install this project.
4. Open Settings and confirm `Sonario 1.1.0 • YouTube extractor 2`.
5. Test the same captioned video that previously returned `status=200 len=0`.
6. If it still fails, copy the full new diagnostics. They identify whether the
   failure occurred at watch bootstrap, `/next`, `/get_transcript`, player track
   discovery, a PO-token requirement, or timedtext parsing.

YouTube's transcript endpoints are unofficial and can change. This code removes
known implementation bugs but cannot guarantee that every restricted, private,
age-gated, members-only, or sign-in-required video will work anonymously.
