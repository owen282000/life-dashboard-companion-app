package com.owen282000.lifedashboard

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException

/** The last error at the end of the failure notification: short, and never a path, a query or a secret. */
class FailureReasonTest {

    @Test
    fun anHttpStatusKeepsOnlyItsCode() {
        assertEquals("HTTP 502", FailureReason.shorten("HTTP 502: Bad Gateway"))
        assertEquals("HTTP 401", FailureReason.of(PayloadRefusedException(401, "HTTP 401: Unauthorized")))
    }

    @Test
    fun aRedirectThatWasNotFollowedIsItsStatus() {
        assertEquals("HTTP 301", FailureReason.shorten(WebhookSupport.redirectMessage(301, "elsewhere.example.com")))
    }

    @Test
    fun aUrlKeepsOnlyItsHost() {
        val reason = FailureReason.shorten(
            "unexpected end of stream on https://user:hunter2@ha.example.com:8123/api/webhook/abc123?token=s3cret#x"
        )
        assertEquals("unexpected end of stream on ha.example.com", reason)
    }

    @Test
    fun aHeaderValueOkHttpQuotesIsLeftOut() {
        val reason = FailureReason.shorten("Unexpected char 0x0a at 6 in X-Api-Key value: sk-live-1234\n")
        assertEquals("Unexpected char 0x0a at 6 in X-Api-Key", reason)
    }

    @Test
    fun theClassNameOfAWrappedExceptionIsLeftOut() {
        val reason = FailureReason.shorten(
            "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."
        )
        assertEquals("Trust anchor for certification path not found", reason)
    }

    @Test
    fun onlyTheFirstSentenceIsKept() {
        assertEquals("Plain HTTP is blocked", FailureReason.shorten(WebhookSupport.CLEARTEXT_BLOCKED_MESSAGE))
    }

    @Test
    fun aLongReasonIsCutAtAWord() {
        val reason = FailureReason.shorten("word ".repeat(40).trim())!!
        assertTrue(reason.length <= FailureReason.MAX_LENGTH + 1)
        assertTrue(reason.endsWith("word…"))
    }

    @Test
    fun anExceptionWithoutAMessageIsNamedByItsClass() {
        assertEquals("SocketTimeoutException", FailureReason.of(SocketTimeoutException()))
        assertEquals("timeout", FailureReason.of(SocketTimeoutException("timeout")))
    }

    @Test
    fun nothingToSayIsNull() {
        assertNull(FailureReason.of(null))
        assertNull(FailureReason.shorten(null))
        assertNull(FailureReason.shorten("   "))
    }

    @Test
    fun aRefusedDeliveryGivesItsStatusAndNotItsUrl() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse.Builder().code(404).build())
            val url = server.url("/api/webhook/secret-id?token=abc").toString()

            val result = WebhookManager(webhookUrls = listOf(url), allowHttpOverride = true).postData("{}")

            assertTrue(result.isFailure)
            assertEquals("HTTP 404", FailureReason.of(result.exceptionOrNull()))
        } finally {
            server.close()
        }
    }
}
