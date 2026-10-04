package com.owen282000.lifedashboard.securestore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

/**
 * The secret store on a real Android Keystore, and the migration out of a real security-crypto
 * file written in the test, as 1.6.0 to 1.22 wrote it. Every test uses files and a key alias of
 * its own, so the app's own secrets are never touched.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    private val context: Context = TestSetup.context
    private val alias = "ldsuite_secrets_test"
    private val targetName = "ldsuite_secrets_target"
    private val plainName = "ldsuite_secrets_plain"
    private val legacyName = "ldsuite_secure_prefs_legacy"

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun legacyStore(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        legacyName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun file(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    private fun logic(legacy: Boolean = true) = SecretVaultLogic(
        plain = prefs(plainName),
        target = prefs(targetName),
        openCipher = { allowCreate -> KeystoreCipher.open(alias, allowCreate) },
        resetKey = { KeystoreCipher.delete(alias) },
        legacyExists = { legacy && file(legacyName).exists() },
        legacyHasValues = { SecretVault.holdsSecrets(prefs(legacyName)) },
        openLegacy = { legacyStore() },
        deleteLegacy = { context.deleteSharedPreferences(legacyName) },
        legacyPlainKeys = PreferencesManager.LEGACY_PLAIN_SECRET_KEYS,
        knownKeys = PreferencesManager.SECRET_KEYS,
        bootCount = { 1 }
    )

    @After
    fun cleanUp() {
        listOf(targetName, plainName, legacyName).forEach { context.deleteSharedPreferences(it) }
        runCatching { KeystoreCipher.delete(alias) }
    }

    @Test
    fun aSecretSurvivesANewStoreOnTheSameKey() {
        logic(legacy = false).open().store.edit().putString("health_webhook_secret", "hmac-1").commit()
        val reopened = logic(legacy = false).open()
        assertEquals(SecretState.READY, reopened.state)
        assertEquals("hmac-1", reopened.store.getString("health_webhook_secret", null))
    }

    @Test
    fun theSecretsOfASecurityCryptoFileComeOver() {
        legacyStore().edit()
            .putString("health_webhook_secret", "SENTINEL-hmac-7f3a")
            .putString("health_webhook_headers", "{\"X-Api-Key\":\"SENTINEL-header-91c2\"}")
            .putString("mqtt_password", "SENTINEL-mqtt-4b8d")
            .commit()
        val opened = logic().open()
        assertEquals(SecretState.READY, opened.state)
        assertEquals("SENTINEL-hmac-7f3a", opened.store.getString("health_webhook_secret", null))
        assertEquals("{\"X-Api-Key\":\"SENTINEL-header-91c2\"}", opened.store.getString("health_webhook_headers", null))
        assertEquals("SENTINEL-mqtt-4b8d", opened.store.getString("mqtt_password", null))
        assertFalse("security-crypto's keysets are not secrets", opened.store.all.keys.any { it.contains("androidx_security") })
        assertFalse("the old file goes once its secrets are safe", file(legacyName).exists())
    }

    @Test
    fun aSecurityCryptoFileWithoutSecretsIsNotReadAndGoes() {
        // Opening it is enough for security-crypto to write its own keys into the file.
        legacyStore().edit().putString("health_webhook_secret", "x").remove("health_webhook_secret").commit()
        assertTrue(file(legacyName).exists())
        assertFalse("only its own keys", SecretVault.holdsSecrets(prefs(legacyName)))
        val opened = logic().open()
        assertEquals("nothing was lost, so nothing is asked for", SecretState.READY, opened.state)
        assertFalse(file(legacyName).exists())
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
