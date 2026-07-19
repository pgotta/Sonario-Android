package ai.sonario.app.data

import android.content.Context

enum class EngineChoice { ON_DEVICE, GROQ }

/**
 * Simple local settings, backed by SharedPreferences. Stores the engine choice
 * and the user's Groq API key. Cloud inference is intentionally pinned to one
 * supported model so stale saved sessions cannot restore a retired model ID.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sonario_settings", Context.MODE_PRIVATE)

    var engine: EngineChoice
        get() = if (prefs.getString(KEY_ENGINE, "on_device") == "groq")
            EngineChoice.GROQ else EngineChoice.ON_DEVICE
        set(v) = prefs.edit()
            .putString(KEY_ENGINE, if (v == EngineChoice.GROQ) "groq" else "on_device")
            .apply()

    var groqApiKey: String?
        get() = prefs.getString(KEY_GROQ_KEY, null)
        set(v) = prefs.edit().putString(KEY_GROQ_KEY, v?.trim()).apply()

    /**
     * Kept as a property for saved-session compatibility, but Sonario no longer
     * accepts an arbitrary cloud model. Reading or writing this value always
     * migrates it to the current fixed model.
     */
    var groqModel: String
        get() {
            if (prefs.getString(KEY_GROQ_MODEL, null) != DEFAULT_GROQ_MODEL) {
                prefs.edit().putString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL).apply()
            }
            return DEFAULT_GROQ_MODEL
        }
        set(@Suppress("UNUSED_PARAMETER") value) {
            prefs.edit().putString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL).apply()
        }

    val hasGroqKey: Boolean get() = !groqApiKey.isNullOrBlank()

    companion object {
        private const val KEY_ENGINE = "engine"
        private const val KEY_GROQ_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"

        const val DEFAULT_GROQ_MODEL = "qwen/qwen3.6-27b"
    }
}
