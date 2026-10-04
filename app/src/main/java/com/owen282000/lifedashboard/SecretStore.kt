package com.owen282000.lifedashboard

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts one value. [encrypt] returns the IV followed by the ciphertext and its
 * tag; [decrypt] throws [AEADBadTagException] when the blob was not made with this key and
 * [aad], or was altered, and anything else when the key could not be used right now.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/** The key under the alias is not there, while the store says one was made before. */
class KeyMissingException(alias: String) : Exception("no key under $alias")

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
        // Keymaster does not always hand the IV back; a blob without it could never be read again.
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "no usable IV" }
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw AEADBadTagException("too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /**
         * Opens the key under [alias]. Only when [allowCreate] is a missing key generated: once
         * the store holds values, a key that does not show up is a failure, never a reason to
         * make a new one over the old. On Android 8 to 11 the Keystore answers "no such key" for
         * one that exists when its daemon cannot be reached, and generating then would destroy
         * the real key. Throws when the Keystore cannot be used.
         */
        fun open(alias: String, allowCreate: Boolean): SecretCipher {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return KeystoreCipher(it) }
            if (!allowCreate) throw KeyMissingException(alias)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return KeystoreCipher(generator.generateKey())
        }

        /** Removes the key under [alias]; everything encrypted with it becomes unreadable. */
        fun delete(alias: String) {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        }

        /**
         * Whether [e] says the key itself is gone or unusable for good, as opposed to a Keystore
         * that is busy, unreachable or broken as a whole. Only these can ever lead to a new key.
         *
         * Android reports nearly every Keystore failure as an [InvalidKeyException] or an
         * [UnrecoverableKeyException], a system error or a secure hardware that stopped
         * answering included, with the Keystore's own error as the cause. A key that is missing
         * or permanently invalidated comes without that cause. So the cause decides: only an
         * error about the key itself (not found, corrupted, an invalid blob) counts, and anything
         * else, an unknown error too, does not, because a new key does not help a Keystore that
         * fails as a whole, and destroys the old key a later fix would have brought back.
         */
        fun isDefinitive(
            e: Throwable,
            isKeystoreError: (Throwable) -> Boolean = { it.javaClass.name == KEYSTORE_ERROR }
        ): Boolean {
            if (e is KeyMissingException) return true
            // Locked or not yet initialised; our key is not bound to either.
            if (e is UserNotAuthenticatedException) return false
            if (e !is InvalidKeyException && e !is UnrecoverableKeyException) return false
            val cause = generateSequence(e.cause) { it.cause }.firstOrNull(isKeystoreError) ?: return true
            // The message, not the error code: Android 13 and later file an invalid key blob under
            // a general KeyMint failure, and before 13 the error class is hidden. Every version
            // starts the message with the same words (13 and later add the code after them).
            val message = cause.message.orEmpty()
            return KEY_ERRORS.any { message.startsWith(it) }
        }

        private const val KEYSTORE_ERROR = "android.security.KeyStoreException"

        /** The Keystore's messages for errors about one key. */
        private val KEY_ERRORS = listOf("Key not found", "Key blob corrupted", "Invalid key blob", "Key permanently invalidated")
    }
}

/**
 * A [SharedPreferences] that keeps its string values encrypted with [cipher] in [backing], so
 * every place that used the old encrypted preferences keeps calling getString and putString.
 *
 * A value is stored as `v1:` and the base64 of IV, ciphertext and tag, with `v1:` plus the key
 * name as associated data, so a value copied under another key does not decrypt. Key names stay
 * readable: they name settings (`health_webhook_secret`), not secrets. Keys starting with
 * [INTERNAL_PREFIX] are [SecretVault]'s bookkeeping, never values, and are invisible here.
 *
 * Decrypted values are kept in memory for the life of the process, as the old store kept its
 * keys: one Keystore call per value, not per read. A value whose tag does not match (another
 * key wrote it, or it was altered) reads as absent. A value the Keystore could not decrypt
 * right now also reads as absent, but an empty write does not remove it: the settings screens
 * save every field at once, and an empty field shown during a Keystore hiccup would otherwise
 * replace a real password. A real new value is always written.
 *
 * Only strings are stored; nothing secret is anything else. The other typed puts throw, so a
 * secret can never land unencrypted by accident. Writing a value equal to the stored one does
 * nothing, so saving unrelated settings costs no Keystore call and enters no secret again.
 */
class EncryptedStore(private val backing: SharedPreferences, private val cipher: SecretCipher) : SharedPreferences {

    /** How a stored value came out. */
    sealed interface Opened {
        data class Value(val value: String) : Opened

        /** The tag did not match: another key wrote it, or it was altered. */
        data object WrongKey : Opened

        /** The Keystore could not decrypt it right now. */
        data class Failed(val error: Exception) : Opened
    }

    private val cache = ConcurrentHashMap<String, String>()
    private val unreadable = ConcurrentHashMap.newKeySet<String>()

