package com.openauto.dash

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A Gemini key shipped inside the app encrypted, so the public repo and APK
 * never hold it in the clear. Typing the short activation code in the AI
 * mechanic settings decrypts it once; it's then saved like a typed key.
 *
 * AES-256-GCM under a key stretched from the code with PBKDF2-SHA256, slow on
 * purpose so guessing codes against the public ciphertext is costly. A short
 * code is a lock, not a vault: someone determined could still brute-force it,
 * so this key is meant to be rotated (a new key gets a new code and [SEALED]).
 */
object AiKeyVault {

    /** base64(salt 16 | iv 12 | ciphertext + tag), made with [seal]. Empty = no built-in key. */
    private const val SEALED =
        "AEPpm9vQDoNKcIz8WSbmiK6yYzbgZHFFjwtnhJtvwMc7IHi2JtPhKMJUcfeLEQ3NOhpRlYlLAdPXtI9EJLPKPjBgUwHmEjRUJ32DeGqEC5OgFnwK8hkzdTr+KDaH40i/+A=="

    private const val ITERATIONS = 200_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    val available: Boolean get() = SEALED.isNotEmpty()

    /** The built-in key, or null when [code] is wrong. Slow by design: call it off the main thread. */
    fun unlock(code: String): String? = open(SEALED, code)

    internal fun open(sealed: String, code: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        val salt = bytes.copyOfRange(0, SALT_BYTES)
        val iv = bytes.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(code, salt), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes, SALT_BYTES + IV_BYTES, bytes.size - SALT_BYTES - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    /** Encrypts [key] under [code]: how [SEALED] is produced, and used by the tests. */
    internal fun seal(key: String, code: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret(code, salt), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(salt + iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8)))
    }

    private fun secret(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(normalize(code).toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** Typed on a car screen, so case and spaces don't matter. */
    internal fun normalize(code: String): String = code.filterNot { it.isWhitespace() }.uppercase()
}
