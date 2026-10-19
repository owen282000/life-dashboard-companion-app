package com.owen282000.lifedashboard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.ProviderException

/**
 * Where the destinations are stored, and the update over 1.27.0: derived from the old keys on
 * every read, stored at the first change, and never stored while the secrets are out of reach.
 */
class DestinationStoreTest {

    /** Stands in for the Keystore: no real encryption, and a switch for one that cannot decrypt right now. */
    private class TestCipher : SecretCipher {
        var busy = false
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = ByteArray(IV) + plain
        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            if (busy) throw ProviderException("keystore busy")
            return blob.copyOfRange(IV, blob.size)
        }

        companion object {
            const val IV = 12
        }
    }

    private val typed = "https://stack.example/health"
    private val paired = "http://homeassistant.local:8123/api/webhook/abc"
    private val headers = mapOf("X-Api-Key" to "k")

    private val plain = InMemoryPrefs()
    private val cipher = TestCipher()
    private val encryptedFile = InMemoryPrefs()

    /** The secret store of a process that could open the Keystore. Each call is a new process: nothing cached. */
    private fun secrets() = EncryptedStore(encryptedFile, cipher)

    private fun store(secrets: android.content.SharedPreferences = secrets(), usable: Boolean = true) =
        DestinationStore(plain, secrets) { usable }

    /** What 1.27.0 left: two addresses, one secret, headers, and pairing's mark on the second address. */
    private fun updatedFrom127() {
        plain.edit()
            .putString(DestinationStore.HEALTH.urls, "$typed,$paired")
            .putString(DestinationStore.HEALTH.urlsWithoutHeaders, Json.encodeToString(listOf(paired)))
            .commit()
        secrets().edit()
            .putString(DestinationStore.HEALTH.secret, "s3cret")
            .putString(DestinationStore.HEALTH.headers, Json.encodeToString(headers))
            .commit()
    }

    @Test
    fun theUpdateChangesNothingAnyReceiverGets() {
        updatedFrom127()

        val read = store().read(LogType.HEALTH_CONNECT)

        assertEquals(listOf(typed, paired), read.map { it.url })
        assertEquals(listOf("s3cret", "s3cret"), read.map { it.secret })
        assertEquals(listOf(headers, emptyMap()), read.map { it.headers })
        assertFalse("reading stores nothing", store().isStored(LogType.HEALTH_CONNECT))
    }

    private val foreign = Destination("foreign", "https://foreign.example/hook", headers = mapOf("Authorization" to "Bearer foreign"))

    /** Nothing of what a refused store was asked to write: the settings read exactly as before it. */
    private fun assertUnchangedFrom127() {
        val back = store().read(LogType.HEALTH_CONNECT)
        assertEquals(listOf(typed, paired), back.map { it.url })
        assertEquals(listOf("s3cret", "s3cret"), back.map { it.secret })
        assertEquals(listOf(headers, emptyMap()), back.map { it.headers })
        assertFalse(store().isStored(LogType.HEALTH_CONNECT))
    }

    @Test
    fun destinationsDerivedWhileKeystoreUnavailable() {
        updatedFrom127()
        // Right after a reboot: the secret store is the stand-in that holds nothing.
        val down = store(InMemoryPrefs(), usable = false)

        val read = down.read(LogType.HEALTH_CONNECT)
        assertEquals(listOf(typed, paired), read.map { it.url })
        assertEquals(listOf<String?>(null, null), read.map { it.secret })

        // A settings save then, from a tab whose store is that stand-in, is refused whole: it
        // would lose the new secret, and leave an address with headers of its own that a later
        // read gives the section's.
        assertFalse(down.write(LogType.HEALTH_CONNECT, read + foreign.copy(secret = "lost")))

        // The Keystore is back: as if nothing happened, and no other host has the section's key.
        assertUnchangedFrom127()
    }

    @Test
    fun anImportWhileTheSectionHeadersCannotBeReadIsRefused() {
        updatedFrom127()
        // The Keystore is open, but cannot decrypt the section's headers right now.
        cipher.busy = true
        val hiccup = store()

        assertFalse(hiccup.canWrite(LogType.HEALTH_CONNECT, listOf(foreign)))
        assertFalse(hiccup.write(LogType.HEALTH_CONNECT, listOf(foreign)))

        cipher.busy = false
        assertUnchangedFrom127()
    }

    @Test
    fun aPairingWhileTheSectionSecretCannotBeReadIsRefused() {
        updatedFrom127()
        cipher.busy = true
        val hiccup = store()
        val read = hiccup.read(LogType.HEALTH_CONNECT)
        assertNull(read.first().secret)

        // 29c2acb wrote the address alone, and the paired receiver got the old section secret.
        assertFalse(hiccup.write(LogType.HEALTH_CONNECT, Destinations.paired(read, "https://new.example/hook", "paired-secret") { "new" }))

        cipher.busy = false
        assertUnchangedFrom127()
    }

    @Test
    fun aStoredListIsNotChangedWhileTheSecretsCannotBeKept() {
        val stack = Destination("stack", typed, secret = "s1", headers = headers)
        assertTrue(store().write(LogType.HEALTH_CONNECT, listOf(stack)))

        // After a reboot with the Keystore out of reach, a pairing adds Home Assistant.
        val down = store(InMemoryPrefs(), usable = false)
        assertFalse(down.write(LogType.HEALTH_CONNECT, down.read(LogType.HEALTH_CONNECT) + Destination("ha", paired, secret = "ha-secret")))

        // No destination was stored that a worker would then send unsigned.
        assertEquals(listOf(stack), store().read(LogType.HEALTH_CONNECT))
    }

    @Test
    fun aDestinationWhoseSecretCannotBeReadNowIsNotSaved() {
        val stack = Destination("stack", typed, secret = "s1")
        store().write(LogType.HEALTH_CONNECT, listOf(stack))
        cipher.busy = true

        assertFalse(store().write(LogType.HEALTH_CONNECT, listOf(stack.copy(secret = null))))

        cipher.busy = false
        assertEquals(listOf(stack), store().read(LogType.HEALTH_CONNECT))
    }

    @Test
    fun theKeysOf127FailClosedWhenTheListHasToBeDerivedAgain() {
        updatedFrom127()
        val read = store().read(LogType.HEALTH_CONNECT)
        store().write(LogType.HEALTH_CONNECT, read)
        // Added after the first store, without headers of its own.
        store().write(LogType.HEALTH_CONNECT, read + Destination("new", "https://new.example/hook", secret = "s3cret"))

        // The list cannot be read any more (or an older version reads the old keys).
        plain.edit().putString(DestinationStore.HEALTH.list, "not a list").commit()

        val derived = store().read(LogType.HEALTH_CONNECT)
        assertEquals(listOf(typed, paired, "https://new.example/hook"), derived.map { it.url })
        assertEquals(listOf(headers, emptyMap(), emptyMap()), derived.map { it.headers })
    }

    @Test
    fun theFirstChangeStoresEveryDestinationWithWhatItHad() {
        updatedFrom127()
        val read = store().read(LogType.HEALTH_CONNECT)

        // The user gives Home Assistant a secret of its own.
        store().write(LogType.HEALTH_CONNECT, read.map { if (it.url == paired) it.copy(secret = "ha-secret") else it })

        assertTrue(store().isStored(LogType.HEALTH_CONNECT))
        val stored = store().read(LogType.HEALTH_CONNECT)
        assertEquals(read.map { it.id }, stored.map { it.id })
        assertEquals(listOf("s3cret", "ha-secret"), stored.map { it.secret })
        assertEquals(listOf(headers, emptyMap()), stored.map { it.headers })
        // The address list of 1.27.0 is still written, for the outbox and the drain.
        assertEquals("$typed,$paired", plain.getString(DestinationStore.HEALTH.urls, null))
    }

    @Test
    fun editedUrlKeepsItsSecretAndHeaders() {
        val stack = Destination("stack", "http://stack.example/health", secret = "s1", headers = headers)
        store().write(LogType.SCREEN_TIME, listOf(stack))

        store().write(LogType.SCREEN_TIME, listOf(stack.copy(url = "https://stack.example/health")))

        assertEquals(listOf(stack.copy(url = "https://stack.example/health")), store().read(LogType.SCREEN_TIME))
        assertEquals("https://stack.example/health", plain.getString(DestinationStore.SCREEN_TIME.urls, null))
    }

    @Test
    fun aRemovedDestinationTakesItsSecretsAlongAndTheSharedSecretBecomesTheSections() {
        val a = Destination("a", typed, secret = "shared", headers = headers)
        val b = Destination("b", paired, secret = "shared")
        store().write(LogType.HEALTH_CONNECT, listOf(a, b))
        assertEquals("shared", store().sectionSecret(LogType.HEALTH_CONNECT))

        store().write(LogType.HEALTH_CONNECT, listOf(a.copy(secret = "other")))

        assertFalse(encryptedFile.contains(DestinationStore.secretKey("b")))
        assertFalse(encryptedFile.contains(DestinationStore.headersKey("b")))
        // One destination is all of them, so its secret is the section's now.
        assertEquals("other", store().sectionSecret(LogType.HEALTH_CONNECT))

        // Two that differ leave the section's alone.
        store().write(LogType.HEALTH_CONNECT, listOf(a.copy(secret = "x"), b.copy(secret = "y")))
        assertEquals("other", store().sectionSecret(LogType.HEALTH_CONNECT))

        // A secret cleared is gone, not empty.
        store().write(LogType.HEALTH_CONNECT, listOf(a.copy(secret = " ")))
        assertNull(store().read(LogType.HEALTH_CONNECT).single().secret)
    }

    @Test
    fun theKeysOf127MakeTheSettingsAgain() {
        store().write(LogType.HEALTH_CONNECT, listOf(Destination("a", typed, secret = "own")))

        store().setLegacyUrls(LogType.HEALTH_CONNECT, listOf(paired))

        assertFalse(store().isStored(LogType.HEALTH_CONNECT))
        assertFalse(encryptedFile.contains(DestinationStore.secretKey("a")))
        assertEquals(listOf(paired), store().read(LogType.HEALTH_CONNECT).map { it.url })
    }
}
