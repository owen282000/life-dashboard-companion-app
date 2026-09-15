package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {

    private val webhookUrl = "http://192.168.10.138:8123/api/webhook/" + "a".repeat(64)
    private val secret = "b".repeat(64)

    /**
     * The exact string the Home Assistant integration's pairing_url() produces, copied from
     * its test_pairing.py. Change one side and this fails, which is the point: the two
     * repositories have to agree on the format.
     */
    private val knownGood =
        "https://owen282000.github.io/life-dashboard-companion-app/pair" +
            "#v=1" +
            "&url=http%3A%2F%2F192.168.10.138%3A8123%2Fapi%2Fwebhook%2F" + "a".repeat(64) +
            "&secret=" + "b".repeat(64) +
            "&name=Home%20Assistant" +
            "&sources=health_connect%2Cscreen_time"

    private fun ok(text: String): PairingLink {
        val parsed = PairingLinks.parse(text)
        assertTrue("expected a usable link, got $parsed", parsed is PairingParse.Ok)
        return (parsed as PairingParse.Ok).link
    }

    private fun problem(text: String): PairingProblem {
        val parsed = PairingLinks.parse(text)
        assertTrue("expected an invalid link, got $parsed", parsed is PairingParse.Invalid)
        return (parsed as PairingParse.Invalid).reason
    }

    @Test
    fun readsTheIntegrationsOwnLink() {
        val link = ok(knownGood)
        assertEquals(webhookUrl, link.url)
        assertEquals(secret, link.secret)
        assertEquals("Home Assistant", link.name)
        assertEquals(setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME), link.sources)
        assertEquals("192.168.10.138:8123", link.host)
        assertTrue(link.isPlainHttp)
    }

    @Test
    fun readsTheCustomSchemeForm() {
        val link = ok("lifedashboard://pair#v=1&url=https%3A%2F%2Fha.example.com%2Fhook&secret=xyz")
        assertEquals("https://ha.example.com/hook", link.url)
        assertEquals("xyz", link.secret)
        assertFalse(link.isPlainHttp)
        assertEquals("ha.example.com", link.host)
    }

    @Test
    fun aTrailingSlashBeforeTheFragmentIsFine() {
        // The landing page's own address ends in a slash, so a link built from it does too.
        val link = ok(
            "https://owen282000.github.io/life-dashboard-companion-app/pair/#v=1" +
                "&url=https%3A%2F%2Fha.example.com%2Fhook&secret=xyz"
        )
        assertEquals("https://ha.example.com/hook", link.url)
    }

    @Test
    fun anAbsentSourcesFieldMeansBoth() {
        val link = ok("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz")
        assertEquals(setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME), link.sources)
        assertNull(link.name)
    }

    @Test
    fun aReceiverCanAskForOneSectionOnly() {
        val link = ok(
            "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz&sources=health_connect"
        )
        assertEquals(setOf(PairingSource.HEALTH), link.sources)
    }

    @Test
    fun unknownSourcesAreIgnoredButKnownOnesSurvive() {
        val link = ok(
            "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz" +
                "&sources=screen_time%2Cmoon_phase"
        )
        assertEquals(setOf(PairingSource.SCREEN_TIME), link.sources)
    }

    @Test
    fun aReceiverThatAcceptsNothingWeSendIsRefused() {
        assertEquals(
            PairingProblem.NoUsableSource,
            problem("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz&sources=moon_phase")
        )
    }

    @Test
    fun anEncodedAmpersandInsideTheUrlStaysPartOfIt() {
        // Decoding the whole fragment first would split this URL in two.
        val link = ok(
            "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fhook%3Fx%3D1%26y%3D2&secret=xyz"
        )
        assertEquals("https://a.b/hook?x=1&y=2", link.url)
    }

    @Test
    fun aSecretWithAwkwardCharactersSurvives() {
        // A cloudhook URL and a base64 secret both carry + and /.
        val link = ok(
            "lifedashboard://pair#v=1&url=https%3A%2F%2Fhooks.nabu.casa%2FgAAAAAB%2Babc&secret=x%2By%2Fz%3D"
        )
        assertEquals("https://hooks.nabu.casa/gAAAAAB+abc", link.url)
        assertEquals("x+y/z=", link.secret)
    }

    @Test
    fun aLiteralPlusIsNotTurnedIntoASpace() {
        // URLDecoder would; a pairing link is not a form body.
        val link = ok("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=x+y")
        assertEquals("x+y", link.secret)
    }

    @Test
    fun aLiteralSpaceInTheNameIsAccepted() {
        // Chrome's address bar hands back "name=Home Assistant" while leaving every other
        // escape intact, and a pasted link should not be refused over that.
        val link = ok(
            "https://owen282000.github.io/life-dashboard-companion-app/pair#v=1" +
                "&url=https%3A%2F%2Fa.b%2Fc&secret=xyz&name=Home Assistant"
        )
        assertEquals("Home Assistant", link.name)
    }

    @Test
    fun anotherVersionIsRefusedRatherThanGuessedAt() {
        assertEquals(
            PairingProblem.UnsupportedVersion,
            problem("lifedashboard://pair#v=2&url=https%3A%2F%2Fa.b%2Fc&secret=xyz")
        )
        assertEquals(
            PairingProblem.UnsupportedVersion,
            problem("lifedashboard://pair#url=https%3A%2F%2Fa.b%2Fc&secret=xyz")
        )
    }

    @Test
    fun aMissingHalfIsRefused() {
        assertEquals(PairingProblem.Incomplete, problem("lifedashboard://pair#v=1&secret=xyz"))
        assertEquals(
            PairingProblem.Incomplete,
            problem("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc")
        )
        assertEquals(
            PairingProblem.Incomplete,
            problem("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=")
        )
        assertEquals(PairingProblem.Incomplete, problem("lifedashboard://pair"))
    }

    @Test
    fun anAddressWeCannotPostToIsRefused() {
        assertEquals(
            PairingProblem.Incomplete,
            problem("lifedashboard://pair#v=1&url=ftp%3A%2F%2Fa.b%2Fc&secret=xyz")
        )
        // The URL list is stored comma-joined, so a comma would split one URL into two.
        assertEquals(
            PairingProblem.Incomplete,
            problem("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc%2Cd&secret=xyz")
        )
        // Whitespace inside the address is a mangled link, not an address.
        assertEquals(
            PairingProblem.Incomplete,
            problem("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2F%20c&secret=xyz")
        )
    }

    @Test
    fun otherLinksAreLeftAlone() {
        // A scanner keeps scanning and an unrelated VIEW intent is not hijacked.
        for (text in listOf(
            "https://example.com/pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz",
            "https://owen282000.github.io/other/pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz",
            "otherapp://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz",
            "https://owen282000.github.io/life-dashboard-companion-app/",
            "WIFI:S:MyNetwork;T:WPA;P:hunter2;;",
            "just some text",
            "",
            null
        )) {
            assertEquals("should ignore $text", PairingParse.NotAPairingLink, PairingLinks.parse(text))
        }
    }

    @Test
    fun theHostIsShownWithoutAPortWhenThereIsNone() {
        val link = ok("lifedashboard://pair#v=1&url=https%3A%2F%2Fha.example.com%2Fhook&secret=xyz")
        assertEquals("ha.example.com", link.host)
    }

    @Test
    fun aLongNameIsTruncatedAndControlCharactersDropped() {
        val link = ok(
            "lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=xyz&name=" +
                "A".repeat(80) + "%0A"
        )
        assertEquals(64, link.name?.length)
    }

    @Test
    fun anOversizedSecretIsRefused() {
        assertEquals(
            PairingProblem.Incomplete,
            problem("lifedashboard://pair#v=1&url=https%3A%2F%2Fa.b%2Fc&secret=" + "x".repeat(513))
        )
    }
}
