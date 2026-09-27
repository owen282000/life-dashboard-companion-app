package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookSupportTest {

    @Test
    fun signatureMatchesKnownHmacSha256TestVector() {
        // Well-known HMAC-SHA256 vector: key "key", message "The quick brown fox jumps over the lazy dog"
        val signature = WebhookSupport.signature(
            payload = "The quick brown fox jumps over the lazy dog",
            secret = "key"
        )
        assertEquals(
            "sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            signature
        )
    }

    @Test
    fun signatureChangesWithPayloadAndSecret() {
        val base = WebhookSupport.signature("payload", "secret")
        assertEquals(base, WebhookSupport.signature("payload", "secret"))
        assertFalse(base == WebhookSupport.signature("payload2", "secret"))
        assertFalse(base == WebhookSupport.signature("payload", "secret2"))
    }

    @Test
    fun transientFailuresAreRetryable() {
        assertTrue(WebhookSupport.isRetryable(null)) // network error, no HTTP response
        assertTrue(WebhookSupport.isRetryable(408))
        assertTrue(WebhookSupport.isRetryable(429))
        assertTrue(WebhookSupport.isRetryable(500))
        assertTrue(WebhookSupport.isRetryable(503))
        assertTrue(WebhookSupport.isRetryable(599))
    }

    @Test
    fun permanentClientErrorsAreNotRetryable() {
        assertFalse(WebhookSupport.isRetryable(400))
        assertFalse(WebhookSupport.isRetryable(401))
        assertFalse(WebhookSupport.isRetryable(403))
        assertFalse(WebhookSupport.isRetryable(404))
        assertFalse(WebhookSupport.isRetryable(410))
    }

    @Test
    fun onlyRefusalsOfThePayloadItselfAreDropped() {
        listOf(400, 413, 422).forEach { assertTrue("$it", WebhookSupport.refusesPayload(it)) }
        // Setup errors a correction can fix, and transient ones, stay queued.
        listOf(null, 401, 403, 404, 405, 407, 408, 410, 429, 500, 503).forEach {
            assertFalse("$it", WebhookSupport.refusesPayload(it))
        }
    }

    // Plain HTTP opt-in (issue #51)

    @Test
    fun httpIsBlockedUnlessTheUserOptedIn() {
        assertEquals(WebhookSupport.CLEARTEXT_BLOCKED_MESSAGE,
            WebhookSupport.cleartextBlockReason("http://homeassistant.local:8123/api/webhook/x", allowHttp = false))
        assertNotNull(WebhookSupport.cleartextBlockReason("HTTP://192.168.1.10/hook", allowHttp = false))
        assertNull(WebhookSupport.cleartextBlockReason("http://192.168.1.10/hook", allowHttp = true))
    }

    @Test
    fun httpsIsNeverBlocked() {
        assertNull(WebhookSupport.cleartextBlockReason("https://example.com/hook", allowHttp = false))
        assertNull(WebhookSupport.cleartextBlockReason("https://example.com/hook", allowHttp = true))
    }

    @Test
    fun aRedirectMessageNamesTheTargetHost() {
        val message = WebhookSupport.redirectMessage(307, "evil.example")
        assertTrue(message.startsWith("HTTP 307"))
        assertTrue(message.contains("to evil.example"))
        // Without a Location header it still says what happened.
        assertTrue(WebhookSupport.redirectMessage(302, null).startsWith("HTTP 302: redirect not followed"))
    }

    @Test
    fun aRedirectOnTheSameHostIsFollowed() {
        assertEquals(
            "https://ha.example/api/webhook/abc/",
            WebhookSupport.followableRedirect("https://ha.example/api/webhook/abc", "/api/webhook/abc/", allowHttp = false)
        )
        assertEquals(
            "https://HA.example/next",
            WebhookSupport.followableRedirect("https://ha.example/hook", "https://HA.example/next", allowHttp = false)
        )
    }

    @Test
    fun aRedirectFromHttpUpToHttpsOnTheSameHostIsFollowed() {
        assertEquals(
            "https://ha.example/hook",
            WebhookSupport.followableRedirect("http://ha.example/hook", "https://ha.example/hook", allowHttp = true)
        )
    }

    @Test
    fun aRedirectElsewhereIsNotFollowed() {
        // Another host, another port, a step down to http, and plain http without the opt-in.
        assertNull(WebhookSupport.followableRedirect("https://ha.example/hook", "https://evil.example/hook", allowHttp = true))
        assertNull(WebhookSupport.followableRedirect("https://ha.example/hook", "https://ha.example:8443/hook", allowHttp = true))
        assertNull(WebhookSupport.followableRedirect("https://ha.example/hook", "http://ha.example/hook", allowHttp = true))
        assertNull(WebhookSupport.followableRedirect("http://10.0.0.2:8123/hook", "/other", allowHttp = false))
        assertNull(WebhookSupport.followableRedirect("https://ha.example/hook", null, allowHttp = false))
        assertNull(WebhookSupport.followableRedirect("https://ha.example/hook", "ftp://ha.example/hook", allowHttp = false))
    }
}
