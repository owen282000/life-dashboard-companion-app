package com.owen282000.lifedashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * The limits on an MQTT publish, and what a sync makes of a failed one. The broker itself is
 * in MqttPublishTest; this is the part that needs none.
 */
class MqttTimeoutTest {

    private fun settings(useTls: Boolean = false) =
        MqttSettings(enabled = true, host = "broker.lan", port = 1883, useTls = useTls, username = null, password = null, baseTopic = "lifedashboard")

    @Test
    fun theClientCarriesBothConnectTimeouts() {
        val transport = MqttPublisher.clientFor(settings()).config.transportConfig
        assertEquals(MqttTimeouts.SOCKET_CONNECT_MILLIS, transport.socketConnectTimeoutMs.toLong())
        assertEquals(MqttTimeouts.MQTT_CONNECT_MILLIS, transport.mqttConnectTimeoutMs.toLong())
        // Set inside the transport config, so the host, the port and TLS must still be there.
        assertEquals("broker.lan", transport.serverAddress.hostString)
        assertEquals(1883, transport.serverAddress.port)
        assertTrue(transport.sslConfig.isEmpty)
        assertTrue(MqttPublisher.clientFor(settings(useTls = true)).config.transportConfig.sslConfig.isPresent)
    }

    @Test
    fun theDeadlinesLeaveRoomForTheConnectAndEndBeforeTheWorker() {
        assertTrue(MqttTimeouts.CONNECT_DEADLINE_MILLIS > MqttTimeouts.SOCKET_CONNECT_MILLIS + MqttTimeouts.MQTT_CONNECT_MILLIS)
        assertTrue(MqttTimeouts.PUBLISH_DEADLINE_MILLIS > MqttTimeouts.CONNECT_DEADLINE_MILLIS)
        // WorkManager stops a worker after 10 minutes; the publish is one step of a sync.
        assertTrue(MqttTimeouts.PUBLISH_DEADLINE_MILLIS <= 2 * 60_000L)
    }

    @Test
    fun aBrokerThatNeverAnswersFailsTheStep() = runTest {
        val never = CompletableFuture<String>()
        val wait = async { runCatching { never.within(MqttTimeouts.STEP_MILLIS) } }
        advanceTimeBy(MqttTimeouts.STEP_MILLIS - 1)
        runCurrent()
        assertTrue("still waiting just before the limit", wait.isActive)
        advanceTimeBy(2)
        val failure = wait.await().exceptionOrNull()
        assertTrue("a plain failure, not a cancellation: $failure", failure is MqttTimeoutException)
        assertEquals("No answer within 10 s", failure?.message)
    }

    @Test
    fun anAnswerInTimeIsNoFailure() = runTest {
        val done = CompletableFuture.completedFuture("ack")
        done.within(MqttTimeouts.STEP_MILLIS)
        // A disconnect completes with null, which must not read as a timeout.
        CompletableFuture.completedFuture<Void?>(null).within(MqttTimeouts.STEP_MILLIS)
    }

    @Test
    fun aCancelledSyncCancelsTheWaitAndTheFuture() = runTest {
        val never = CompletableFuture<String>()
        val wait = async { never.within(MqttTimeouts.STEP_MILLIS) }
        runCurrent()
        wait.cancel()
        val thrown = runCatching { wait.await() }.exceptionOrNull()
        assertTrue("the cancellation goes through: $thrown", thrown is CancellationException)
        assertTrue(never.isCancelled)
    }

    @Test
    fun mqttOnlyAFailedPublishIsAFailedSync() {
        val failure = MqttSupport.syncFailure(hasWebhooks = false, publish = Result.failure(java.io.IOException("Connection refused")))
        assertNotNull(failure)
        assertEquals("MQTT broker: Connection refused", failure?.message)
    }

    @Test
    fun withAWebhookTheWebhookDecides() {
        assertNull(MqttSupport.syncFailure(hasWebhooks = true, publish = Result.failure(java.io.IOException("Connection refused"))))
    }

    @Test
    fun aPublishThatWentOutOrHadNothingToSendIsNoFailure() {
        assertNull(MqttSupport.syncFailure(hasWebhooks = false, publish = Result.success(12)))
        assertNull(MqttSupport.syncFailure(hasWebhooks = false, publish = Result.success(0)))
        assertNull(MqttSupport.syncFailure(hasWebhooks = false, publish = null))
    }

    @Test
    fun aFailureWithoutAMessageIsNamedByItsType() {
        val failure = MqttSupport.syncFailure(hasWebhooks = false, publish = Result.failure(MqttTimeoutException(10_000)))
        assertEquals("MQTT broker: No answer within 10 s", failure?.message)
        assertEquals(
            "MQTT broker: IllegalStateException",
            MqttSupport.syncFailure(hasWebhooks = false, publish = Result.failure(IllegalStateException()))?.message
        )
    }
}
