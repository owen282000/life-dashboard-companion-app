package com.owen282000.lifedashboard

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * A redirect is never followed: the body, the signature and the custom headers go to the
 * configured URL only, and the delivery fails with a message that names the target host.
 * Runs the client WebhookManager builds (without a certificate) against two local servers.
 */
class WebhookRedirectTest {

    private val receiver = MockWebServer()
    private val elsewhere = MockWebServer()

    @Before
    fun start() {
        receiver.start(InetAddress.getByName("127.0.0.1"), 0)
        elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        receiver.close()
        elsewhere.close()
    }

    private fun post(): okhttp3.Response {
        val request = Request.Builder()
            .url(receiver.url("/hook"))
            .post("""{"test":true}""".toRequestBody())
            .header("Authorization", "Bearer token")
            .header(WebhookSupport.SIGNATURE_HEADER, "sha256=abc")
            .build()
        return WebhookManager.baseClientBuilder().build().newCall(request).execute()
    }

    @Test
    fun aTemporaryRedirectToAnotherHostIsNotFollowed() {
        // Another origin: the same address on another port, as far as OkHttp is concerned.
        val target = elsewhere.url("/stolen").toString()
        receiver.enqueue(MockResponse.Builder().code(307).addHeader("Location", target).build())

        post().use { response ->
            assertEquals(307, response.code)
            assertFalse(response.isSuccessful)
            val message = WebhookManager.failureMessage(response)
            assertTrue(message, message.startsWith("HTTP 307"))
            assertTrue(message, message.contains("to ${elsewhere.hostName} "))
        }
        assertEquals(1, receiver.requestCount)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test
    fun aPermanentRedirectIsNotFollowedEither() {
        receiver.enqueue(MockResponse.Builder().code(308).addHeader("Location", "/moved").build())

        post().use { response ->
            assertEquals(308, response.code)
            // A relative Location resolves against the configured URL.
            val message = WebhookManager.failureMessage(response)
            assertTrue(message, message.contains("to ${receiver.hostName} "))
        }
        assertEquals(1, receiver.requestCount)
        // Not retried and not a refusal of the payload: the outbox waits for a corrected address.
        assertFalse(WebhookSupport.isRetryable(308))
        assertFalse(WebhookSupport.refusesPayload(308))
    }

    @Test
    fun anOrdinaryFailureKeepsItsStatusLine() {
        receiver.enqueue(MockResponse.Builder().code(401).build())

        post().use { response ->
            assertEquals("HTTP 401: Client Error", WebhookManager.failureMessage(response))
        }
    }
}
