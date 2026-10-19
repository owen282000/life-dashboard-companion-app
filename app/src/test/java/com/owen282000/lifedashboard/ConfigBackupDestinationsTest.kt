package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `destinations` block of a settings backup, and what a reader without it makes of the section keys (P2-13). */
class ConfigBackupDestinationsTest {

    private val stack = Destination("stack-id", "https://stack.example/health", secret = "stack-secret", headers = mapOf("X-Api-Key" to "k"))
    private val ha = Destination("ha-id", "http://homeassistant.local:8123/api/webhook/abc", secret = "ha-secret")

    /** A section as ConfigBackupManager.export writes it. */
    private fun exported(destinations: List<Destination>, sectionSecret: String? = null): SectionConfig {
        val legacy = Destinations.legacy(destinations, sectionSecret)
        return SectionConfig(
            webhookUrls = destinations.map { it.url },
            headers = legacy.headers,
            signingSecret = legacy.secret,
            urlsWithoutHeaders = legacy.urlsWithoutHeaders,
            destinations = destinations.map { DestinationConfig.from(it) }
        )
    }

    private fun backup(health: SectionConfig) = ConfigBackup(health = health, screenTime = exported(listOf(stack)))

    @Test
    fun importRoundTrip() {
        val file = ConfigBackup.decode(backup(exported(listOf(stack, ha))).encode())

        assertEquals(listOf(stack, ha), file.health.destinationsOnImport(emptyList(), null, file.containsSecrets()))
    }

    @Test
    fun exportWithoutSecretsHoldsNoHeadersOrSecrets() {
        val shared = backup(exported(listOf(stack, ha))).withoutSecrets()
        val text = shared.encode()

        assertFalse(shared.containsSecrets())
        for (secret in listOf("stack-secret", "ha-secret", "X-Api-Key", "\"k\"")) {
            assertFalse(secret, text.contains(secret))
        }
        // The addresses and ids stay, so an import on the same phone keeps its own secrets by them.
        assertEquals(listOf(stack.id, ha.id), shared.health.destinations?.map { it.id })
        assertTrue(backup(exported(listOf(ha))).containsSecrets())
    }

    @Test
    fun theDestinationsBlockHoldsNoNulls() {
        // The iPhone app refuses a null; a destination without a secret leaves the key out.
        val unsigned = Destination("plain-id", "https://plain.example/hook")
        val text = backup(exported(listOf(unsigned, ha))).encode()
        val health = Json.parseToJsonElement(text).jsonObject["health"]!!.jsonObject
        val destinations = health["destinations"]!!.jsonArray.map { it.jsonObject }

        assertFalse(destinations.toString().contains("null"))
        assertFalse("signing_secret" in destinations[0])
        assertEquals(setOf("id", "url", "format", "headers", "signing_secret"), destinations[1].keys)
        // A file without destinations, from before them, does not get an empty or null block.
        assertFalse("destinations" in (Json.parseToJsonElement(ConfigBackup().encode()).jsonObject["health"] as JsonObject))
    }

    @Test
    fun legacyHeadersOnlyWhenAllDestinationsShareThem() {
        // Two receivers with keys of their own: an old reader gets none, for no address.
        val other = Destination("other-id", "https://other.example/hook", headers = mapOf("Authorization" to "Bearer t"))
        val section = exported(listOf(stack, ha, other))

        assertEquals(emptyMap<String, String>(), section.headers)
        assertEquals(section.webhookUrls, section.urlsWithoutHeaders)
        assertNull("the secrets differ, so none", section.signingSecret)

        // Read by the rules of a version without destinations: no header goes anywhere.
        val asOldReader = section.copy(destinations = null).destinationsOnImport(emptyList(), null, backupHasSecrets = true)
        assertTrue(asOldReader!!.all { it.headers.isEmpty() })

        // One set of headers and an address without: written as 1.27.0 wrote it.
        val familiar = exported(listOf(stack, ha.copy(secret = "stack-secret")))
        assertEquals(stack.headers, familiar.headers)
        assertEquals(listOf(ha.url), familiar.urlsWithoutHeaders)
        assertEquals("stack-secret", familiar.signingSecret)
    }

