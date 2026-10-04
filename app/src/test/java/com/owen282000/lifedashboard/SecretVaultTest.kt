package com.owen282000.lifedashboard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The secret store that replaces security-crypto, and the migration into it, with a software
 * AES-GCM key standing in for the Keystore. What matters most: no secret is lost on the way,
 * none is ever kept in plain text, and a failure that may pass never wipes anything or makes a
 * new key, however often it happens.
 */
class SecretVaultTest {

    /** AES-256-GCM as KeystoreCipher does it, with a key in memory, and a switch for a busy Keystore. */
    private class SoftwareCipher(private val key: SecretKey = newKey()) : SecretCipher {
        var busy = false
        var encryptFails = false

        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            if (busy || encryptFails) throw ProviderException("keystore busy")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            if (busy) throw ProviderException("keystore busy")
            if (blob.size <= 12) throw javax.crypto.AEADBadTagException("too short")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, 12, blob.size - 12)
        }

        companion object {
            fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    /** A store whose commits can be made to fail, the way a full disk would. */
    private class FlakyPrefs(private val inner: InMemoryPrefs = InMemoryPrefs()) : SharedPreferences by inner {
        var failCommits = false
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by inner.edit() {
            private val pending = mutableListOf<(SharedPreferences.Editor) -> Unit>()
            override fun putString(key: String, value: String?) = apply { pending += { it.putString(key, value) } }
            override fun putBoolean(key: String, value: Boolean) = apply { pending += { it.putBoolean(key, value) } }
            override fun putInt(key: String, value: Int) = apply { pending += { it.putInt(key, value) } }
            override fun putLong(key: String, value: Long) = apply { pending += { it.putLong(key, value) } }
            override fun remove(key: String) = apply { pending += { it.remove(key) } }
            override fun clear() = apply { pending += { it.clear() } }
            override fun commit(): Boolean {
                if (failCommits) return false
                val editor = inner.edit()
                pending.forEach { it(editor) }
                return editor.commit()
            }
            override fun apply() {
                commit()
            }
        }
    }

    private val plain = InMemoryPrefs()
    private val target = FlakyPrefs()
    private var cipher = SoftwareCipher()
    private var keyExists = false
    private var keystoreError: Exception? = null
    private var keyResets = 0
    private var legacy: SharedPreferences? = null
    private var legacyFails = false
    private var legacyReads = 0
    private var legacyDeleted = false
    private var boot: Int? = 1
    private val countedThisProcess = mutableSetOf<String>()

    private fun logic() = SecretVaultLogic(
        plain = plain,
        target = target,
        openCipher = { allowCreate ->
            keystoreError?.let { throw it }
            if (!keyExists) {
                if (!allowCreate) throw KeyMissingException("test")
                keyExists = true
            }
            cipher
        },
        resetKey = { keyResets++; keyExists = false; keystoreError = null; cipher = SoftwareCipher() },
        legacyExists = { !legacyDeleted && (legacy != null || legacyFails) },
        openLegacy = {
            legacyReads++
            if (legacyFails) throw java.security.GeneralSecurityException("keyset")
            legacy!!
        },
        deleteLegacy = { legacyDeleted = true },
        legacyPlainKeys = listOf("health_webhook_headers", "health_webhook_secret"),
        knownKeys = listOf("health_webhook_headers", "health_webhook_secret", "mqtt_password"),
        bootCount = { boot },
        countedThisProcess = countedThisProcess
    )

    /** A new process on the same phone: it counts its failures afresh. */
    private fun newProcess() = countedThisProcess.clear()

    private fun oldStore(vararg values: Pair<String, String>) = InMemoryPrefs().apply {
        edit().apply { values.forEach { (k, v) -> putString(k, v) } }.commit()
    }

    private fun secret(opened: SecretVault.Opened, key: String = "health_webhook_secret") = opened.store.getString(key, null)

    // ==================== The store ====================

    @Test
    fun `a value comes back, and is not in the file as itself`() {
        val opened = logic().open()
        opened.store.edit().putString("health_webhook_secret", "s3cr3t-hmac").commit()
        assertEquals("s3cr3t-hmac", secret(opened))
        assertEquals("s3cr3t-hmac", secret(logic().open()))
        assertFalse(target.all.values.any { it.toString().contains("s3cr3t-hmac") })
        assertTrue((target.getString("health_webhook_secret", null) ?: "").startsWith("v1:"))
    }

    @Test
    fun `a value copied under another key does not decrypt`() {
        logic().open().store.edit().putString("health_webhook_secret", "one").commit()
        target.edit().putString("screentime_webhook_secret", target.getString("health_webhook_secret", null)).commit()
        assertNull(logic().open().store.getString("screentime_webhook_secret", null))
    }

    @Test
    fun `an altered or foreign value reads as absent instead of failing`() {
        logic().open()
        listOf("v1:not-base64!", "v1:AAAA", "plain text", "v2:whatever").forEach {
            target.edit().putString("health_webhook_secret", it).commit()
            assertNull(it, secret(logic().open()))
        }
    }

    @Test
    fun `only strings are stored, and the bookkeeping stays hidden and untouched`() {
        val opened = logic().open()
        assertTrue(runCatching { opened.store.edit().putBoolean("x", true) }.isFailure)
        assertTrue(runCatching { opened.store.edit().putString(SecretVaultLogic.MARKER, "x") }.isFailure)
        opened.store.edit().putString("mqtt_password", "pw").commit()
        assertEquals(setOf("mqtt_password"), opened.store.all.keys)
        opened.store.edit().clear().commit()
        assertTrue(opened.store.all.isEmpty())
        assertTrue("the migration stays done", target.getBoolean(SecretVaultLogic.MARKER, false))
    }

    @Test
    fun `an empty value is no value, so nothing empty is stored`() {
        val opened = logic().open()
        opened.store.edit().putString("mqtt_password", "pw").commit()
        opened.store.edit().putString("mqtt_password", "").putString("mqtt_username", "").commit()
        assertFalse(target.contains("mqtt_password"))
        assertFalse(target.contains("mqtt_username"))
    }

    // ==================== Migration ====================

    @Test
    fun `a fresh install is ready with nothing to migrate`() {
        val opened = logic().open()
        assertEquals(SecretState.READY, opened.state)
        assertTrue(target.getBoolean(SecretVaultLogic.MARKER, false))
    }

    @Test
    fun `every secret of the old store comes over, and then the old file goes`() {
        legacy = oldStore("health_webhook_secret" to "hmac", "health_webhook_headers" to "{\"X-Api-Key\":\"k\"}", "health_mqtt_password" to "pw", "screentime_mqtt_username" to "")
        val opened = logic().open()
        assertEquals(SecretState.READY, opened.state)
        assertEquals("hmac", secret(opened))
        assertEquals("{\"X-Api-Key\":\"k\"}", secret(opened, "health_webhook_headers"))
        assertEquals("pw", secret(opened, "health_mqtt_password"))
        assertEquals("an empty username stays empty", "", secret(opened, "screentime_mqtt_username"))
        assertTrue("so a secret rotated later cannot come back from it", legacyDeleted)
    }

    @Test
    fun `secrets from before 1-6-0 come over too, the encrypted ones win, and the plain copies go`() {
        plain.edit().putString("health_webhook_secret", "old-plain").putString("health_webhook_headers", "{}").commit()
        legacy = oldStore("health_webhook_secret" to "encrypted")
        val opened = logic().open()
        assertEquals("encrypted", secret(opened))
        assertEquals("{}", secret(opened, "health_webhook_headers"))
        assertNull(plain.getString("health_webhook_secret", null))
        assertNull(plain.getString("health_webhook_headers", null))
    }

    @Test
    fun `the migration runs once`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        logic().open()
        logic().open().store.edit().putString("health_webhook_secret", "changed later").commit()
        assertEquals(1, legacyReads)
        assertEquals("changed later", secret(logic().open()))
    }

    @Test
    fun `a failed write leaves nothing done and the old store in place, and the next start migrates`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        plain.edit().putString("health_webhook_headers", "{}").commit()
        target.failCommits = true
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        assertFalse(target.getBoolean(SecretVaultLogic.MARKER, false))
        assertFalse(legacyDeleted)
        assertEquals("{}", plain.getString("health_webhook_headers", null))
        target.failCommits = false
        val opened = logic().open()
        assertEquals("hmac", secret(opened))
        assertEquals("{}", secret(opened, "health_webhook_headers"))
    }

    @Test
    fun `a Keystore that cannot encrypt during the migration is unavailable, not a crash`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        cipher.encryptFails = true
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        assertFalse(legacyDeleted)
        cipher.encryptFails = false
        assertEquals("hmac", secret(logic().open()))
    }

    @Test
    fun `an old store that cannot be read is given up only on the third boot, and read later when it can be`() {
        legacyFails = true
        plain.edit().putString("health_webhook_secret", "plain").commit()
        repeat(20) { assertEquals("many tries in one boot count once", SecretState.UNAVAILABLE, logic().open().state) }
        boot = 2
        newProcess()
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        boot = 3
        newProcess()
        val opened = logic().open()
        assertEquals(SecretState.NEEDS_REENTRY, opened.state)
        assertEquals("what could be saved is saved", "plain", secret(opened))
        assertFalse("kept, to try again", legacyDeleted)

        // Entered again in the meantime, then the old store turns out readable after all.
        opened.store.edit().putString("health_webhook_secret", "entered").commit()
        legacyFails = false
        legacy = oldStore("health_webhook_secret" to "old", "mqtt_password" to "from-old")
        val later = logic().open()
        assertEquals("what was entered wins", "entered", secret(later))
        assertEquals("what was missing comes from the old store", "from-old", secret(later, "mqtt_password"))
        assertTrue(legacyDeleted)
    }

    @Test
    fun `plain secrets a restore brings back later are moved in and removed`() {
        logic().open()
        plain.edit().putString("health_webhook_secret", "restored").commit()
        assertEquals("restored", secret(logic().open()))
        assertNull(plain.getString("health_webhook_secret", null))
    }

    // ==================== The Keystore ====================

    @Test
    fun `a busy Keystore never wipes a secret or makes a new key, however long it lasts`() {
        logic().open().store.edit().putString("health_webhook_secret", "hmac").commit()
        keystoreError = KeyStoreException("system error")
        for (day in 2..12) {
            boot = day
            newProcess()
            repeat(5) { assertEquals(SecretState.UNAVAILABLE, logic().open().state) }
        }
        assertEquals(0, keyResets)
        keystoreError = null
        assertEquals("hmac", secret(logic().open()))
    }

    @Test
    fun `a probe that cannot be decrypted right now is unavailable, and wipes nothing`() {
        logic().open().store.edit().putString("health_webhook_secret", "hmac").commit()
        cipher.busy = true
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        cipher.busy = false
        val opened = logic().open()
        assertEquals(SecretState.READY, opened.state)
        assertEquals("hmac", secret(opened))
    }

    @Test
    fun `once there are values, a key that does not show up is never replaced on the spot`() {
        logic().open().store.edit().putString("health_webhook_secret", "hmac").commit()
        keyExists = false
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        assertFalse("no new key over the old one", keyExists)
        boot = 2
        newProcess()
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        assertEquals(0, keyResets)
        boot = 3
        newProcess()
        val opened = logic().open()
        assertEquals("gone on three boots: a new key, and the secrets asked for again", 1, keyResets)
        assertEquals(SecretState.NEEDS_REENTRY, opened.state)
        assertNull(secret(opened))
    }

    @Test
    fun `a key replaced before the migration still migrates the old store`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        keystoreError = java.security.UnrecoverableKeyException("unusable")
        repeat(2) { b ->
            boot = b + 1
            newProcess()
            assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        }
        boot = 3
        newProcess()
        val opened = logic().open()
        assertEquals(1, keyResets)
        assertEquals(SecretState.READY, opened.state)
        assertEquals("hmac", secret(opened))
    }

    @Test
    fun `a key that provably wrote nothing here is noticed by the probe, and the unreadable values are wiped`() {
        logic().open().store.edit().putString("health_webhook_secret", "hmac").commit()
        cipher = SoftwareCipher()
        val opened = logic().open()
        assertEquals(SecretState.NEEDS_REENTRY, opened.state)
        assertNull(target.getString("health_webhook_secret", null))
    }

    @Test
    fun `without a boot counter, failures count once per process`() {
        boot = null
        legacyFails = true
        repeat(SecretVaultLogic.PERSISTENT_PROCESSES - 1) {
            newProcess()
            repeat(3) { assertEquals(SecretState.UNAVAILABLE, logic().open().state) }
        }
        newProcess()
        assertEquals(SecretState.NEEDS_REENTRY, logic().open().state)
    }

    @Test
    fun `the failure counters stay out of the backed-up settings`() {
        keystoreError = java.security.UnrecoverableKeyException("unusable")
        logic().open()
        assertTrue(plain.all.isEmpty())
        assertTrue(target.all.keys.any { it.startsWith("__fail") })
    }

    // ==================== Writing during trouble ====================

    @Test
    fun `a value that could not be read is not overwritten by the next save`() {
        logic().open().store.edit().putString("health_webhook_headers", "{\"Authorization\":\"Bearer t\"}").putString("mqtt_password", "pw").commit()
        val opened = logic().open()
        cipher.busy = true
        assertNull("shown as empty during the hiccup", opened.store.getString("health_webhook_headers", null))
        cipher.busy = false
        // The settings screen saves every field, the empty one included.
        opened.store.edit().putString("health_webhook_headers", null).putString("mqtt_password", "pw").commit()
        assertEquals("{\"Authorization\":\"Bearer t\"}", logic().open().store.getString("health_webhook_headers", null))
    }

    @Test
    fun `saving unrelated settings does not count as entering the secrets again`() {
        legacyFails = true
        for (b in 1..3) {
            boot = b
            newProcess()
            logic().open()
        }
        val opened = logic().open()
        assertTrue(opened.needsReentry)
        opened.store.edit().putString("mqtt_username", "").putString("mqtt_password", "").putString("health_webhook_headers", null).commit()
        assertTrue("nothing was entered", opened.needsReentry)
        opened.store.edit().putString("health_webhook_secret", "new").commit()
        assertFalse(opened.needsReentry)
        assertEquals(SecretState.READY, logic().open().state)
    }

    @Test
    fun `writing the value that is already stored costs nothing`() {
        val opened = logic().open()
        opened.store.edit().putString("health_webhook_secret", "same").commit()
        val stored = target.getString("health_webhook_secret", null)
        opened.store.edit().putString("health_webhook_secret", "same").commit()
        assertEquals("not encrypted again with a new IV", stored, target.getString("health_webhook_secret", null))
    }
}
