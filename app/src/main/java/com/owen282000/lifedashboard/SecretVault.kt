package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the secret store can do right now. */
enum class SecretState {
    /** Secrets read and save normally. */
    READY,

    /**
     * The Keystore could not be used this time.
     * Reads are empty and writes are dropped (InMemoryPrefs), never kept in plain storage;
     * a later start tries again.
     */
    UNAVAILABLE,

    /**
     * Secrets were lost (the Keystore key is gone for good, or they were still in the old
     * store of 1.22 and older, which can no longer be read) and have to be entered again.
     * Writes work; the note goes as soon as one secret is entered.
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

    /**
     * The security-crypto file of 1.6.0 to 1.22. 1.23.0 migrated it; this version can no longer
     * read it, so one still here is deleted, and the secrets it held are asked for again.
     */
    const val LEGACY_FILE = "life_dashboard_secure_prefs"

    /** The Keystore alias of security-crypto's master key, which served only [LEGACY_FILE]. */
    const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /** How long an unavailable store is answered from memory before the Keystore is tried again. */
    private const val RETRY_AFTER_MS = 30_000L

    class Opened(val store: SharedPreferences, val state: SecretState, private val backing: SharedPreferences?) {
        val needsReentry: Boolean get() = backing?.getBoolean(EncryptedStore.NEEDS_REENTRY, false) == true

        /** The user dismissed the request to enter the secrets again. */
        fun dismissReentry() {
            backing?.edit()?.remove(EncryptedStore.NEEDS_REENTRY)?.commit()
        }
    }

    private val lock = Any()

    @Volatile
    private var opened: Opened? = null

    @Volatile
    private var unavailableAt = 0L
    private var unavailable: Opened? = null

    /** Each failure is counted once per process; see [SecretVaultLogic.countFailure]. */
    private val countedThisProcess = mutableSetOf<String>()

    /** A write of the store failed in this process; see [SecretVaultLogic.open]. */
    private val writeFailed = AtomicBoolean(false)

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
                // A write of 1.22 cut short leaves only the backup copy, which Android restores on open.
                legacyExists = { legacyFile(app).exists() || File(legacyFile(app).path + ".bak").exists() },
                legacyHasValues = { holdsSecrets(app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)) },
                deleteLegacy = { deleteLegacy(app) },
                legacyPlainKeys = PreferencesManager.LEGACY_PLAIN_SECRET_KEYS,
                bootCount = { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 } },
                countedThisProcess = countedThisProcess,
                writeFailed = writeFailed
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

    /** Where security-crypto keeps its own keys in its file; every other entry is a secret. */
    private val LEGACY_KEYSETS = setOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    )

    /** Whether a security-crypto file, read as it is on disk, holds any secret. */
    internal fun holdsSecrets(legacy: SharedPreferences) = legacy.all.keys.any { it !in LEGACY_KEYSETS }

    /**
     * Deletes the old file and its master key, which served nothing else. The key goes by its
     * literal alias, through the Keystore itself; a Keystore that is busy only leaves a key behind.
     */
    internal fun deleteLegacy(context: Context, name: String = LEGACY_FILE, alias: String = LEGACY_MASTER_KEY_ALIAS) {
        context.deleteSharedPreferences(name)
        runCatching { KeystoreCipher.delete(alias) }
    }
}

/**
 * Opening the secret store and migrating into it, free of Android so every step and every
 * failure can be tested on the JVM. See [open] for the order. Nothing here ever wipes a secret
 * over a failure that may pass: only a key that is provably wrong (a tag that does not match,
 * checked twice) or an error about the key itself seen on [PERSISTENT_BOOTS] separate boots
 * does that.
 */
