package ai.sonario.app.llm

/**
 * A selectable cloud LLM provider. Each has a stable [id] used as the
 * SharedPreferences key, a human [displayName], a default API [baseUrl],
 * and a curated list of [suggestedModels] the user can pick from.
 *
 * Adding a new provider means adding an entry here and an implementation in
 * [ai.sonario.app.llm.CloudEngine] — nothing else in the app needs to change.
 */
enum class LlmProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val suggestedModels: List<String>,
    val needsKey: Boolean = true,
) {
    GROQ(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        suggestedModels = listOf(
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "mixtral-8x7b-32768",
        ),
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        suggestedModels = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4-turbo",
            "gpt-3.5-turbo",
            "o1-mini",
        ),
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        suggestedModels = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229",
        ),
    ),
    OLLAMA(
        id = "ollama",
        displayName = "Ollama (local)",
        baseUrl = "http://localhost:11434/v1",
        suggestedModels = listOf(
            "llama3.2",
            "llama3.1",
            "mistral",
            "phi3",
            "qwen2.5",
        ),
        needsKey = false,
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom (OpenAI-compatible)",
        baseUrl = "",
        suggestedModels = emptyList(),
        needsKey = false,
    ),

    ;

    companion object {
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: GROQ
    }
}

/**
 * Per-provider configuration the user actually saves: which provider, which
 * model, and optionally a custom base URL (for proxies / self-hosted).
 * The API key lives in [SecureStorage], keyed by [LlmProvider.id].
 */
data class ProviderConfig(
    val provider: LlmProvider,
    val model: String,
    val customBaseUrl: String = "",
    val temperature: Float = 0.35f,
    val maxTokens: Int = 4096,
) {
    /** Effective base URL: user override if set, otherwise provider default. */
    val resolvedBaseUrl: String
        get() = customBaseUrl.trim().ifBlank { provider.baseUrl }
}
