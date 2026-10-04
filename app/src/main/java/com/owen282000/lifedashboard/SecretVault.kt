package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/** What the secret store can do right now. */
enum class SecretState {
    /** Secrets read and save normally. */
    READY,

    /**
     * The Keystore could not be used this time, or the old store not read for migration.
     * Reads are empty and writes are dropped (InMemoryPrefs), never kept in plain storage;
     * the next process start tries again.
     */
    UNAVAILABLE,

    /**
     * Secrets were lost (the Keystore key is gone, or the old store stayed unreadable) and
     * have to be entered again. Writes work; the note goes as soon as one secret is saved.
     */
    NEEDS_REENTRY
}

/**
 * The one secret store of the process. Every PreferencesManager gets it from here, so the
 * migration runs once, under a lock, before any code can read or write a secret: a read that
 * found the new store empty before the migration, written back, would otherwise replace a real
 * password with an empty one.
 */
object SecretVault {

    /** The file the secrets live in since security-crypto was replaced. Excluded from backup. */
    const val FILE = "life_dashboard_secrets"

    /** The Keystore alias of their key, separate from security-crypto's master key. */
    const val KEY_ALIAS = "life_dashboard_secrets_v1"

    /** The security-crypto file of 1.6.0 to 1.22, read once to migrate; deleted in a later release. */
    const val LEGACY_FILE = "life_dashboard_secure_prefs"

    class Opened(val store: SharedPreferences, val state: SecretState, private val backing: SharedPreferences?) {
        val needsReentry: Boolean get() = backing?.getBoolean(EncryptedStore.NEEDS_REENTRY, false) == true
    }

    private val lock = Any()

    @Volatile
    private var opened: Opened? = null

    /** The store; opened, and migrated into, on first use. An unavailable result is not kept. */
    fun open(context: Context): Opened {
        opened?.let { return it }
        synchronized(lock) {
            opened?.let { return it }
            val app = context.applicationContext ?: context
            val target = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val result = SecretVaultLogic(
                plain = app.getSharedPreferences(PreferencesManager.PREFS_FILE, Context.MODE_PRIVATE),
                target = target,
                openCipher = { KeystoreCipher.open(KEY_ALIAS) },
                resetKey = { KeystoreCipher.delete(KEY_ALIAS) },
                legacyExists = { legacyFile(app).exists() },
                openLegacy = { openLegacy(app) },
                legacyPlainKeys = PreferencesManager.LEGACY_PLAIN_SECRET_KEYS
            ).open()
            if (result.state != SecretState.UNAVAILABLE) opened = result
            return result
        }
    }

