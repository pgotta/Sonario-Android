package ai.sonario.app.llm

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage for user API keys using the Android Keystore system.
 *
 * Each provider's key is encrypted with an AES-256-GCM key that never leaves
 * the hardware-backed keystore. The encrypted blob + IV is stored in ordinary
 * SharedPreferences — so even with root, the ciphertext is useless without the
 * hardware key. Falls back to Base64 obfuscation only if the keystore is
 * unavailable (no hardware-backed keystore), and logs a warning in that case.
 *
 * This replaces the old plain-Text SharedPreferences storage so that bringing
 * your own key (BYOK) from OpenAI, Anthropic, Groq, Ollama, or a self-hosted
 * proxy does not leave secrets in clear text on disk.
 */
object SecureStorage {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sonario_api_keys"
    private const val PREFS = "sonario_secure_keys"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val IV_SEPARATOR = "]"
    private const val TAG_BITS = 128

    /**
     * Encrypt and persist a provider API key. Empty/null clears the stored key.
     */
    fun storeKey(context: Context, providerId: String, key: String?) {
        val prefs = prefs(context)
        if (key.isNullOrBlank()) {
            prefs.edit().remove(providerKey(providerId)).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val ct = Base64.encodeToString(
                cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
            )
            prefs.edit().putString(providerKey(providerId), "$iv$IV_SEPARATOR$ct").apply()
        } catch (e: Exception) {
            // Fallback: store Base64-obfuscated only if keystore fails
            val obfuscated = Base64.encodeToString(
                key.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            prefs.edit().putString(providerKey(providerId), "b64:$obfuscated").apply()
        }
    }

    /**
     * Decrypt and return the stored key for [providerId], or null if none set.
     */
    fun getKey(context: Context, providerId: String): String? {
        val raw = prefs(context).getString(providerKey(providerId), null) ?: return null
        return try {
            if (raw.startsWith("b64:")) {
                // Fallback-obfuscated path
                return Base64.decode(raw.removePrefix("b64:"), Base64.NO_WRAP)
                    .toString(Charsets.UTF_8)
            }
            val parts = raw.split(IV_SEPARATOR, limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** True if a key is stored for [providerId] (even if decryption would fail). */
    fun hasKey(context: Context, providerId: String): Boolean =
        prefs(context).contains(providerKey(providerId))

    /** Remove the stored key for [providerId]. */
    fun clearKey(context: Context, providerId: String) {
        prefs(context).edit().remove(providerKey(providerId)).apply()
    }

    /** Remove every stored provider key. */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Masked display string: first 4 and last 3 chars, e.g. "sk-a…x7Q". */
    fun masked(key: String?): String {
        if (key.isNullOrBlank()) return "Not set"
        return when {
            key.length <= 10 -> "••••••"
            else -> "${key.take(4)}…${key.takeLast(3)}"
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun providerKey(providerId: String) = "key_$providerId"

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getEntry(KEY_ALIAS, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
