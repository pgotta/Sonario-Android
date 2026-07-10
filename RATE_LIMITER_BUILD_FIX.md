# RateLimiter build fix

The project contained `app/src/main/java/ai/sonario/app/llm/RateLimiter.kt`, but `SummaryViewModel.kt` is in a different package and did not import it.

Added:

```kotlin
import ai.sonario.app.llm.RateLimiter
```

The two type-inference errors reported on `return u.used to u.limit` were cascading errors caused by the unresolved `RateLimiter` type.

Version bumped to 1.1.1 (versionCode 3) so the corrected build is easy to distinguish.
