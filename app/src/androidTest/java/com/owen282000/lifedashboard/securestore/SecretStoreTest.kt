package com.owen282000.lifedashboard.securestore

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.EncryptedStore
import com.owen282000.lifedashboard.KeystoreCipher
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.SecretState
import com.owen282000.lifedashboard.SecretVault
import com.owen282000.lifedashboard.SecretVaultLogic
import com.owen282000.lifedashboard.harness.TestSetup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The secret store on a real Android Keystore, and what happens to a security-crypto file of
 * 1.6.0 to 1.22 that is still on the phone: it can no longer be read, so it is deleted with its
 * master key. The file is written in the test as plain XML shaped like the real one. Every test
 * uses files and key aliases of its own, so the app's own secrets are never touched.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    private val context: Context = TestSetup.context
    private val alias = "ldsuite_secrets_test"
    private val targetName = "ldsuite_secrets_target"
    private val plainName = "ldsuite_secrets_plain"
    private val legacyName = "ldsuite_secure_prefs_legacy"
    private val legacyAlias = "ldsuite_legacy_master_key_test"

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun file(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    /**
     * An old file as security-crypto left it on disk: its two keysets, and an entry per secret
     * with an encrypted name and value. Written as XML, so the test needs no security-crypto,
     * with a Keystore key under [legacyAlias] standing in for its master key.
     */
    private fun writeLegacyFile(withSecret: Boolean) {
        context.deleteSharedPreferences(legacyName)
        val secret = if (withSecret) {
            "    <string name=\"AQ5vX2ZpbGxlcl9rZXlfbmFtZV9lbmNyeXB0ZWQ=\">AWa1b2Nfc2VjcmV0X3ZhbHVlX2VuY3J5cHRlZF9ieV9nY20=</string>\n"
        } else {
            ""
        }
        file(legacyName).apply { parentFile?.mkdirs() }.writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"__androidx_security_crypto_encrypted_prefs_key_keyset__\">12a901f3e2a1c0</string>\n" +
                "    <string name=\"__androidx_security_crypto_encrypted_prefs_value_keyset__\">128801b4d5c6e7</string>\n" +
                secret +
                "</map>\n"
        )
        KeystoreCipher.open(legacyAlias, allowCreate = true)
    }

    private fun aliasExists(alias: String) = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    private fun logic(legacy: Boolean = true) = SecretVaultLogic(
        plain = prefs(plainName),
        target = prefs(targetName),
        openCipher = { allowCreate -> KeystoreCipher.open(alias, allowCreate) },
        resetKey = { KeystoreCipher.delete(alias) },
        legacyExists = { legacy && file(legacyName).exists() },
        legacyHasValues = { SecretVault.holdsSecrets(prefs(legacyName)) },
        deleteLegacy = { SecretVault.deleteLegacy(context, legacyName, legacyAlias) },
        legacyPlainKeys = PreferencesManager.LEGACY_PLAIN_SECRET_KEYS,
        bootCount = { 1 }
    )

    @After
    fun cleanUp() {
        listOf(targetName, plainName, legacyName).forEach { context.deleteSharedPreferences(it) }
        runCatching { KeystoreCipher.delete(alias) }
        runCatching { KeystoreCipher.delete(legacyAlias) }
    }

    @Test
    fun aSecretSurvivesANewStoreOnTheSameKey() {
        logic(legacy = false).open().store.edit().putString("health_webhook_secret", "hmac-1").commit()
        val reopened = logic(legacy = false).open()
        assertEquals(SecretState.READY, reopened.state)
        assertEquals("hmac-1", reopened.store.getString("health_webhook_secret", null))
    }

    @Test
    fun aSecurityCryptoFileWithSecretsIsLostAndGoesWithItsKey() {
        writeLegacyFile(withSecret = true)
        assertTrue("a secret besides the keysets", SecretVault.holdsSecrets(prefs(legacyName)))
        assertTrue(aliasExists(legacyAlias))
        val opened = logic().open()
        assertEquals("the secrets are asked for again", SecretState.NEEDS_REENTRY, opened.state)
        assertTrue(opened.needsReentry)
        assertFalse("security-crypto's keysets are not secrets", opened.store.all.keys.any { it.contains("androidx_security") })
        assertFalse("nothing is left waiting", prefs(targetName).contains(SecretVaultLogic.LEGACY_PENDING))
        assertFalse("the old file goes", file(legacyName).exists())
        assertFalse("its master key goes too", aliasExists(legacyAlias))
        opened.store.edit().putString("health_webhook_secret", "SENTINEL-hmac-7f3a").commit()
        assertEquals(SecretState.READY, logic().open().state)
    }

    @Test
    fun aSecurityCryptoFileWithoutSecretsGoesAndAsksNothing() {
        writeLegacyFile(withSecret = false)
        assertTrue(file(legacyName).exists())
        assertFalse("only its own keys", SecretVault.holdsSecrets(prefs(legacyName)))
        val opened = logic().open()
        assertEquals("nothing was lost, so nothing is asked for", SecretState.READY, opened.state)
        assertFalse(file(legacyName).exists())
        assertFalse(aliasExists(legacyAlias))
    }

    @Test
    fun theFileOnDiskHoldsNoSecretInPlainText() {
        val opened = logic(legacy = false).open()
        opened.store.edit()
            .putString("health_webhook_secret", "SENTINEL-hmac-7f3a")
            .putString("mqtt_password", "SENTINEL-mqtt-4b8d")
            .commit()
        val xml = file(targetName).readText()
        assertFalse(xml, xml.contains("SENTINEL"))
        assertTrue(xml.contains("v1:"))
    }

    @Test
    fun aKeyThatIsGoneIsNotReplacedOnTheSpot() {
        logic(legacy = false).open().store.edit().putString("health_webhook_secret", "hmac-1").commit()
        KeystoreCipher.delete(alias)
        // A missing key after the migration is a failure, never a reason to make a new one here
        // and now: on Android 8 to 11 the Keystore says that of keys that exist when it cannot
        // be reached.
        val reopened = logic(legacy = false).open()
        assertEquals(SecretState.UNAVAILABLE, reopened.state)
        assertTrue(reopened.store is com.owen282000.lifedashboard.InMemoryPrefs)
        assertTrue("the values stay", prefs(targetName).contains("health_webhook_secret"))
    }

    @Test
    fun aValueFromAnotherKeyWipesAndAsksForTheSecretsAgain() {
        logic(legacy = false).open().store.edit().putString("health_webhook_secret", "hmac-1").commit()
        // Another key: as after a key replaced on purpose. The probe proves the values unreadable.
        KeystoreCipher.delete(alias)
        KeystoreCipher.open(alias, allowCreate = true)
        val reopened = logic(legacy = false).open()
        assertEquals(SecretState.NEEDS_REENTRY, reopened.state)
        assertNull(reopened.store.getString("health_webhook_secret", null))
        reopened.store.edit().putString("health_webhook_secret", "hmac-2").commit()
        assertEquals(SecretState.READY, logic(legacy = false).open().state)
    }

    @Test
    fun theAppsOwnStoreIsTheNewOne() {
        val prefs = PreferencesManager(context)
        assertFalse(prefs.secretsUnavailable)
        prefs.setHealthWebhookSecret("SENTINEL-app-2c6e")
        val raw = context.getSharedPreferences(SecretVault.FILE, Context.MODE_PRIVATE).getString("health_webhook_secret", null)
        assertTrue("stored encrypted in the new file: $raw", raw?.startsWith("v1:") == true)
        assertFalse(file(SecretVault.FILE).readText().contains("SENTINEL"))
        assertEquals("SENTINEL-app-2c6e", PreferencesManager(context).getHealthWebhookSecret())
        prefs.clearAllSecrets()
        assertTrue(context.getSharedPreferences(SecretVault.FILE, Context.MODE_PRIVATE).getBoolean(SecretVaultLogic.MARKER, false))
        assertTrue(EncryptedStore.INTERNAL_PREFIX.isNotEmpty())
    }
}
