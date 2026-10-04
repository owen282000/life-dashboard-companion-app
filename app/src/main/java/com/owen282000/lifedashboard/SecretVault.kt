package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
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
     * a later start tries again.
     */
    UNAVAILABLE,

    /**
     * Secrets were lost (the Keystore key is gone for good, or the old store stayed
     * unreadable) and have to be entered again. Writes work; the note goes as soon as one
     * secret is entered.
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

    /** The security-crypto file of 1.6.0 to 1.22, read once to migrate and then deleted. */
    const val LEGACY_FILE = "life_dashboard_secure_prefs"

    /** How long an unavailable store is answered from memory before the Keystore is tried again. */
    private const val RETRY_AFTER_MS = 30_000L

    class Opened(val store: SharedPreferences, val state: SecretState, private val backing: SharedPreferences?) {
        val needsReentry: Boolean get() = backing?.getBoolean(EncryptedStore.NEEDS_REENTRY, false) == true
    }

    private val lock = Any()

    @Volatile
    private var opened: Opened? = null

    @Volatile
    private var unavailableAt = 0L
    private var unavailable: Opened? = null

    /** Each failure is counted once per process; see [SecretVaultLogic.countFailure]. */
    private val countedThisProcess = mutableSetOf<String>()

    /**
     * The store; opened, and migrated into, on first use. An unavailable result is not kept for
     * good: after [RETRY_AFTER_MS] the next caller tries again, so the many places that build a
     * PreferencesManager do not each go to the Keystore while it is down.
     */
    fun open(context: Context): Opened {
        opened?.let { return it }
        synchronized(lock) {
            opened?.let { return it }
            unavailable?.let { if (SystemClock.elapsedRealtime() - unavailableAt < RETRY_AFTER_MS) return it }
            val app = context.applicationContext ?: context
            val result = SecretVaultLogic(
                plain = app.getSharedPreferences(PreferencesManager.PREFS_FILE, Context.MODE_PRIVATE),
                target = app.getSharedPreferences(FILE, Context.MODE_PRIVATE),
                openCipher = { allowCreate -> KeystoreCipher.open(KEY_ALIAS, allowCreate) },
                resetKey = { KeystoreCipher.delete(KEY_ALIAS) },
                legacyExists = { legacyFile(app).exists() },
                openLegacy = { openLegacy(app) },
                deleteLegacy = {
                    app.deleteSharedPreferences(LEGACY_FILE)
                    // Its master key served nothing else.
                    runCatching { KeystoreCipher.delete(MasterKey.DEFAULT_MASTER_KEY_ALIAS) }
                },
                legacyPlainKeys = PreferencesManager.LEGACY_PLAIN_SECRET_KEYS,
                knownKeys = PreferencesManager.SECRET_KEYS,
                bootCount = { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 } },
                countedThisProcess = countedThisProcess
            ).open()
            if (result.state == SecretState.UNAVAILABLE) {
                unavailable = result
                unavailableAt = SystemClock.elapsedRealtime()
            } else {
                opened = result
                unavailable = null
            }
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
 * failure can be tested on the JVM. See [open] for the order. Nothing here ever wipes a secret
 * over a failure that may pass: only a key that is provably wrong (a tag that does not match)
 * or a definitive Keystore error seen on [PERSISTENT_BOOTS] separate boots does that.
 */
class SecretVaultLogic(
    /** The plain settings, where versions before 1.6.0 kept four secrets. */
    private val plain: SharedPreferences,
    /** The new file: encrypted secrets, and the bookkeeping, which is excluded from backup with it. */
    private val target: SharedPreferences,
    private val openCipher: (allowCreate: Boolean) -> SecretCipher,
    private val resetKey: () -> Unit,
    private val legacyExists: () -> Boolean,
    private val openLegacy: () -> SharedPreferences,
    private val deleteLegacy: () -> Unit,
    private val legacyPlainKeys: List<String>,
    /** Every secret's key, for reading an old store whose all() fails as a whole. */
    private val knownKeys: List<String>,
    /** Android's boot counter; null when the phone does not report it. */
    private val bootCount: () -> Int?,
    /** The failure kinds already counted in this process. */
    private val countedThisProcess: MutableSet<String> = mutableSetOf()
) {

    /**
     * 1. Open the Keystore key. Before the migration a missing key is made; after it, a missing
     *    key is a failure, never a reason to make a new one. A failure is [SecretState.UNAVAILABLE];
     *    only a definitive one seen on [PERSISTENT_BOOTS] boots replaces the key.
     * 2. Migrated before: check the probe. A tag that does not match means the key that wrote
     *    it is gone, so the values are wiped and have to be entered again; any other failure
     *    is only unavailable. Then pick up what an old store still holds, if it was unreadable
     *    at the migration, and plain secrets a restore brought back.
     * 3. Not migrated: gather the secrets of the old encrypted store and any older plain ones,
     *    and write them, the probe and the marker in one commit, which replaces the file whole,
     *    so a process killed halfway leaves no half migration. Read everything back, and only
     *    then remove the plain copies and delete the old file, so a secret rotated later can
     *    never come back from it.
     *
     * Anything unexpected is unavailable, never a crash and never a wipe.
     */
    fun open(): SecretVault.Opened = try {
        openChecked()
    } catch (e: Exception) {
        unavailable()
    }

    private fun openChecked(): SecretVault.Opened {
        val migrated = target.getBoolean(MARKER, false)
        val cipher = try {
            openCipher(!migrated)
        } catch (e: Exception) {
            return failed(e, migrated)
        }
        val store = EncryptedStore(target, cipher)
        if (!migrated) return migrate(store)
        return when (val probe = store.peek(PROBE)) {
            is EncryptedStore.Opened.Value -> {
                // Failures only end at a key that proved itself, not at one that merely opened.
                clearFailures(KEYSTORE)
                absorbLeftovers(store)
                ready(store)
            }
            EncryptedStore.Opened.WrongKey, null -> wipeForReentry(store)
            is EncryptedStore.Opened.Failed -> failed(probe.error, migrated)
        }
    }

    /**
     * A Keystore failure: unavailable, unless it is definitive and has been seen on
     * [PERSISTENT_BOOTS] boots without the key proving itself in between; then the key is
     * replaced, the store migrated into if it never was, and otherwise the unreadable values
     * wiped for the user to enter again.
     */
    private fun failed(e: Exception, migrated: Boolean): SecretVault.Opened {
        if (!KeystoreCipher.isDefinitive(e) || !countFailure(KEYSTORE)) return unavailable()
        resetKey()
        val store = EncryptedStore(target, openCipher(true))
        return if (migrated) wipeForReentry(store) else migrate(store)
    }

    private fun migrate(store: EncryptedStore): SecretVault.Opened {
        val values = linkedMapOf<String, String>()
        legacyPlainKeys.forEach { key -> plain.getString(key, null)?.let { values[key] = it } }
        // Complete when the old store gave every value it holds; incomplete keeps it, and asks.
        var complete = true
        if (legacyExists()) {
            val legacy = readLegacy()
            if (legacy != null) {
                values.putAll(legacy.values)
                complete = legacy.complete
                clearFailures(LEGACY)
            } else {
                if (!countFailure(LEGACY)) return unavailable()
                complete = false
            }
        }
        val editor = target.edit().clear()
        values.forEach { (key, value) -> editor.putString(key, store.seal(key, value)) }
        editor.putString(PROBE, store.seal(PROBE, PROBE_VALUE))
        editor.putBoolean(MARKER, true)
        if (!complete) {
            editor.putBoolean(EncryptedStore.NEEDS_REENTRY, true)
            editor.putBoolean(LEGACY_PENDING, true)
        }
        if (!editor.commit()) return unavailable()
        val intact = store.peek(PROBE) is EncryptedStore.Opened.Value &&
            values.all { (key, value) -> (store.peek(key) as? EncryptedStore.Opened.Value)?.value == value }
        if (!intact) {
            target.edit().clear().commit()
            return unavailable()
        }
        removePlainCopies()
        if (complete && legacyExists()) deleteLegacy()
        return ready(store)
    }

    /**
     * After the migration, on every start that opens the store:
     * - plain secrets that a restore of an old backup brought back are removed, not taken in:
     *   the store already holds what the user has set since, and a plain copy must not stay;
     * - an old store that could not be read at the migration is tried again, but only while
     *   the user is still asked to enter the secrets again: it fills the keys with nothing
     *   stored, and when it gave everything, the request goes. Once the user has entered a
     *   secret, a key left empty may be empty on purpose, so the old store is dropped then;
     * - an old store that is still there otherwise (the process stopped between the migration
     *   and its deletion) is deleted.
     */
    private fun absorbLeftovers(store: EncryptedStore) {
        removePlainCopies()
        if (!legacyExists()) {
            if (target.contains(LEGACY_PENDING)) target.edit().remove(LEGACY_PENDING).commit()
            return
        }
        val waiting = target.getBoolean(LEGACY_PENDING, false) && target.getBoolean(EncryptedStore.NEEDS_REENTRY, false)
        if (!waiting) {
            if (target.contains(LEGACY_PENDING)) target.edit().remove(LEGACY_PENDING).commit()
            deleteLegacy()
            return
        }
        val legacy = readLegacy() ?: return
        val editor = target.edit()
        legacy.values.filterKeys { !target.contains(it) }.forEach { (key, value) -> editor.putString(key, store.seal(key, value)) }
        if (legacy.complete) editor.remove(EncryptedStore.NEEDS_REENTRY).remove(LEGACY_PENDING)
        if (!editor.commit()) return
        if (legacy.complete) deleteLegacy()
    }

    /** What an old store gave, and whether that was all it holds. */
    private class LegacyRead(val values: Map<String, String>, val complete: Boolean)

    /**
     * The old store's secrets, or null when it cannot be read. When reading it whole fails,
     * every known key is tried on its own; what fails then is missing, so the read is not
     * complete, and when nothing at all could be read it counts as unreadable, not as empty.
     */
    private fun readLegacy(): LegacyRead? = try {
        val old = openLegacy()
        try {
            LegacyRead(old.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap(), complete = true)
        } catch (e: Exception) {
            var failures = 0
            val values = knownKeys.mapNotNull { key ->
                try {
                    old.getString(key, null)?.let { key to it }
                } catch (e: Exception) {
                    failures++
                    null
                }
            }.toMap()
            if (values.isEmpty() && failures > 0) null else LegacyRead(values, complete = failures == 0)
        }
    } catch (e: Exception) {
        null
    }

    private fun removePlainCopies() {
        if (legacyPlainKeys.any { plain.contains(it) }) {
            plain.edit().apply { legacyPlainKeys.forEach { remove(it) } }.commit()
        }
    }

    private fun wipeForReentry(store: EncryptedStore): SecretVault.Opened {
        // An old store still waiting to be read can still fill in what is wiped here.
        val pending = target.getBoolean(LEGACY_PENDING, false)
        val ok = target.edit().clear()
            .putString(PROBE, store.seal(PROBE, PROBE_VALUE))
            .putBoolean(MARKER, true)
            .putBoolean(EncryptedStore.NEEDS_REENTRY, true)
            .apply { if (pending) putBoolean(LEGACY_PENDING, true) }
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
     * Counts a failure of [kind] and says whether it has become permanent: seen on
     * [PERSISTENT_BOOTS] separate boots, counted once per boot, so a phone that is off for a
     * weekend or a burst of failures in one process does not count as a lasting failure, and a
     * clock that jumps after a dead battery plays no part. A phone that reports no boot counter
     * counts once per process instead, and needs [PERSISTENT_PROCESSES] of them.
     */
    internal fun countFailure(kind: String): Boolean {
        val boot = bootCount()
        val countKey = "$FAIL_COUNT$kind"
        if (boot == null) {
            if (!countedThisProcess.add(kind)) return target.getInt(countKey, 0) >= PERSISTENT_PROCESSES
            val count = target.getInt(countKey, 0) + 1
            target.edit().putInt(countKey, count).commit()
            return count >= PERSISTENT_PROCESSES
        }
        val bootKey = "$FAIL_BOOT$kind"
        val count = if (target.getInt(bootKey, -1) == boot) target.getInt(countKey, 0) else target.getInt(countKey, 0) + 1
        target.edit().putInt(bootKey, boot).putInt(countKey, count).commit()
        return count >= PERSISTENT_BOOTS
    }

    private fun clearFailures(kind: String) {
        if (target.contains("$FAIL_COUNT$kind")) {
            target.edit().remove("$FAIL_COUNT$kind").remove("$FAIL_BOOT$kind").commit()
        }
    }

    companion object {
        const val MARKER = "__migrated_v1"
        const val PROBE = "__probe"
        const val PROBE_VALUE = "life-dashboard"
        const val LEGACY_PENDING = "__legacy_pending"
        const val PERSISTENT_BOOTS = 3
        const val PERSISTENT_PROCESSES = 10
        private const val FAIL_COUNT = "__fail_count_"
        private const val FAIL_BOOT = "__fail_boot_"
        private const val KEYSTORE = "keystore"
        private const val LEGACY = "legacy"
    }
}
