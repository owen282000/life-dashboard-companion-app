package com.owen282000.lifedashboard

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The secret store that replaces security-crypto, and the migration into it, with a software
 * AES-GCM key standing in for the Keystore. What matters most: no secret is lost on the way,
 * none is ever kept in plain text, and a failure that may pass leaves everything as it was.
 */
class SecretVaultTest {

    /** AES-256-GCM as KeystoreCipher does it, with a key in memory. */
    private class SoftwareCipher(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretCipher {
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, 12, blob.size - 12)
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
            override fun apply() { commit() }
        }
    }

    private val plain = InMemoryPrefs()
    private val target = FlakyPrefs()
    private var cipher: SecretCipher = SoftwareCipher()
    private var created = false
    private var keystoreFails = false
    private var keyResets = 0
    private var legacy: SharedPreferences? = null
    private var legacyFails = false
    private var legacyReads = 0
    private var clock = 1_000_000L

    private fun logic() = SecretVaultLogic(
        plain = plain,
        target = target,
        openCipher = {
            if (keystoreFails) throw java.security.KeyStoreException("busy")
            KeystoreCipher.Opening(cipher, created)
        },
        resetKey = { keyResets++; keystoreFails = false; cipher = SoftwareCipher(); created = true },
        legacyExists = { legacy != null || legacyFails },
        openLegacy = {
            legacyReads++
            if (legacyFails) throw java.security.GeneralSecurityException("keyset")
            legacy!!
        },
        legacyPlainKeys = listOf("health_webhook_headers", "health_webhook_secret"),
        now = { clock }
    )

    private fun oldStore(vararg values: Pair<String, String>) = InMemoryPrefs().apply {
        edit().apply { values.forEach { (k, v) -> putString(k, v) } }.commit()
    }

    // ==================== The store ====================

    @Test
    fun `a value comes back, and is not in the file as itself`() {
        val opened = logic().open()
        opened.store.edit().putString("health_webhook_secret", "s3cr3t-hmac").commit()
        assertEquals("s3cr3t-hmac", opened.store.getString("health_webhook_secret", null))
        assertFalse(target.all.values.any { it.toString().contains("s3cr3t-hmac") })
        assertTrue((target.getString("health_webhook_secret", null) ?: "").startsWith("v1:"))
    }

    @Test
    fun `a value copied under another key does not decrypt`() {
        val opened = logic().open()
        opened.store.edit().putString("health_webhook_secret", "one").commit()
        target.edit().putString("screentime_webhook_secret", target.getString("health_webhook_secret", null)).commit()
        assertNull(opened.store.getString("screentime_webhook_secret", null))
    }

    @Test
    fun `an altered or foreign value reads as absent instead of failing`() {
        val opened = logic().open()
        listOf("v1:not-base64!", "v1:AAAA", "plain text", "v2:whatever").forEach {
            target.edit().putString("health_webhook_secret", it).commit()
            assertNull(it, opened.store.getString("health_webhook_secret", null))
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

    // ==================== Migration ====================

    @Test
    fun `a fresh install is ready with nothing to migrate`() {
        val opened = logic().open()
        assertEquals(SecretState.READY, opened.state)
        assertTrue(target.getBoolean(SecretVaultLogic.MARKER, false))
    }

    @Test
    fun `every secret of the old store comes over, and the old file is left alone`() {
        legacy = oldStore("health_webhook_secret" to "hmac", "health_webhook_headers" to "{\"X-Api-Key\":\"k\"}", "health_mqtt_password" to "pw", "screentime_mqtt_username" to "")
        val opened = logic().open()
        assertEquals(SecretState.READY, opened.state)
        assertEquals("hmac", opened.store.getString("health_webhook_secret", null))
        assertEquals("{\"X-Api-Key\":\"k\"}", opened.store.getString("health_webhook_headers", null))
        assertEquals("pw", opened.store.getString("health_mqtt_password", null))
        assertEquals("an empty username stays empty", "", opened.store.getString("screentime_mqtt_username", null))
        assertEquals("hmac", legacy!!.getString("health_webhook_secret", null))
    }

    @Test
    fun `secrets from before 1-6-0 come over too, the encrypted ones win, and the plain copies go`() {
        plain.edit().putString("health_webhook_secret", "old-plain").putString("health_webhook_headers", "{}").commit()
        legacy = oldStore("health_webhook_secret" to "encrypted")
        val opened = logic().open()
        assertEquals("encrypted", opened.store.getString("health_webhook_secret", null))
        assertEquals("{}", opened.store.getString("health_webhook_headers", null))
        assertNull(plain.getString("health_webhook_secret", null))
        assertNull(plain.getString("health_webhook_headers", null))
    }

    @Test
    fun `the migration runs once, and a later start does not read the old store again`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        logic().open()
        logic().open().store.edit().putString("health_webhook_secret", "changed later").commit()
        val again = logic().open()
        assertEquals(1, legacyReads)
        assertEquals("changed later", again.store.getString("health_webhook_secret", null))
    }

    @Test
    fun `a failed write leaves nothing done, and the next start migrates`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        plain.edit().putString("health_webhook_headers", "{}").commit()
        target.failCommits = true
        assertEquals(SecretState.UNAVAILABLE, logic().open().state)
        assertFalse(target.getBoolean(SecretVaultLogic.MARKER, false))
        assertEquals("the plain copy stays until the store holds it", "{}", plain.getString("health_webhook_headers", null))
        target.failCommits = false
        val opened = logic().open()
        assertEquals("hmac", opened.store.getString("health_webhook_secret", null))
        assertEquals("{}", opened.store.getString("health_webhook_headers", null))
    }

    @Test
    fun `an old store that cannot be read is retried, and given up only after a day of tries`() {
        legacyFails = true
        plain.edit().putString("health_webhook_secret", "plain").commit()
        repeat(4) {
            assertEquals(SecretState.UNAVAILABLE, logic().open().state)
            clock += 60 * 60 * 1000L
        }
        assertEquals("five tries, but not yet a day", SecretState.UNAVAILABLE, logic().open().state)
        clock += 24 * 60 * 60 * 1000L
        val opened = logic().open()
        assertEquals(SecretState.NEEDS_REENTRY, opened.state)
        assertEquals("what could be saved is saved", "plain", opened.store.getString("health_webhook_secret", null))
        assertTrue(opened.needsReentry)
    }

    // ==================== The Keystore ====================

    @Test
    fun `a Keystore that fails leaves everything as it was and keeps nothing`() {
        legacy = oldStore("health_webhook_secret" to "hmac")
        keystoreFails = true
        val opened = logic().open()
        assertEquals(SecretState.UNAVAILABLE, opened.state)
        assertTrue(opened.store is InMemoryPrefs)
        assertTrue(target.all.isEmpty())
        assertEquals(0, legacyReads)
        assertEquals(0, keyResets)
    }

    @Test
    fun `a Keystore that stays broken for a day gets a new key and asks for the secrets again`() {
        logic().open().store.edit().putString("health_webhook_secret", "hmac").commit()
        keystoreFails = true
        // Four tries over 18 hours: still only unavailable, and the key is kept.
        repeat(4) {
            assertEquals(SecretState.UNAVAILABLE, logic().open().state)
            clock += 6 * 60 * 60 * 1000L
        }
        assertEquals(0, keyResets)
        // The fifth, a day after the first: given up on.
        val opened = logic().open()
        assertEquals(1, keyResets)
        assertEquals(SecretState.NEEDS_REENTRY, opened.state)
        assertNull(opened.store.getString("health_webhook_secret", null))
    }

    @Test
    fun `a key that is gone is noticed by the probe, and the unreadable values are wiped`() {
        logic().open().store.edit().putString("health_webhook_secret", "hmac").commit()
        cipher = SoftwareCipher()
        created = true
        val opened = logic().open()
        assertEquals(SecretState.NEEDS_REENTRY, opened.state)
        assertNull(target.getString("health_webhook_secret", null))
    }

    @Test
    fun `saving a secret again clears the request to enter them`() {
        legacyFails = true
        repeat(5) { logic().open(); clock += 6 * 60 * 60 * 1000L }
        val opened = logic().open()
        assertTrue(opened.needsReentry)
        opened.store.edit().putString("health_webhook_secret", "new").commit()
        assertFalse(opened.needsReentry)
        assertEquals(SecretState.READY, logic().open().state)
    }

    @Test
    fun `clearing the values does not count as entering them again`() {
        cipher = SoftwareCipher()
        logic().open()
        target.edit().putBoolean(EncryptedStore.NEEDS_REENTRY, true).commit()
        val opened = logic().open()
        opened.store.edit().clear().commit()
        assertTrue(opened.needsReentry)
    }
}
