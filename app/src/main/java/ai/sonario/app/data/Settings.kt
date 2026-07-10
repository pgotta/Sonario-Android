package ai.sonario.app.data

import android.content.Context

enum class EngineChoice { ON_DEVICE, GROQ }

/**
 * Simple local settings, backed by SharedPreferences. Stores the engine choice,
 * the user's Groq API key, and the Groq model string. The key never leaves the
 * device except in the Authorization header of requests the user initiates.
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

    var groqModel: String
        get() = prefs.getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL)
            ?: DEFAULT_GROQ_MODEL
        set(v) = prefs.edit().putString(KEY_GROQ_MODEL,
            v.trim().ifBlank { DEFAULT_GROQ_MODEL }).apply()

    val hasGroqKey: Boolean get() = !groqApiKey.isNullOrBlank()

    companion object {
        private const val KEY_ENGINE = "engine"
        private const val KEY_GROQ_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        // Default model. Groq's lineup changes; this is user-editable in Settings.
        const val DEFAULT_GROQ_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"
    }
}
