# BUILD.md - Sonario for Android

How to build the APK and set the app up. No NDK required; the on-device engine
(Llamatik) ships prebuilt native libraries.

## Prerequisites

- Android Studio (2024.2 / Koala or newer) with the Android SDK, **API 36**.
- JDK 17 (bundled with recent Android Studio). Build from Android Studio's menus,
  not a terminal using an older system Java.
- A phone with USB debugging on, or an arm64 emulator. Native libs are
  arm64-v8a only.

## Versions this project pins (keep consistent)

- Android Gradle Plugin: 8.7.3 (project `build.gradle.kts`)
- Kotlin: 2.2.20 (must match the Kotlin that Llamatik 1.7.0 was built with, or
  you get "incompatible metadata version" errors)
- Gradle wrapper: 8.9
- compileSdk / targetSdk: 36 (Llamatik 1.7.0 requires compiling against API 36)
- minSdk: 28

## 1. Open the project

Extract the project into an empty folder, then in Android Studio use
**File -> Open** and select that folder (the one containing `settings.gradle.kts`).
Do NOT use "New Project". Let Gradle sync finish; it downloads AGP, Compose,
OkHttp, Jsoup, and Llamatik from Google's Maven and Maven Central, so it needs
internet and a network that isn't doing SSL interception (corporate proxies or
some antivirus can cause "PKIX path building failed" during sync).

Tip: always extract into a fresh folder rather than merging over an old copy, so
stale files (e.g. leftover .webp launcher icons) don't cause "Duplicate
resources" errors.

## 2. Build the APK

**Build -> Build APK(s)**. Output: `app/build/outputs/apk/debug/app-debug.apk`.

Install: because this project replaces an older broken extractor, uninstall the
existing Sonario app from the phone first, then connect the phone with USB
debugging on and press **Run**. You can also use:

```text
adb uninstall ai.sonario.app
adb install app/build/outputs/apk/debug/app-debug.apk
```

This build is **version 1.1.0 (versionCode 2)** and Settings shows **YouTube
extractor 2**. That marker prevents accidentally testing an old cached APK. An
uninstall removes locally downloaded models and saved settings, so save your Groq
key first if needed. You can rename the APK to `Sonario.apk` before sharing; the
filename does not affect the installed app.

## 3. First launch and engine setup

The APK bundles no AI model. On first launch:

- **To use Groq cloud (fast):** tap **Use Groq cloud instead**, then open Settings
  and paste a free API key from console.groq.com. Set the engine toggle to
  **Groq cloud**.
- **To use on-device (private, slow):** tap **Get** on a model to download it in
  the app (~1 GB, Wi-Fi recommended), then set the toggle to **On-device**.

## Signed release APK (for sharing with others)

**Build -> Generate Signed Bundle / APK -> APK -> Create new keystore**. Keep the
`.jks` file and passwords safe (you need the same key for future updates). Output:
`app/build/outputs/apk/release/app-release.apk`. Sideloaded APKs trigger a Play
Protect "unrecognized app" prompt on the recipient's phone; that's normal for any
app installed outside the Play Store.

## Two engines

Both backends implement `llm/InferenceEngine.kt`:

- **On-device** (`LlmEngine`, Llamatik/llama.cpp): CPU-only, slow, private.
- **Groq cloud** (`GroqEngine`): fast, needs the user's own free key from
  console.groq.com, sends text to Groq. Key and model are set in the in-app
  Settings screen (stored in local SharedPreferences only). The Groq model is
  editable because Groq's lineup changes; default is Llama 4 Scout.

The engine is chosen per summary via the main-screen toggle. No build config is
needed for Groq; users add their key at runtime.

## Known issues / gotchas

- **On-device speed and heat.** CPU-only inference (no Adreno GPU backend in
  Llamatik). The bottom-left meter shows CPU near 100% during a run. Prefer Groq
  for real use.
- **YouTube captions** use `next` -> `get_transcript` first, then consistent
  ANDROID/WEB `player` requests and watch-page caption tracks. Caption URLs keep
  all signed parameters, and JSON3, XML, SRV3, TTML, and WebVTT are supported.
  These are undocumented endpoints; failures include HTTP/track diagnostics and
  the extractor build number in the error text.
- **Model files vanish on uninstall.** They live in the app's private
  `files/models/` dir, which Android deletes with the app. Newer Android also
  blocks file managers from browsing `Android/data` ("Root isn't available")-
  that's the file manager, not this app.
- **Duplicate resources on build.** Caused by leftover icon files (e.g. `.webp`
  from an earlier Image Asset run) colliding with the project's `.png` icons.
  Extract into a clean folder to avoid it.
- **APK size** is small (~6-7 MB) because no model is bundled.
- **Crash reports** are written to
  `Android/data/ai.sonario.app/files/last_crash.txt` and shown on next launch.

## Adding a self-hosted / remote engine

To point the app at a llama.cpp server or Ollama on a PC (LAN or Tailscale)
instead of Groq, implement `InferenceEngine` with HTTP calls to that server -
same shape as `GroqEngine`, different URL and payload - and select it in the
ViewModel. Nothing else in the app needs to change.