    override fun getString(key: String, defValue: String?): String? {
        if (key.startsWith(INTERNAL_PREFIX)) return defValue
        cache[key]?.let { return it }
        val stored = backing.getString(key, null) ?: return defValue
        return when (val opened = open(key, stored)) {
            is Opened.Value -> opened.value.also { cache[key] = it; unreadable.remove(key) }
            Opened.WrongKey -> defValue
            is Opened.Failed -> defValue.also { unreadable += key }
        }
    }

    /** How the value under [key] comes out, internal keys included (the vault's probe). */
    fun peek(key: String): Opened? = backing.getString(key, null)?.let { open(key, it) }

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

    /** [value] encrypted for [key], as it is stored. Throws when the Keystore cannot be used. */
    fun seal(key: String, value: String): String =
        PREFIX + ENCODER.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8), aad(key)))

    /** How [stored], as it would be stored under [key], comes out. */
    fun open(key: String, stored: String): Opened {
        if (!stored.startsWith(PREFIX)) return Opened.WrongKey
        val blob = try {
            DECODER.decode(stored.substring(PREFIX.length))
        } catch (e: IllegalArgumentException) {
            return Opened.WrongKey
        }
        return try {
            Opened.Value(cipher.decrypt(blob, aad(key)).toString(Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            Opened.WrongKey
        } catch (e: Exception) {
            Opened.Failed(e)
        }
    }

    /**
     * Collects the changes and encrypts them at commit. A value that could not be read stays
     * as it is; a value equal to the stored one is not written again.
     */
    private inner class Editor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, String?>()
        private var clearing = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            require(!key.startsWith(INTERNAL_PREFIX)) { "reserved key" }
            puts[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { puts[key] = null }

        /** Removes every value, and leaves the vault's bookkeeping alone. */
        override fun clear(): SharedPreferences.Editor = apply {
            clearing = true
            puts.clear()
        }

        override fun commit(): Boolean = prepare()?.commit() ?: false
        override fun apply() {
            prepare()?.apply()
        }

        /** The backing editor with every change sealed, or null when sealing failed (nothing is written then). */
        private fun prepare(): SharedPreferences.Editor? {
            var entered = false
            var removed = false
            val sealed = linkedMapOf<String, String?>()
            if (clearing) {
                backing.all.keys.filterNot { it.startsWith(INTERNAL_PREFIX) }.forEach { sealed[it] = null }
            }
            for ((key, value) in puts) {
                if (value.isNullOrBlank()) {
                    // Nothing to keep: but a value that could not be read is not taken for empty.
                    if (key !in unreadable && backing.contains(key)) {
                        // Versions before this one stored empty usernames and "{}" for no headers;
                        // saving those as empty again removes nothing the user had.
                        if (!isEmptyValue(getString(key, null))) removed = true
                        sealed[key] = null
                    }
                    continue
                }
                if (key !in unreadable && value == getString(key, null)) continue
                sealed[key] = try {
                    seal(key, value)
                } catch (e: Exception) {
                    return null
                }
                entered = true
            }
            sealed.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            // A real secret written means they are being entered again, so the note goes.
            if (entered) editor.remove(NEEDS_REENTRY)
            // Whatever the user set or removed is theirs now: an old store still waiting to be read
            // must not put back a secret they removed on purpose.
            if (entered || removed || clearing) editor.remove(LEGACY_PENDING)
            sealed.forEach { (key, value) ->
                if (value == null) {
                    cache.remove(key)
                } else {
                    puts[key]?.let { cache[key] = it }
                    unreadable.remove(key)
                }
            }
            return editor
        }

        override fun putStringSet(key: String, values: MutableSet<String>?) = unsupported()
        override fun putInt(key: String, value: Int) = unsupported()
        override fun putLong(key: String, value: Long) = unsupported()
        override fun putFloat(key: String, value: Float) = unsupported()
        override fun putBoolean(key: String, value: Boolean) = unsupported()
        private fun unsupported(): Nothing = throw UnsupportedOperationException("only strings are stored encrypted")
    }

    companion object {
        /** Bookkeeping keys in the same file: the migration marker, the probe, the re-entry note, counters. */
        const val INTERNAL_PREFIX = "__"
        const val NEEDS_REENTRY = "__needs_reentry"

        /** An old store that could not be read at the migration and may still fill in; see SecretVaultLogic. */
        const val LEGACY_PENDING = "__legacy_pending"
        private const val PREFIX = "v1:"
        private val ENCODER = Base64.getEncoder()
        private val DECODER = Base64.getDecoder()
        private fun aad(key: String) = "v1:$key".toByteArray(Charsets.UTF_8)

        /** No value, as far as the user is concerned: nothing, blank, or an empty header map. */
        private fun isEmptyValue(value: String?) = value.isNullOrBlank() || value.trim() == "{}"
    }
}
