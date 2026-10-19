package com.owen282000.lifedashboard

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** One payload to two receivers that each check their own secret: the stack and Home Assistant, paired one after the other (P2-13). */
class DestinationDeliveryTest {

    private val stack = MockWebServer()
    private val homeAssistant = MockWebServer()

    @Before
    fun start() {
        stack.start(InetAddress.getByName("127.0.0.1"), 0)
        homeAssistant.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        stack.close()
        homeAssistant.close()
    }

    @Test
    fun eachReceiverGetsASignatureMadeWithItsOwnSecret() = runTest {
        stack.enqueue(MockResponse.Builder().code(200).build())
        homeAssistant.enqueue(MockResponse.Builder().code(200).build())
        val payload = """{"source":"health_connect"}"""

        val outcome = WebhookManager(
            destinations = listOf(
                Destination("stack", stack.url("/health").toString(), secret = "stack-secret", headers = mapOf("X-Api-Key" to "k")),
                Destination("ha", homeAssistant.url("/api/webhook/abc").toString(), secret = "ha-secret")
            ),
            allowHttpOverride = true
        ).postData(payload)

        assertTrue(outcome.isSuccess)
        assertEquals(emptyList<String>(), outcome.getOrNull()?.missedUrls)

        // Each receiver verifies with its own secret, as receiver.py and the integration do.
        val toStack = stack.takeRequest()
        val toHomeAssistant = homeAssistant.takeRequest()
        assertTrue(WebhookSupport.signaturesMatch(toStack.headers[WebhookSupport.SIGNATURE_HEADER], WebhookSupport.signature(payload, "stack-secret")))
        assertTrue(WebhookSupport.signaturesMatch(toHomeAssistant.headers[WebhookSupport.SIGNATURE_HEADER], WebhookSupport.signature(payload, "ha-secret")))
        assertFalse(WebhookSupport.signaturesMatch(toHomeAssistant.headers[WebhookSupport.SIGNATURE_HEADER], WebhookSupport.signature(payload, "stack-secret")))

        // And the stack's key went to the stack alone.
        assertEquals("k", toStack.headers["X-Api-Key"])
        assertNull(toHomeAssistant.headers["X-Api-Key"])
    }

    @Test
    fun aDestinationWithoutASecretIsNotSigned() = runTest {
        stack.enqueue(MockResponse.Builder().code(200).build())

        WebhookManager(destinationsOf(listOf(stack.url("/health").toString())), allowHttpOverride = true).postData("{}")

        assertNull(stack.takeRequest().headers[WebhookSupport.SIGNATURE_HEADER])
    }
}
