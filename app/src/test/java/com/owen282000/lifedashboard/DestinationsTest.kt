package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of a section's destinations (P2-13): migration, pairing, credentials, and what an old reader of a backup gets. */
class DestinationsTest {

    private val typed = "https://stack.example/health"
    private val paired = "http://homeassistant.local:8123/api/webhook/abc"
    private val headers = mapOf("X-Api-Key" to "k")

    @Test
    fun migrationGivesEveryUrlTheSectionSecretAndHeaders() {
        // What 1.27.0 stored: one secret and one set of headers, and pairing's mark on its address.
        val derived = Destinations.derive(LogType.HEALTH_CONNECT, listOf(typed, paired), "s3cret", headers, setOf(paired))

        assertEquals(listOf(typed, paired), derived.map { it.url })
        assertEquals(listOf("s3cret", "s3cret"), derived.map { it.secret })
        // The paired address got no headers before the update, and gets none after it.
        assertEquals(listOf(headers, emptyMap()), derived.map { it.headers })
        assertEquals(listOf(Destination.FORMAT_DEFAULT, Destination.FORMAT_DEFAULT), derived.map { it.format })
    }

    @Test
    fun derivedIdsStayTheSameOnEveryRead() {
        val first = Destinations.derive(LogType.HEALTH_CONNECT, listOf(typed), null, emptyMap(), emptySet())
        val again = Destinations.derive(LogType.HEALTH_CONNECT, listOf(typed), "later", headers, emptySet())
        assertEquals(first.single().id, again.single().id)
        // The same address in the other section is another destination.
        assertNotEquals(first.single().id, Destinations.derive(LogType.SCREEN_TIME, listOf(typed), null, emptyMap(), emptySet()).single().id)
        // No secret is no secret, not an empty one.
        assertNull(Destinations.derive(LogType.HEALTH_CONNECT, listOf(typed), " ", emptyMap(), emptySet()).single().secret)
    }

    @Test
    fun urlWithCredentialsIsRejected() {
        assertEquals(UrlCredential(null), Destinations.credentialIn("https://user:pass@example.com/hook"))
        assertEquals(UrlCredential(null), Destinations.credentialIn("https://token@example.com/hook"))
        for (name in listOf("password", "token", "access_token", "apikey", "apiKey", "api_key", "secret", "auth", "TOKEN")) {
            assertEquals(name, UrlCredential(name), Destinations.credentialIn("https://example.com/hook?a=1&$name=x"))
        }
        assertEquals(UrlCredential("token"), Destinations.credentialIn("https://example.com/hook?%74oken=x"))

        // What is not a credential stays allowed.
        assertNull(Destinations.credentialIn("https://example.com/hook"))
        assertNull(Destinations.credentialIn("https://example.com/hook?source=phone&page=2"))
        // Single letters and key are used for other things than credentials.
        assertNull(Destinations.credentialIn("https://example.com/blog?p=1&u=me&key=health"))
        // IFTTT keeps its key in the path.
        assertNull(Destinations.credentialIn("https://maker.ifttt.com/trigger/health/with/key/abc123"))
        assertNull(Destinations.credentialIn("https://example.com/hook#token=x"))
        assertNull(Destinations.credentialIn(paired))
        // An @ in the path is not a user name.
        assertNull(Destinations.credentialIn("https://example.com/users/@me"))
    }

    @Test
    fun anAppsScriptUrlKeepsItsToken() {
        // Apps Script cannot read headers; docs/webhook.md tells people to put a token in the URL.
        assertNull(Destinations.credentialIn("https://script.google.com/macros/s/AKfy/exec?token=abc"))
        // A Google Workspace deployment, which the redirect rule takes too.
        val workspace = "https://script.google.com/a/macros/example.com/s/AKfy/exec?token=abc"
        assertNull(Destinations.credentialIn(workspace))
        assertTrue(WebhookSupport.isAppsScriptWebApp(workspace))
        // Only there: another Google host, or another path, is checked like any address.
        assertEquals(UrlCredential("token"), Destinations.credentialIn("https://script.google.com/a/exec?token=abc"))
        assertEquals(UrlCredential("token"), Destinations.credentialIn("https://script.googleusercontent.com/macros/echo?token=abc"))
        // A user name is never fine.
        assertEquals(UrlCredential(null), Destinations.credentialIn("https://me@script.google.com/macros/s/AKfy/exec"))
    }

    @Test
    fun legacyHeadersOnlyWhenAllDestinationsShareThem() {
        val stack = Destination("a", typed, secret = "s1", headers = headers)
        val ha = Destination("b", paired, secret = "s1")

        // One set of headers, and an address without: the section keys say exactly that.
        assertEquals(LegacySection(headers, listOf(paired), "s1"), Destinations.legacy(listOf(stack, ha), null))

        // No headers anywhere: nothing to hold back, so no address is listed.
        assertEquals(LegacySection(emptyMap(), emptyList(), "s1"), Destinations.legacy(listOf(ha, stack.copy(headers = emptyMap())), null))

        // Two different sets: none, and every address listed, so an old reader sends neither.
        val other = Destination("c", "https://other.example/hook", secret = "s1", headers = mapOf("Authorization" to "Bearer t"))
        assertEquals(LegacySection(emptyMap(), listOf(typed, paired, other.url), "s1"), Destinations.legacy(listOf(stack, ha, other), null))
    }

    @Test
    fun legacySecretOnlyWhenAllDestinationsShareIt() {
        val stack = Destination("a", typed, secret = "stack-secret")
        val ha = Destination("b", paired, secret = "ha-secret")

        assertNull(Destinations.legacy(listOf(stack, ha), "stack-secret").secret)
        assertNull("one signed and one not is not shared", Destinations.legacy(listOf(stack, ha.copy(secret = null)), null).secret)
        assertEquals("stack-secret", Destinations.legacy(listOf(stack, ha.copy(secret = "stack-secret")), null).secret)
        // A section without destinations still carries its own secret.
        assertEquals("kept", Destinations.legacy(emptyList(), "kept").secret)
    }

    @Test
    fun pairingAnAddressThatIsThereKeepsItsHeadersAndId() {
        val current = listOf(Destination("a", typed, secret = "old", headers = headers), Destination("b", paired, secret = "ha"))

        assertEquals(
            listOf(Destination("a", typed, secret = "new", headers = headers), current[1]),
            Destinations.paired(current, typed, "new") { error("no new id for an address that is there") }
        )
        assertEquals(current + Destination("n", "https://new.example/hook", secret = "new"), Destinations.paired(current, "https://new.example/hook", "new") { "n" })
    }

    @Test
    fun aButtonNamesTheHostAndOnlyAsMuchMoreAsItNeeds() {
        val a = Destination("a", "https://stack.example/health")
        val b = Destination("b", "http://homeassistant.local:8123/api/webhook/abc")
        val c = Destination("c", "https://stack.example/screen")
        assertEquals("homeassistant.local:8123", Destinations.label(b, listOf(a, b)))
        assertEquals("stack.example", Destinations.label(a, listOf(a, b)))
        // Two on one host and port: the path tells them apart.
        assertEquals("stack.example/health", Destinations.label(a, listOf(a, b, c)))
        assertEquals("stack.example/screen", Destinations.label(c, listOf(a, b, c)))
    }

    @Test
    fun theHostDecidesWhetherAnEditIsTheSameReceiver() {
        assertTrue(Destinations.sameHost("http://stack.example/health", "https://STACK.example:8443/other"))
        assertFalse(Destinations.sameHost("https://stack.example/health", "https://evil.example/health"))
        assertFalse(Destinations.sameHost("https://", "https://"))
    }
}
