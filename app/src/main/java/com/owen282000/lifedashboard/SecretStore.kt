package com.owen282000.lifedashboard

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts one value. [encrypt] returns the IV followed by the ciphertext and its
 * tag; [decrypt] throws when the blob was not made with this key and [aad], or was altered.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a key the Android Keystore generates and holds, the replacement for the
 * deprecated security-crypto that Google itself recommends. The key never leaves the Keystore
 * and is not bound to user authentication or an unlocked device, so a worker can use it while
 * the phone is locked; StrongBox is not asked for (slower, and nothing here needs it).
 */
class KeystoreCipher private constructor(private val key: SecretKey) : SecretCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks a random IV (randomized encryption is required by default).
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    /** The cipher, and whether its key was made just now (so nothing encrypted before can be read). */
    class Opening(val cipher: SecretCipher, val created: Boolean)

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /**
         * Opens the key under [alias], generating it when there is none. Throws when the
         * Keystore cannot be used right now; the caller decides when a failure is permanent.
         */
        fun open(alias: String): Opening {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return Opening(KeystoreCipher(it), created = false) }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return Opening(KeystoreCipher(generator.generateKey()), created = true)
        }

        /** Removes the key under [alias]; everything encrypted with it becomes unreadable. */
        fun delete(alias: String) {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }
    }
}

/**
 * A [SharedPreferences] that keeps its string values encrypted with [cipher] in [backing], so
 * every place that used the old encrypted preferences keeps calling getString and putString.
 *
 * A value is stored as `v1:` and the base64 of IV, ciphertext and tag, with `v1:` plus the key
 * name as associated data, so a value copied under another key does not decrypt. Key names stay
 * readable: they name settings (`health_webhook_secret`), not secrets. A value that does not
 * decrypt reads as absent rather than failing the caller. Keys starting with
 * [INTERNAL_PREFIX] are [SecretVault]'s bookkeeping, never values, and are invisible here.
 *
 * Only strings are stored; nothing secret is anything else. The other typed puts throw, so a
 * secret can never land unencrypted by accident.
 */
class EncryptedStore(private val backing: SharedPreferences, private val cipher: SecretCipher) : SharedPreferences {

    override fun getString(key: String, defValue: String?): String? {
        if (key.startsWith(INTERNAL_PREFIX)) return defValue
        val stored = backing.getString(key, null) ?: return defValue
        return open(key, stored) ?: defValue
    }

    override fun getAll(): Map<String, *> = backing.all.keys
        .filterNot { it.startsWith(INTERNAL_PREFIX) }
        .mapNotNull { key -> getString(key, null)?.let { key to it } }
        .toMap()

    override fun contains(key: String): Boolean = !key.startsWith(INTERNAL_PREFIX) && backing.contains(key)

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue

    override fun edit(): SharedPreferences.Editor = Editor(backing.edit())

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        backing.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        backing.unregisterOnSharedPreferenceChangeListener(listener)

    /** The decrypted value under [key], also an internal one (the vault's probe); null when it does not decrypt. */
    fun peek(key: String): String? = backing.getString(key, null)?.let { open(key, it) }

    /** [value] encrypted for [key], as it is stored. */
    fun seal(key: String, value: String): String =
        PREFIX + ENCODER.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8), aad(key)))

    private fun open(key: String, stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            cipher.decrypt(DECODER.decode(stored.substring(PREFIX.length)), aad(key)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private inner class Editor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor {
        private var wrote = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            require(!key.startsWith(INTERNAL_PREFIX)) { "reserved key" }
            if (value == null) editor.remove(key) else editor.putString(key, seal(key, value))
            wrote = true
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }

        /** Removes every value, and leaves the vault's bookkeeping alone. */
        override fun clear(): SharedPreferences.Editor = apply {
            backing.all.keys.filterNot { it.startsWith(INTERNAL_PREFIX) }.forEach { editor.remove(it) }
        }

        override fun commit(): Boolean = finish().commit()
        override fun apply() = finish().apply()

        /** A secret written means it has been entered again, so the re-entry note goes. */
        private fun finish(): SharedPreferences.Editor = editor.also { if (wrote) it.remove(NEEDS_REENTRY) }

        override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
        override fun putInt(key: String, value: Int) = unsupported()
        override fun putLong(key: String, value: Long) = unsupported()
        override fun putFloat(key: String, value: Float) = unsupported()
        override fun putBoolean(key: String, value: Boolean) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("only strings are stored encrypted")
    }

    companion object {
        /** Bookkeeping keys in the same file: the migration marker, the probe, the re-entry note. */
        const val INTERNAL_PREFIX = "__"
        const val NEEDS_REENTRY = "__needs_reentry"
        private const val PREFIX = "v1:"
        private val ENCODER = Base64.getEncoder()
        private val DECODER = Base64.getDecoder()
        private fun aad(key: String) = "v1:$key".toByteArray(Charsets.UTF_8)
    }
}
