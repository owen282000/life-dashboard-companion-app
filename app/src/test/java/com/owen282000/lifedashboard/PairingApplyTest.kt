package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePairingStore(
    var health: List<Destination> = emptyList(),
    var screenTime: List<Destination> = emptyList(),
    var plainHttpAllowed: Boolean = false,
    /** The secure storage is not ready: nothing is stored. */
    var refuse: Boolean = false
) : PairingStore {
    val healthUrls get() = health.map { it.url }
    val screenTimeUrls get() = screenTime.map { it.url }

    override fun destinations(source: PairingSource) = if (source == PairingSource.HEALTH) health else screenTime

    override fun setDestinations(source: PairingSource, destinations: List<Destination>): Boolean {
        if (refuse) return false
        if (source == PairingSource.HEALTH) health = destinations else screenTime = destinations
        return true
    }

    override fun setAllowPlainHttp(enabled: Boolean) {
        plainHttpAllowed = enabled
    }
}

class PairingApplyTest {

    private val link = PairingLink(
        url = "http://192.168.10.138:8123/api/webhook/abc",
        secret = "s3cret",
        name = "Home Assistant",
        sources = setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME)
    )
    private val both = PairingChoice(health = true, screenTime = true, allowPlainHttp = true)
    private var ids = 0
    private val newId = { "new-${ids++}" }
    private val stack = Destination(id = "stack", url = "https://stack.example/health", secret = "stack-secret", headers = mapOf("X-Api-Key" to "k"))

    @Test
    fun aFreshInstallGetsTheAddressAndTheSecret() {
        val store = FakePairingStore()

        val written = PairingApply.apply(link, both, store, newId).written

        assertEquals(setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME), written)
        assertEquals(listOf(link.url), store.healthUrls)
        assertEquals(listOf(link.url), store.screenTimeUrls)
        assertEquals("s3cret", store.health.single().secret)
        assertEquals("s3cret", store.screenTime.single().secret)
    }

    @Test
    fun pairingSetsSecretOnlyOnThePairedDestination() {
        // The stack paired first, Home Assistant second: before P2-13 this re-signed the stack
        // with Home Assistant's secret and the stack answered 401 from then on.
        val store = FakePairingStore(health = listOf(stack), screenTime = listOf(stack))

        PairingApply.apply(link, both, store, newId)

        for (section in listOf(store.health, store.screenTime)) {
            assertEquals(stack, section.first())
            val paired = section.last()
            assertEquals(link.url, paired.url)
            assertEquals("s3cret", paired.secret)
        }

        // Pairing the stack again with a rotated secret touches the stack alone.
        val rotated = link.copy(url = stack.url, secret = "rotated")
        PairingApply.apply(rotated, both, store, newId)
        assertEquals(stack.copy(secret = "rotated"), store.health.first())
        assertEquals("s3cret", store.health.last().secret)
        assertEquals(2, store.health.size)
    }

    @Test
    fun aPairingTheSecureStorageCannotKeepIsReportedAndChangesNothing() {
        val store = FakePairingStore(health = listOf(stack), refuse = true)

        val result = PairingApply.apply(link, both, store, newId)

        assertEquals(setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME), result.refused)
        assertTrue(result.written.isEmpty())
        assertEquals(listOf(stack), store.health)
        assertFalse("nothing paired, no permission change", store.plainHttpAllowed)
    }

    @Test
    fun anExistingReceiverIsKept() {
        // Someone feeding their own server as well should not lose it.
        val store = FakePairingStore(health = listOf(stack))

        PairingApply.apply(link, both, store, newId)

        assertEquals(listOf(stack.url, link.url), store.healthUrls)
    }

    @Test
    fun theSameAddressTwiceDoesNotDuplicate() {
        val store = FakePairingStore(health = destinationsOf(listOf(link.url)))

        PairingApply.apply(link, both, store, newId)

        assertEquals(listOf(link.url), store.healthUrls)
    }

    @Test
    fun onlyTheSectionsTheUserPickedAreTouched() {
        val store = FakePairingStore()

        val written = PairingApply.apply(
            link,
            PairingChoice(health = false, screenTime = true, allowPlainHttp = true),
            store
        ).written

        assertEquals(setOf(PairingSource.SCREEN_TIME), written)
        assertTrue(store.health.isEmpty())
        assertEquals(listOf(link.url), store.screenTimeUrls)
    }

    @Test
    fun aSectionTheReceiverDoesNotAcceptIsNotWrittenEvenIfAsked() {
        val healthOnly = link.copy(sources = setOf(PairingSource.HEALTH))
        val store = FakePairingStore()

        val written = PairingApply.apply(healthOnly, both, store).written

        assertEquals(setOf(PairingSource.HEALTH), written)
        assertTrue(store.screenTimeUrls.isEmpty())
    }

    @Test
    fun plainHttpIsOnlyEnabledWhenAskedAndNeeded() {
        val store = FakePairingStore()
        PairingApply.apply(link, both, store, newId)
        assertTrue("an internal HA address is http://", store.plainHttpAllowed)

        val declined = FakePairingStore()
        PairingApply.apply(link, both.copy(allowPlainHttp = false), declined)
        assertFalse(declined.plainHttpAllowed)

        val https = FakePairingStore()
        PairingApply.apply(link.copy(url = "https://ha.example.com/hook"), both, https)
        assertFalse("nothing to allow for https", https.plainHttpAllowed)
    }

    @Test
    fun nothingSelectedWritesNothingAtAll() {
        val store = FakePairingStore()

        val written = PairingApply.apply(
            link,
            PairingChoice(health = false, screenTime = false, allowPlainHttp = true),
            store
        ).written

        assertTrue(written.isEmpty())
        assertTrue(store.healthUrls.isEmpty())
        assertTrue(store.screenTimeUrls.isEmpty())
        assertFalse("no pairing, no permission change", store.plainHttpAllowed)
    }

    @Test
    fun thePreviewSaysWhatWillHappen() {
        assertEquals(
            SectionChange(addsUrl = true, setsSecret = false, replacesSecret = false),
            PairingApply.preview(link, emptyList())
        )
        // The address already there keeps its own secret, which the dialog says.
        assertEquals(
            SectionChange(addsUrl = true, setsSecret = false, replacesSecret = false, othersKeepTheirOwn = listOf(stack.url)),
            PairingApply.preview(link, listOf(stack))
        )
        // Already paired with this receiver: nothing to announce.
        val unchanged = PairingApply.preview(link, destinationsOf(listOf(link.url), secret = link.secret))
        assertEquals(SectionChange(addsUrl = false, setsSecret = false, replacesSecret = false), unchanged)
        assertTrue(unchanged.changesNothing)
        // An address that had no secret gets one, which is a change but replaces nothing.
        val signed = PairingApply.preview(link, destinationsOf(listOf(link.url)))
        assertFalse(signed.changesNothing)
        assertFalse(signed.replacesSecret)
    }

    @Test
    fun aRepairOfTheSameReceiverNamesNoOtherAddress() {
        // Pairing again with a rotated secret: the paired address itself is not an "other" one.
        val change = PairingApply.preview(link, destinationsOf(listOf(link.url), secret = "rotated-away"))
        assertTrue(change.replacesSecret)
        assertEquals(emptyList<String>(), change.othersKeepTheirOwn)
    }

    @Test
    fun theDialogOffersWhatTheReceiverAccepts() {
        assertEquals(
            setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME),
            PairingApply.offered(link)
        )
        assertEquals(
            setOf(PairingSource.SCREEN_TIME),
            PairingApply.offered(link.copy(sources = setOf(PairingSource.SCREEN_TIME)))
        )
    }

    @Test
    fun aPairedAddressGetsNoCustomHeaders() {
        // A phishing code the user confirms must not receive the API keys typed for the others.
        val store = FakePairingStore(health = listOf(stack), screenTime = listOf(stack))

        PairingApply.apply(link, both, store, newId)

        for (section in listOf(store.health, store.screenTime)) {
            assertEquals(mapOf("X-Api-Key" to "k"), section.first { it.url == stack.url }.headers)
            assertEquals(emptyMap<String, String>(), section.first { it.url == link.url }.headers)
        }
    }

    @Test
    fun anAddressTypedInBeforeKeepsItsHeaders() {
        // Pairing adds nothing here, so it takes nothing away either.
        val typed = Destination(id = "typed", url = link.url, headers = mapOf("X-Api-Key" to "k"))
        val store = FakePairingStore(health = listOf(typed))

        PairingApply.apply(link, both.copy(screenTime = false), store, newId)

        assertEquals(listOf(typed.copy(secret = link.secret)), store.health)
    }
}