class SecretVaultLogic(
    /** The plain settings, where versions before 1.6.0 kept four secrets. */
    private val plain: SharedPreferences,
    /** The new file: encrypted secrets, and the bookkeeping, which is excluded from backup with it. */
    private val target: SharedPreferences,
    private val openCipher: (allowCreate: Boolean) -> SecretCipher,
    private val resetKey: () -> Unit,
    private val legacyExists: () -> Boolean,
    /** Whether the old file holds any entry besides security-crypto's own keys, read without decrypting. */
    private val legacyHasValues: () -> Boolean,
    /** Deletes the old file and its master key. */
    private val deleteLegacy: () -> Unit,
    private val legacyPlainKeys: List<String>,
    /** Android's boot counter; null when the phone does not report it. */
    private val bootCount: () -> Int?,
    /** The failure kinds already counted in this process. */
    private val countedThisProcess: MutableSet<String> = mutableSetOf(),
    /** Set when a write of [target] failed in this process. */
    private val writeFailed: AtomicBoolean = AtomicBoolean(false),
    private val isDefinitive: (Throwable) -> Boolean = { KeystoreCipher.isDefinitive(it) }
) {

    /**
     * 1. Open the Keystore key. Before the migration a missing key is made; after it, a missing
     *    key is a failure, never a reason to make a new one. A failure is [SecretState.UNAVAILABLE];
     *    only an error about the key itself seen on [PERSISTENT_BOOTS] boots replaces the key.
     * 2. Migrated before: check the probe. A tag that does not match, again with a fresh handle,
     *    while no stored value opens either, means the key that wrote them is gone, so the values
     *    are wiped and have to be entered again; any other failure is only unavailable. Then
     *    clean up what is left over: an old file, and plain secrets a restore brought back.
     * 3. Not migrated: gather the plain secrets of before 1.6.0, and write them, the probe and
     *    the marker in one commit, which replaces the file whole, so a process killed halfway
     *    leaves no half migration. An old encrypted file with secrets in it can no longer be
     *    read, so the same commit asks for them again. Read everything back, and only then
     *    remove the plain copies and delete the old file.
     *
     * Anything unexpected is unavailable, never a crash and never a wipe. So is everything after
     * a write that failed in this process: SharedPreferences changes its memory before the disk,
     * so the memory may hold a marker the disk does not, and acting on it (deleting the old file)
     * could lose what the disk never got. The next process reads the disk again.
     */
    fun open(): SecretVault.Opened = try {
        if (writeFailed.get()) unavailable() else openChecked()
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
            is EncryptedStore.Opened.Value -> proven(store)
            EncryptedStore.Opened.WrongKey, null -> mismatch()
            is EncryptedStore.Opened.Failed -> failed(probe.error, migrated)
        }
    }

    /** The key opened the probe: failures end here, at a key that proved itself, not one that merely opened. */
    private fun proven(store: EncryptedStore): SecretVault.Opened {
        clearFailures(KEYSTORE)
        absorbLeftovers()
        return ready(store)
    }

    /**
     * The probe did not open with this key. A tag that does not match is the secure hardware's
     * own verdict, but a wipe costs the user every secret, so it is checked once more first:
     * with a fresh handle on the key, against every stored value, and with a round trip that
     * shows the key itself works. Only when all of that agrees are the values wiped.
     */
    private fun mismatch(): SecretVault.Opened {
        val fresh = EncryptedStore(target, openCipher(false))
        if (fresh.peek(PROBE) is EncryptedStore.Opened.Value) return proven(fresh)
        val values = target.all.keys.filterNot { it.startsWith(EncryptedStore.INTERNAL_PREFIX) }.map { fresh.peek(it) }
        if (values.any { it is EncryptedStore.Opened.Failed }) return unavailable()
        if (values.any { it is EncryptedStore.Opened.Value }) {
            // The key is right and only the probe is damaged: write it again.
            if (!commit(target.edit().putString(PROBE, fresh.seal(PROBE, PROBE_VALUE)))) return unavailable()
            return proven(fresh)
        }
        if (fresh.open(PROBE, fresh.seal(PROBE, PROBE_VALUE)) !is EncryptedStore.Opened.Value) return unavailable()
        return wipeForReentry(fresh)
    }

    /**
     * A Keystore failure: unavailable, unless it is about the key itself and has been seen on
     * [PERSISTENT_BOOTS] boots without the key proving itself in between; then the key is
     * replaced, the store migrated into if it never was, and otherwise the unreadable values
     * wiped for the user to enter again.
     */
    private fun failed(e: Exception, migrated: Boolean): SecretVault.Opened {
        if (!isDefinitive(e) || !countFailure(KEYSTORE)) return unavailable()
        resetKey()
        val store = EncryptedStore(target, openCipher(true))
        return if (migrated) wipeForReentry(store, afterReset = true) else migrate(store, afterReset = true)
    }

    /**
     * Encrypts [values] for the store, or hands a key that cannot encrypt to [failed], so a key
     * that is there but broken is counted and replaced like one that cannot decrypt, instead of
     * leaving the store unavailable for good. Null when the caller should return [fallback].
     */
    private inline fun sealAll(
        store: EncryptedStore,
        values: Map<String, String>,
        afterReset: Boolean,
        migrated: Boolean,
        fallback: (SecretVault.Opened) -> Nothing
    ): Map<String, String> = try {
        values.mapValues { (key, value) -> store.seal(key, value) }
    } catch (e: Exception) {
        fallback(if (afterReset) unavailable() else failed(e, migrated))
    }

    private fun migrate(store: EncryptedStore, afterReset: Boolean = false): SecretVault.Opened {
        val values = linkedMapOf<String, String>()
        legacyPlainKeys.forEach { key -> plain.getString(key, null)?.let { values[key] = it } }
        // The old file of 1.22 and older can no longer be read, and never will be again: secrets
        // in it are lost. One with only security-crypto's own keys held none, so loses nothing.
        val lost = legacyExists() && legacyHasValues()
        val sealed = sealAll(store, values + (PROBE to PROBE_VALUE), afterReset, migrated = false) { return it }
        val editor = target.edit().clear()
        sealed.forEach { (key, value) -> editor.putString(key, value) }
        editor.putBoolean(MARKER, true)
        if (lost) editor.putBoolean(EncryptedStore.NEEDS_REENTRY, true)
        if (!commit(editor)) return unavailable()
        val intact = store.peek(PROBE) is EncryptedStore.Opened.Value &&
            values.all { (key, value) -> (store.peek(key) as? EncryptedStore.Opened.Value)?.value == value }
        if (!intact) {
            commit(target.edit().clear())
            return unavailable()
        }
        removePlainCopies()
        if (legacyExists()) deleteLegacy()
        return ready(store)
    }

    /**
     * After the migration, on every start that opens the store:
     * - plain secrets that a restore of an old backup brought back are removed, not taken in:
     *   the store already holds what the user has set since, and a plain copy must not stay;
     * - an old file that is still there is deleted. Either the process stopped between the
     *   migration and its deletion, or 1.23.0 could not read all of it and kept it to try
     *   again ([LEGACY_PENDING]). This version cannot read it at all, so that wait ends: the
     *   flag goes, and the request to enter the secrets again stays as it is.
     */
    private fun absorbLeftovers() {
        removePlainCopies()
        if (target.contains(LEGACY_PENDING) && !commit(target.edit().remove(LEGACY_PENDING))) return
        if (legacyExists()) deleteLegacy()
    }

    private fun removePlainCopies() {
        if (legacyPlainKeys.any { plain.contains(it) }) {
            plain.edit().apply { legacyPlainKeys.forEach { remove(it) } }.commit()
        }
    }

    /**
     * Wipes the values no key can open any more, and writes a probe for [store]'s key. The user
     * is asked to enter the secrets again only when something was lost: a store that held no
     * secret loses nothing, and a banner about lost secrets would then never go away.
     */
    private fun wipeForReentry(store: EncryptedStore, afterReset: Boolean = false): SecretVault.Opened {
        val lost = target.getBoolean(EncryptedStore.NEEDS_REENTRY, false) ||
            target.all.keys.any { !it.startsWith(EncryptedStore.INTERNAL_PREFIX) }
        val probe = sealAll(store, mapOf(PROBE to PROBE_VALUE), afterReset, migrated = true) { return it }.getValue(PROBE)
        val ok = commit(
            target.edit().clear()
                .putString(PROBE, probe)
                .putBoolean(MARKER, true)
                .apply { if (lost) putBoolean(EncryptedStore.NEEDS_REENTRY, true) }
        )
        if (!ok || store.peek(PROBE) !is EncryptedStore.Opened.Value) return unavailable()
        return ready(store)
    }

    /** Commits [editor], and remembers a failure for the rest of the process; see [open]. */
    private fun commit(editor: SharedPreferences.Editor): Boolean = editor.commit().also { if (!it) writeFailed.set(true) }

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
        const val LEGACY_PENDING = EncryptedStore.LEGACY_PENDING
        const val PERSISTENT_BOOTS = 3
        const val PERSISTENT_PROCESSES = 10
        private const val FAIL_COUNT = "__fail_count_"
        private const val FAIL_BOOT = "__fail_boot_"
        private const val KEYSTORE = "keystore"
    }
}
