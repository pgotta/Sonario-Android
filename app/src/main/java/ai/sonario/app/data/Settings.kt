package ai.sonario.app.data

import android.content.Context
import ai.sonario.app.llm.LlmProvider
import ai.sonario.app.llm.ProviderConfig
import ai.sonario.app.llm.SecureStorage

/**
 * Per-provider settings, backed by SharedPreferences.
 *
 * API keys are stored in [SecureStorage] (hardware-backed encryption),
 * not here. This class holds only the non-secret configuration: which
 * provider is active, which model, the custom base URL, temperature, etc.
 *
 * Backward-compatible: if the user had a Groq key in the old plain
 * SharedPreferences, we migrate it into SecureStorage on first read.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sonario_settings", Context.MODE_PRIVATE)

    init {
        migrateLegacyKey(context)
    }

    var engine: EngineChoice
        get() = if (prefs.getString(KEY_ENGINE, "on_device") == "cloud") {
            EngineChoice.CLOUD
        } else {
            EngineChoice.ON_DEVICE
        }
        set(v) = prefs.edit()
            .putString(KEY_ENGINE, if (v == EngineChoice.CLOUD) "cloud" else "on_device")
            .apply()

    /** Currently selected cloud provider. */
    var cloudProvider: LlmProvider
        get() = LlmProvider.fromId(prefs.getString(KEY_CLOUD_PROVIDER, LlmProvider.GROQ.id))
        set(v) = prefs.edit().putString(KEY_CLOUD_PROVIDER, v.id).apply()

    /** Selected model per provider: key = provider id, value = model string. */
    fun modelFor(provider: LlmProvider): String {
        val fallback = provider.suggestedModels.firstOrNull() ?: ""
        return prefs.getString("$KEY_MODEL_PREFIX${provider.id}", null) ?: fallback
    }

    fun setModelFor(provider: LlmProvider, model: String) {
        prefs.edit().putString("$KEY_MODEL_PREFIX${provider.id}", model.trim()).apply()
    }

    /** Custom base URL override per provider (empty = use default). */
    fun customBaseUrlFor(provider: LlmProvider): String =
        prefs.getString("$KEY_BASEURL_PREFIX${provider.id}", "") ?: ""

    fun setCustomBaseUrlFor(provider: LlmProvider, url: String) {
        prefs.edit().putString("$KEY_BASEURL_PREFIX${provider.id}", url.trim()).apply()
    }

    /** Temperature setting per provider. */
    fun temperatureFor(provider: LlmProvider): Float =
        prefs.getFloat("$KEY_TEMP_PREFIX${provider.id}", 0.35f)

    fun setTemperatureFor(provider: LlmProvider, value: Float) {
        prefs.edit().putFloat("$KEY_TEMP_PREFIX${provider.id}", value.coerceIn(0f, 2f)).apply()
    }

    /** Max output tokens the user wants for normal summaries. */
    var maxOutputTokens: Int
        get() = prefs.getInt(KEY_MAX_OUTPUT, 1024)
        set(v) = prefs.edit().putInt(KEY_MAX_OUTPUT, v.coerceIn(256, 8192)).apply()

    /** True if the provider has a stored API key. */
    fun hasKeyFor(provider: LlmProvider): Boolean =
        SecureStorage.hasKey(context, provider.id)

    /** Returns the stored API key for [provider], or null if not set. */
    fun keyFor(provider: LlmProvider): String? =
        SecureStorage.getKey(context, provider.id)

    /** Stores (or clears) the API key for [provider]. */
    fun setKeyFor(provider: LlmProvider, key: String?) {
        SecureStorage.storeKey(context, provider.id, key)
    }

    /** Builds a [ProviderConfig] for [provider] from the saved settings. */
    fun configFor(provider: LlmProvider): ProviderConfig = ProviderConfig(
        provider = provider,
        model = modelFor(provider),
        customBaseUrl = customBaseUrlFor(provider),
        temperature = temperatureFor(provider),
        maxTokens = maxOutputTokens,
    )

    // ── legacy migration ─────────────────────────────────────────────────────

    private fun migrateLegacyKey(context: Context) {
        if (!prefs.contains(KEY_LEGACY_GROQ_KEY)) return
        val legacy = prefs.getString(KEY_LEGACY_GROQ_KEY, null)
        if (!legacy.isNullOrBlank()) {
            SecureStorage.storeKey(context, LlmProvider.GROQ.id, legacy)
        }
        // Also bring over the legacy model string if present
        prefs.getString(KEY_LEGACY_GROQ_MODEL, null)?.let { oldModel ->
            if (oldModel.isNotBlank() && !prefs.contains("$KEY_MODEL_PREFIX${LlmProvider.GROQ.id}")) {
                setModelFor(LlmProvider.GROQ, oldModel)
            }
        }
        prefs.edit()
            .remove(KEY_LEGACY_GROQ_KEY)
            .remove(KEY_LEGACY_GROQ_MODEL)
            .apply()
    }

    companion object {
        private const val KEY_ENGINE = "engine"
        private const val KEY_CLOUD_PROVIDER = "cloud_provider"
        private const val KEY_MODEL_PREFIX = "cloud_model_"
        private const val KEY_BASEURL_PREFIX = "cloud_baseurl_"
        private const val KEY_TEMP_PREFIX = "cloud_temp_"
        private const val KEY_MAX_OUTPUT = "max_output_tokens"

        // Legacy keys that we migrate away from.
        private const val KEY_LEGACY_GROQ_KEY = "groq_api_key"
        private const val KEY_LEGACY_GROQ_MODEL = "groq_model"

        /** @deprecated Use [Settings.modelFor] with a specific [LlmProvider]. */
        @Deprecated("Use per-provider model", ReplaceWith("modelFor(LlmProvider.GROQ)"))
        const val DEFAULT_GROQ_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"
    }
}

/** The on-device vs cloud choice the engine toggle shows. */
enum class EngineChoice { ON_DEVICE, CLOUD }
