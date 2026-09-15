package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePairingStore(
    var healthUrls: List<String> = emptyList(),
    var healthSecret: String? = null,
    var screenTimeUrls: List<String> = emptyList(),
    var screenTimeSecret: String? = null,
    var plainHttpAllowed: Boolean = false
) : PairingStore {
    override fun health() = SectionWebhook(healthUrls, healthSecret)
    override fun screenTime() = SectionWebhook(screenTimeUrls, screenTimeSecret)

    override fun setHealth(urls: List<String>, secret: String) {
        healthUrls = urls
        healthSecret = secret
    }

    override fun setScreenTime(urls: List<String>, secret: String) {
        screenTimeUrls = urls
        screenTimeSecret = secret
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

    @Test
    fun aFreshInstallGetsTheAddressAndTheSecret() {
        val store = FakePairingStore()

        val written = PairingApply.apply(link, both, store)

        assertEquals(setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME), written)
        assertEquals(listOf(link.url), store.healthUrls)
        assertEquals(listOf(link.url), store.screenTimeUrls)
        assertEquals("s3cret", store.healthSecret)
        assertEquals("s3cret", store.screenTimeSecret)
    }

    @Test
    fun anExistingReceiverIsKept() {
        // Someone feeding their own server as well should not lose it.
        val store = FakePairingStore(
            healthUrls = listOf("https://my.server/hook"),
            healthSecret = "old"
        )

        PairingApply.apply(link, both, store)

        assertEquals(listOf("https://my.server/hook", link.url), store.healthUrls)
    }

    @Test
    fun theSameAddressTwiceDoesNotDuplicate() {
        val store = FakePairingStore(healthUrls = listOf(link.url))

        PairingApply.apply(link, both, store)

        assertEquals(listOf(link.url), store.healthUrls)
    }

    @Test
    fun onlyTheSectionsTheUserPickedAreTouched() {
        val store = FakePairingStore()

        val written = PairingApply.apply(
            link,
            PairingChoice(health = false, screenTime = true, allowPlainHttp = true),
            store
        )

        assertEquals(setOf(PairingSource.SCREEN_TIME), written)
        assertTrue(store.healthUrls.isEmpty())
        assertEquals(null, store.healthSecret)
        assertEquals(listOf(link.url), store.screenTimeUrls)
    }

    @Test
    fun aSectionTheReceiverDoesNotAcceptIsNotWrittenEvenIfAsked() {
        val healthOnly = link.copy(sources = setOf(PairingSource.HEALTH))
        val store = FakePairingStore()

        val written = PairingApply.apply(healthOnly, both, store)

        assertEquals(setOf(PairingSource.HEALTH), written)
        assertTrue(store.screenTimeUrls.isEmpty())
    }

    @Test
    fun plainHttpIsOnlyEnabledWhenAskedAndNeeded() {
        val store = FakePairingStore()
        PairingApply.apply(link, both, store)
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
        )

        assertTrue(written.isEmpty())
        assertTrue(store.healthUrls.isEmpty())
        assertTrue(store.screenTimeUrls.isEmpty())
        assertFalse("no pairing, no permission change", store.plainHttpAllowed)
    }

    @Test
    fun thePreviewSaysWhatWillHappen() {
        assertEquals(
            SectionChange(addsUrl = true, replacesSecret = false),
            PairingApply.preview(link, SectionWebhook(emptyList(), null))
        )
        assertEquals(
            SectionChange(addsUrl = true, replacesSecret = true),
            PairingApply.preview(link, SectionWebhook(listOf("https://other/hook"), "old"))
        )
        // Already paired with this receiver: nothing to announce.
        val unchanged = PairingApply.preview(link, SectionWebhook(listOf(link.url), link.secret))
        assertEquals(SectionChange(addsUrl = false, replacesSecret = false), unchanged)
        assertTrue(unchanged.changesNothing)
        // The same secret is not a replacement, even when the address is new.
        assertEquals(
            SectionChange(addsUrl = true, replacesSecret = false),
            PairingApply.preview(link, SectionWebhook(listOf("https://other/hook"), link.secret))
        )
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
}