    @Test
    fun importOfAnOldFormatFile() {
        // Written by 1.27.0: one secret, headers for the address it typed, none for the one it paired.
        val old = SectionConfig(
            webhookUrls = listOf(stack.url, ha.url),
            headers = stack.headers,
            signingSecret = "s3cret",
            urlsWithoutHeaders = listOf(ha.url)
        )
        var ids = 0

        val imported = old.destinationsOnImport(emptyList(), null, backupHasSecrets = true) { "new-${ids++}" }

        assertEquals(
            listOf(
                Destination("new-0", stack.url, secret = "s3cret", headers = stack.headers),
                Destination("new-1", ha.url, secret = "s3cret")
            ),
            imported
        )
        // An address the phone has keeps its id, so its own secret stays with it from then on.
        assertEquals(ha.id, old.destinationsOnImport(listOf(ha), null, backupHasSecrets = true)!![1].id)
    }

    @Test
    fun anOldFileWithoutSecretsKeepsWhatEachAddressHasOnThePhone() {
        val old = SectionConfig(webhookUrls = listOf(stack.url, ha.url, "https://new.example/hook"))

        val imported = old.destinationsOnImport(listOf(stack, ha), "section-secret", backupHasSecrets = false) { "new" }!!

        assertEquals(listOf(stack, ha), imported.take(2))
        // New to the phone: the section's secret, and none of the headers set for the others.
        assertEquals(Destination("new", "https://new.example/hook", secret = "section-secret"), imported[2])
    }

    @Test
    fun aFileWithoutSecretsKeepsTheSecretsOnThePhoneByIdOrAddress() {
        val file = backup(exported(listOf(stack, ha))).withoutSecrets()
        // On the phone the stack's address was edited since, and Home Assistant has another id.
        val onPhone = listOf(stack.copy(url = "http://stack.example/health"), ha.copy(id = "phone-ha"))

        val imported = file.health.destinationsOnImport(onPhone, "section-secret", file.containsSecrets())!!

        assertEquals(listOf(stack, ha), imported)
    }

    @Test
    fun theDestinationsWinOverTheSectionKeys() {
        val file = exported(listOf(stack, ha)).copy(headers = mapOf("X-Leak" to "no"), signingSecret = "no")

        assertEquals(listOf(stack, ha), file.destinationsOnImport(emptyList(), null, backupHasSecrets = true))
    }

    @Test
    fun anUnsendableHeaderOfADestinationIsLeftOut() {
        val broken = stack.copy(headers = mapOf("Bad Header" to "x", "X-Api-Key" to "k"))
        val file = backup(exported(listOf(broken)))

        assertTrue(ImportNote.UNSENDABLE_HEADERS_SKIPPED in file.importNotes())
        assertEquals(mapOf("X-Api-Key" to "k"), file.forThisPhone().health.destinations!!.single().headers)
    }

    @Test
    fun theSummaryCountsDestinations() {
        assertEquals(2, backup(exported(listOf(stack, ha))).summarise(emptySet()).healthWebhooks)
    }

    @Test
    fun aDestinationThePhoneKeepsUnsignedStaysUnsigned() {
        val unsignedOnPhone = listOf(stack, ha.copy(secret = null))

        val fromNew = backup(exported(listOf(stack, ha))).withoutSecrets()
        assertNull(fromNew.health.destinationsOnImport(unsignedOnPhone, "section-secret", backupHasSecrets = false)!![1].secret)

        val fromOld = SectionConfig(webhookUrls = listOf(stack.url, ha.url))
        assertNull(fromOld.destinationsOnImport(unsignedOnPhone, "section-secret", backupHasSecrets = false)!![1].secret)
    }

    @Test
    fun aSecondEntryWithAnIdAlreadyUsedGetsANewOne() {
        val twin = DestinationConfig(id = stack.id, url = "https://twin.example/hook", signingSecret = "twin-secret")
        val file = exported(listOf(stack)).let { it.copy(destinations = it.destinations!! + twin) }

        val imported = file.destinationsOnImport(emptyList(), null, backupHasSecrets = true) { "fresh" }!!

        // Both kept, each with its own secret: sharing an id would have them share one.
        assertEquals(listOf(stack.id, "fresh"), imported.map { it.id })
        assertEquals(listOf("stack-secret", "twin-secret"), imported.map { it.secret })
    }
}
