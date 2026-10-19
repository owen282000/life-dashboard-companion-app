package com.owen282000.lifedashboard

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * The receiver's answer alone decides delivered or failed (P2-22). A log row that cannot be
 * written must not turn an accepted payload into a failure, which the outbox would send again:
 * a duplicate in a receiver that only inserts. An IOException is the sharp case, since the POST
 * loop retries on one.
 */
class DeliveryLoggingTest {

    private val receiver = MockWebServer()

    @Before
    fun start() {
        receiver.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        receiver.close()
    }

    private fun manager(failure: Exception) = WebhookManager(
        destinations = destinationsOf(listOf(receiver.url("/hook").toString())),
        allowHttpOverride = true,
        logRecorder = { _, _ -> throw failure }
    )

    @Test
    fun anAcceptedPayloadStaysDeliveredWhenTheLogCannotBeWritten() = runTest {
        receiver.enqueue(MockResponse.Builder().code(200).build())

        val result = manager(IOException("No space left on device")).postData("{}")

        assertTrue(result.isSuccess)
        assertEquals("posted once, not retried", 1, receiver.requestCount)
    }

    @Test
    fun anyOtherLogErrorLeavesTheDeliveryAlone() = runTest {
        receiver.enqueue(MockResponse.Builder().code(200).build())

        val result = manager(IllegalStateException("broken log file")).postData("{}")

        assertTrue(result.isSuccess)
        assertEquals(1, receiver.requestCount)
    }

    @Test
    fun aRefusalStaysARefusalWhenTheLogCannotBeWritten() = runTest {
        receiver.enqueue(MockResponse.Builder().code(404).build())

        val result = manager(IOException("No space left on device")).postData("{}")

        assertTrue(result.isFailure)
        assertEquals("a 404 is not retried", 1, receiver.requestCount)
    }
}
