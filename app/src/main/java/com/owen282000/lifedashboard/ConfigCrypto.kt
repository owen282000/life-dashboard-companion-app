package com.owen282000.lifedashboard

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for exported settings.
 *
 * An export can carry webhook auth headers, HMAC signing secrets and MQTT credentials. Those
 * travel through a share sheet, so they may end up in a chat app, a cloud drive or a mail
 * folder. Encrypting them under a user-chosen password means the file alone is not enough to
 * take over someone's webhook endpoints.
 *
 * AES-256-GCM with a PBKDF2-HMAC-SHA256 derived key. The salt and IV are random per export and
 * travel with the ciphertext in a small JSON envelope; GCM's tag makes a wrong password fail
 * cleanly rather than yielding garbage.
 *
 * Pure JVM crypto with no Android dependencies, so it is unit tested directly.
 */
object ConfigCrypto {

    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /**
     * Iteration count for the key derivation. High enough to make guessing a weak password
     * expensive, low enough to stay responsive on an older phone.
     */
    const val ITERATIONS = 210_000

    /** Marks an encrypted export so the importer knows a password is needed. */
    const val ENVELOPE_TYPE = "life-dashboard-encrypted-config"

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    /** Thrown when a file cannot be decrypted, almost always a wrong password. */
    class WrongPasswordException(message: String) : Exception(message)

    /**
     * Encrypts [plaintext] under [password], returning the JSON envelope to write to disk.
     */
    fun encrypt(plaintext: String, password: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Hand-built so the envelope stays readable and dependency-free.
        return """
            {
              "type": "$ENVELOPE_TYPE",
              "version": ${ConfigBackup.CURRENT_VERSION},
              "kdf": "$KEY_ALGORITHM",
              "iterations": $ITERATIONS,
              "salt": "${encoder.encodeToString(salt)}",
              "iv": "${encoder.encodeToString(iv)}",
              "ciphertext": "${encoder.encodeToString(ciphertext)}"
            }
        """.trimIndent()
    }

    /**
     * Decrypts an envelope produced by [encrypt].
     *
     * @throws WrongPasswordException when the password is wrong or the file was tampered with.
     */
    fun decrypt(envelope: String, password: String): String {
        val salt = decoder.decode(envelope.field("salt"))
        val iv = decoder.decode(envelope.field("iv"))
        val ciphertext = decoder.decode(envelope.field("ciphertext"))
        val iterations = envelope.field("iterations").toIntOrNull() ?: ITERATIONS

        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(password, salt, iterations),
                GCMParameterSpec(TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // AEADBadTagException and friends all mean the same thing to the user.
            throw WrongPasswordException("Wrong password, or the file is damaged")
        }
    }

    /** True when [text] is an encrypted export rather than a plain one. */
    fun isEncrypted(text: String): Boolean = text.contains("\"$ENVELOPE_TYPE\"")

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int = ITERATIONS) =
        SecretKeySpec(
            SecretKeyFactory.getInstance(KEY_ALGORITHM)
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS))
                .encoded,
            "AES"
        )

    /**
     * Reads one value out of the envelope. A tiny reader rather than a JSON parse, because the
     * envelope shape is fixed and written by [encrypt] alone.
     */
    private fun String.field(name: String): String =
        Regex("\"$name\"\\s*:\\s*\"?([^\",}\\s]+)\"?")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?: throw WrongPasswordException("Not a valid encrypted export: missing \"$name\"")
}