    private fun legacyFile(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_FILE.xml")

    /** The old store, opened exactly as 1.6.0 to 1.22 opened it. */
    private fun openLegacy(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        LEGACY_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

/**
 * Opening the secret store and migrating into it, free of Android so every step and every
 * failure can be tested on the JVM. See [open] for the order.
 */
class SecretVaultLogic(
    /** The plain settings: secrets of versions before 1.6.0, and the failure counters. */
    private val plain: SharedPreferences,
    /** The new file the encrypted secrets go to. */
    private val target: SharedPreferences,
    private val openCipher: () -> KeystoreCipher.Opening,
    private val resetKey: () -> Unit,
    private val legacyExists: () -> Boolean,
    private val openLegacy: () -> SharedPreferences,
    private val legacyPlainKeys: List<String>,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * 1. Open the Keystore key. A failure is temporary ([SecretState.UNAVAILABLE]) until it has
     *    lasted [PERSISTENT_COUNT] tries over [PERSISTENT_MS]; then the key is replaced and the
     *    secrets have to be entered again.
     * 2. Migrated before: check the probe. A probe that does not decrypt means the key that
     *    wrote it is gone, so the values are wiped and have to be entered again.
     * 3. Not migrated: gather the secrets of the old encrypted store and any older plain ones,
     *    and write them, the probe and the marker in one commit, which replaces the file whole,
     *    so a process killed halfway leaves no half migration. Read everything back before
     *    trusting it, and only then remove the plain copies. The old encrypted file stays, for
     *    a rollback build; a later release deletes it.
     */
    fun open(): SecretVault.Opened {
        val opening = try {
            openCipher().also { clearFailures(KEYSTORE) }
        } catch (e: Exception) {
            if (!persistentFailure(KEYSTORE)) return unavailable()
            // The key has been unusable for a day of tries: start over with a new one.
            val fresh = try {
                resetKey()
                openCipher()
            } catch (again: Exception) {
                return unavailable()
            }
            clearFailures(KEYSTORE)
            return wipeForReentry(EncryptedStore(target, fresh.cipher))
        }
        val store = EncryptedStore(target, opening.cipher)
        return if (target.getBoolean(MARKER, false)) {
            if (probeIntact(store)) {
                ready(store)
            } else {
                wipeForReentry(store)
            }
        } else {
            migrate(store)
        }
    }

    /** Whether the probe decrypts, which only the key that wrote it can make it do. */
    private fun probeIntact(store: EncryptedStore): Boolean = store.peek(PROBE) == PROBE_VALUE

    private fun migrate(store: EncryptedStore): SecretVault.Opened {
        val values = linkedMapOf<String, String>()
        legacyPlainKeys.forEach { key -> plain.getString(key, null)?.let { values[key] = it } }
        var legacyLost = false
        if (legacyExists()) {
            try {
                openLegacy().all.forEach { (key, value) -> if (value is String) values[key] = value }
                clearFailures(LEGACY)
            } catch (e: Exception) {
                if (!persistentFailure(LEGACY)) return unavailable()
                legacyLost = true
            }
        }
        val editor = target.edit().clear()
        values.forEach { (key, value) -> editor.putString(key, store.seal(key, value)) }
        editor.putString(PROBE, store.seal(PROBE, PROBE_VALUE))
        editor.putBoolean(MARKER, true)
        if (legacyLost) editor.putBoolean(EncryptedStore.NEEDS_REENTRY, true)
        if (!editor.commit()) return unavailable()
        if (values.any { (key, value) -> store.getString(key, null) != value } || !probeIntact(store)) {
            target.edit().clear().commit()
            return unavailable()
        }
        if (legacyPlainKeys.any { plain.contains(it) }) {
            plain.edit().apply { legacyPlainKeys.forEach { remove(it) } }.commit()
        }
        return ready(store)
    }

    private fun wipeForReentry(store: EncryptedStore): SecretVault.Opened {
        val ok = target.edit().clear()
            .putString(PROBE, store.seal(PROBE, PROBE_VALUE))
            .putBoolean(MARKER, true)
            .putBoolean(EncryptedStore.NEEDS_REENTRY, true)
            .commit()
        return if (ok) ready(store) else unavailable()
    }

    private fun ready(store: EncryptedStore) = SecretVault.Opened(
        store,
        if (target.getBoolean(EncryptedStore.NEEDS_REENTRY, false)) SecretState.NEEDS_REENTRY else SecretState.READY,
        target
    )

    private fun unavailable() = SecretVault.Opened(InMemoryPrefs(), SecretState.UNAVAILABLE, null)

    /**
     * Counts a failure of [kind] and says whether it has become permanent: at least
     * [PERSISTENT_COUNT] tries spread over at least [PERSISTENT_MS], so a phone that is
     * restarted a few times in a row does not lose its secrets over a Keystore that was slow.
     */
    private fun persistentFailure(kind: String): Boolean {
        val first = plain.getLong("$FAIL_FIRST$kind", 0L).takeIf { it > 0 } ?: now()
        val count = plain.getInt("$FAIL_COUNT$kind", 0) + 1
        plain.edit().putLong("$FAIL_FIRST$kind", first).putInt("$FAIL_COUNT$kind", count).commit()
        return count >= PERSISTENT_COUNT && now() - first >= PERSISTENT_MS
    }

    private fun clearFailures(kind: String) {
        if (plain.contains("$FAIL_COUNT$kind")) {
            plain.edit().remove("$FAIL_COUNT$kind").remove("$FAIL_FIRST$kind").commit()
        }
    }

    companion object {
        const val MARKER = "__migrated_v1"
        const val PROBE = "__probe"
        const val PROBE_VALUE = "life-dashboard"
        const val PERSISTENT_COUNT = 5
        const val PERSISTENT_MS = 24L * 60 * 60 * 1000
        private const val FAIL_COUNT = "secrets_fail_count_"
        private const val FAIL_FIRST = "secrets_fail_first_"
        private const val KEYSTORE = "keystore"
        private const val LEGACY = "legacy"
    }
}
