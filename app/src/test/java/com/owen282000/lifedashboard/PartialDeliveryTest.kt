package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.viewmodel.UiMessage
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/** A delivery that reached one webhook and not the other: still delivered, now visible. */
class PartialDeliveryTest {

    private val taken = MockWebServer()
    private val down = MockWebServer()

    @Before
    fun start() {
        taken.start(InetAddress.getByName("127.0.0.1"), 0)
        down.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        taken.close()
        down.close()
    }

    @Test
    fun theOutcomeNamesTheWebhookThatMissedWhatAnotherTook() = runTest {
        val ok = taken.url("/hook").toString()
        val missing = down.url("/hook").toString()
        taken.enqueue(MockResponse.Builder().code(200).build())
        down.enqueue(MockResponse.Builder().code(404).build())

        val result = WebhookManager(webhookUrls = listOf(ok, missing), allowHttpOverride = true).postData("{}")

        assertTrue("one webhook took it, so it counts as delivered, as before", result.isSuccess)
        assertEquals(listOf(missing), result.getOrNull()?.missedUrls)
        assertEquals(2, result.getOrNull()?.urlCount)
    }

    @Test
    fun aDeliveryToEveryWebhookMissesNothing() = runTest {
        taken.enqueue(MockResponse.Builder().code(200).build())
        down.enqueue(MockResponse.Builder().code(200).build())

        val result = WebhookManager(
            webhookUrls = listOf(taken.url("/a").toString(), down.url("/b").toString()),
            allowHttpOverride = true
        ).postData("{}")

        assertEquals(emptyList<String>(), result.getOrNull()?.missedUrls)
    }

    @Test
    fun hostsNeverCarryThePathOrTheQuery() {
        assertEquals(
            "ha.example.com, stack.local",
            PartialDelivery.hosts(
                listOf(
                    "https://ha.example.com/api/webhook/0123456789abcdef",
                    "http://stack.local:8080/health?token=abc",
                    "https://ha.example.com/api/webhook/other"
                )
            )
        )
    }

    @Test
    fun theStreakCountsPartialDeliveriesAndClearsOnAFullOne() {
        val missed = listOf("https://down.example/hook")
        assertEquals(1, PartialDelivery.nextStreak(0, delivered = true, missed = missed))
        assertEquals(3, PartialDelivery.nextStreak(2, delivered = true, missed = missed))
        assertEquals(0, PartialDelivery.nextStreak(3, delivered = true, missed = emptyList()))
        // Nothing delivered at all is the failure streak's business; this one stays as it is.
        assertEquals(2, PartialDelivery.nextStreak(2, delivered = false, missed = emptyList()))
    }

    @Test
    fun theSyncLineSaysHowManyWebhooksTookIt() {
        val synced = UiMessage.SyncedRecords(42)
        assertEquals(UiMessage.PartlyDelivered(synced, delivered = 1, total = 2), UiMessage.partly(synced, missed = 1, total = 2))
        assertTrue(UiMessage.partly(synced, missed = 1, total = 2).isFailure)
        assertEquals(synced, UiMessage.partly(synced, missed = 0, total = 2))
        // Everything missed is a failed sync, which never reaches this line as a success.
        assertEquals(synced, UiMessage.partly(synced, missed = 2, total = 2))
    }
}
