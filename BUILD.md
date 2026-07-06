# BUILD.md - Sonario for Android

## Reality check first

This builds and runs, but on-device inference is CPU-only and slow (see README).
Build it to explore or to later swap in a faster/remote engine, not for daily use.

## Prerequisites

- Android Studio (2024.2 / Koala or newer) with the Android SDK, **API 36**.
- JDK 17 (bundled with recent Android Studio). Do NOT build from a terminal that
  uses an older system Java; use Android Studio's menus, which use its bundled 17.
- A phone or arm64 emulator. Native libs are arm64-v8a only.

No NDK, no CMake. Llamatik ships prebuilt llama.cpp binaries.

## Versions this project expects

These are pinned in the Gradle files and must be consistent:

- Android Gradle Plugin: 8.7.3 (`build.gradle.kts`, project level)
- Kotlin: 2.2.20 (project level) - must match the Kotlin that Llamatik 1.7.0 was
  built with, or the compiler throws "incompatible metadata version" errors.
- Gradle wrapper: 8.9 (`gradle/wrapper/gradle-wrapper.properties`)
- compileSdk / targetSdk: 36 (`app/build.gradle.kts`) - Llamatik 1.7.0 requires
  compiling against API 36.
- minSdk: 28

If you bump Kotlin, you may also need to bump the Compose BOM and the Llamatik
version together.

## Steps

1. Open the project folder (the one containing `settings.gradle.kts`) via
   **File -> Open**. Do NOT use "New Project". Let Gradle sync finish; it will
   download AGP, Compose, OkHttp, Jsoup, and Llamatik. This needs internet and a
   network that isn't doing SSL interception (corporate proxies / some antivirus
   can cause "PKIX path building failed" during sync; build off such networks).
2. If Gradle can't resolve `com.llamatik:library-android:1.7.0`, that artifact
   name is already the explicit Android variant; make sure Maven Central is
   reachable.
3. Build: **Build -> Build APK(s)**. The APK lands at
   `app/build/outputs/apk/debug/app-debug.apk`.
4. Install: connect the phone with USB debugging on, then **Run**, or
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## First launch

The APK contains no model. On first launch, pick one in the setup screen and it
downloads in-app (use Wi-Fi). After it finishes, the app goes to the summarizer.

## Signed release APK (for sharing)

For sending to others, build a signed release instead of debug:
**Build -> Generate Signed Bundle / APK -> APK -> Create new keystore**. Keep the
`.jks` file and passwords safe; you need the same key for future updates. The
output is `app/build/outputs/apk/release/app-release.apk`. Sideloaded APKs trigger
a Play Protect "unrecognized app" prompt on the recipient's phone; that's normal.

## Known issues / gotchas

- **Speed and heat.** CPU-only inference. The `gpuLayers = -1` flag in
  `LlmEngine.kt` is a best-effort request; on Adreno it falls back to CPU because
  Llamatik has no OpenCL/Vulkan Adreno backend. The `SysMeter` (bottom-left) shows
  CPU near 100% during a summary, confirming this.
- **YouTube captions** rely on scraping YouTube's InnerTube `player` endpoint plus
  a watch-page fallback. If YouTube changes these, captions stop working; the fix
  lives in `SourceFetcher.kt` (`findCaptionViaInnerTube` / `findCaptionViaWatchPage`).
- **Model files vanish on uninstall.** They live in the app's private
  `files/models/` dir, which Android deletes with the app. Reinstalling requires
  re-downloading the model. Newer Android also blocks file managers from browsing
  `Android/data`, so you may not see the folder ("Root isn't available") - that's
  the file manager, not this app.
- **Header radar icon.** The launcher icon (the radar tile) is set up across all
  densities and works. A small radar mark was also added beside the in-app title;
  if it doesn't render on your device, it's cosmetic only.
- **APK size** is small (~6 MB) because the model is not bundled by design.

## Swapping to a remote engine (the usable path)

All inference is in `app/src/main/java/ai/sonario/app/llm/LlmEngine.kt`. Replace
the Llamatik calls in `ensureLoaded`, `stream`, and `complete` with HTTP requests
to a llama.cpp server or Ollama running on a PC (reachable over LAN or Tailscale).
Nothing else in the app needs to change. This gives desktop-class speed with the
mobile UI.
