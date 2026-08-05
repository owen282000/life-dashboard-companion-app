package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(WebhookSupport.isRetryable(null))  // network error, no HTTP response
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
}
