# Contributing

Thanks for helping improve Sonario.

## Before opening an issue

- Confirm the issue occurs on the newest version.
- For YouTube failures, include the full extractor diagnostics shown by Sonario,
  the link shape used, and whether the video is public and captioned. Do not post
  private videos, cookies, or API keys.
- For Groq failures, include the HTTP status or displayed error, but redact the
  API key.
- For Android lifecycle/background problems, include the phone model, Android
  version, battery-optimization setting, and approximate time before failure.

## Development

1. Use JDK 17 and Android SDK 36.
2. Open the repository root in Android Studio and let Gradle sync.
3. Build with `./gradlew assembleDebug` or **Build > Build APK(s)**.
4. Keep credentials and signing keys outside the repository.
5. Test both Groq and on-device paths when changing shared summarization code.

## Pull requests

Keep changes focused and explain the user-visible behavior. Include reproduction
steps for bug fixes and note any migration or session-storage implications.
