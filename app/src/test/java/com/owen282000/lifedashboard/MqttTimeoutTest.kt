package com.owen282000.lifedashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * The limits on an MQTT publish, what happens to a connection the publish gave up on, and what
 * a sync makes of a failed publish. The broker itself is in MqttPublishTest; this is the part
 * that needs none.
 */
class MqttTimeoutTest {

    private fun settings(useTls: Boolean = false) =
        MqttSettings(enabled = true, host = "broker.lan", port = 1883, useTls = useTls, username = null, password = null, baseTopic = "lifedashboard")

    /** Stands in for HiveMQ: a connect future the test completes, and a count of disconnects. */
    private class FakeBroker {
        val connect = CompletableFuture<String>()
        var disconnects = 0
        fun disconnect(): CompletableFuture<Void?> {
            disconnects++
            return CompletableFuture.completedFuture(null)
        }
    }

    private val describe: (String) -> String = { "MQTT broker: $it" }

    @Test
    fun theClientCarriesTheConnectTimeouts() {
        val transport = MqttPublisher.clientFor(settings()).config.transportConfig
        assertEquals(MqttTimeouts.SOCKET_CONNECT_MILLIS, transport.socketConnectTimeoutMs.toLong())
        assertEquals(MqttTimeouts.MQTT_CONNECT_MILLIS, transport.mqttConnectTimeoutMs.toLong())
        // Set inside the transport config, so the host, the port and TLS must still be there.
        assertEquals("broker.lan", transport.serverAddress.hostString)
        assertEquals(1883, transport.serverAddress.port)
        assertTrue(transport.sslConfig.isEmpty)
        val tls = MqttPublisher.clientFor(settings(useTls = true)).config.transportConfig.sslConfig
        assertTrue(tls.isPresent)
        assertEquals(MqttTimeouts.TLS_HANDSHAKE_MILLIS, tls.get().handshakeTimeoutMs)
    }

    @Test
    fun theConnectDeadlineCoversEveryStepOfTheConnect() {
        // Socket, TLS handshake and CONNACK in a row: the deadline must outlast all three, so
        // HiveMQ's own limit ends a stalled connect and its error names the step.
        assertTrue(
            MqttTimeouts.CONNECT_DEADLINE_MILLIS >
                MqttTimeouts.SOCKET_CONNECT_MILLIS + MqttTimeouts.TLS_HANDSHAKE_MILLIS + MqttTimeouts.MQTT_CONNECT_MILLIS
        )
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
        assertEquals(MqttTimeouts.STEP_MILLIS, (failure as MqttTimeoutException).millis)
    }

    @Test
    fun anAnswerInTimeIsNoFailure() = runTest {
        CompletableFuture.completedFuture("ack").within(MqttTimeouts.STEP_MILLIS)
        // A disconnect completes with null, which must not read as a timeout.
        CompletableFuture.completedFuture<Void?>(null).within(MqttTimeouts.STEP_MILLIS)
    }

    @Test
    fun aCancelledSyncEndsTheWaitButLeavesHiveMqsFutureAlone() = runTest {
        val never = CompletableFuture<String>()
        val wait = async { never.within(MqttTimeouts.STEP_MILLIS) }
        runCurrent()
        wait.cancel()
        val thrown = runCatching { wait.await() }.exceptionOrNull()
        assertTrue("the cancellation goes through: $thrown", thrown is CancellationException)
        // Cancelled, it would complete at once with a CancellationException while HiveMQ goes
        // on connecting, and a late connect would never be seen to disconnect it.
        assertFalse(never.isDone)
    }

    @Test
    fun aConnectThatCompletesAfterTheTimeoutIsDisconnected() = runTest {
        val broker = FakeBroker()
        val run = async { runCatching { connected(broker.connect, broker::disconnect) {} } }
        advanceTimeBy(MqttTimeouts.CONNECT_DEADLINE_MILLIS + 1)
        assertTrue(run.await().exceptionOrNull() is MqttTimeoutException)
        assertEquals("nothing to disconnect yet", 0, broker.disconnects)

        broker.connect.complete("connack")

        assertEquals(1, broker.disconnects)
    }

    @Test
    fun aConnectThatCompletesAfterACancelledSyncIsDisconnected() = runTest {
        val broker = FakeBroker()
        val run = async { connected(broker.connect, broker::disconnect) {} }
        runCurrent()
        run.cancel()
        assertTrue(runCatching { run.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, broker.disconnects)

        broker.connect.complete("connack")

        assertEquals(1, broker.disconnects)
    }

    @Test
    fun aFailedExchangeDisconnectsAtOnce() = runTest {
        val broker = FakeBroker()
        broker.connect.complete("connack")
        val failure = runCatching { connected(broker.connect, broker::disconnect) { throw IOException("refused") } }.exceptionOrNull()
        assertEquals("refused", failure?.message)
        assertEquals(1, broker.disconnects)
    }

    @Test
    fun aConnectThatFailsIsNeverDisconnected() = runTest {
        val broker = FakeBroker()
        broker.connect.completeExceptionally(IOException("Connection refused"))
        assertNotNull(runCatching { connected(broker.connect, broker::disconnect) {} }.exceptionOrNull())
        assertEquals(0, broker.disconnects)
    }

    @Test
    fun aPublishThatWentOutDisconnectsOnce() = runTest {
        val broker = FakeBroker()
        broker.connect.complete("connack")
        var exchanged = false
        connected(broker.connect, broker::disconnect) { exchanged = true }
        assertTrue(exchanged)
        assertEquals(1, broker.disconnects)
    }

    @Test
    fun mqttOnlyAFailedPublishIsAFailedSync() {
        val failure = MqttSupport.syncFailure(hasWebhooks = false, publish = Result.failure(IOException("Connection refused")), describe = describe)
        assertNotNull(failure)
        assertEquals("MQTT broker: Connection refused", failure?.message)
    }

    @Test
    fun withAWebhookTheWebhookDecides() {
        assertNull(MqttSupport.syncFailure(hasWebhooks = true, publish = Result.failure(IOException("Connection refused")), describe = describe))
    }

    @Test
    fun aPublishThatWentOutOrHadNothingToSendIsNoFailure() {
        assertNull(MqttSupport.syncFailure(hasWebhooks = false, publish = Result.success(12), describe = describe))
        assertNull(MqttSupport.syncFailure(hasWebhooks = false, publish = Result.success(0), describe = describe))
        assertNull(MqttSupport.syncFailure(hasWebhooks = false, publish = null, describe = describe))
    }

    @Test
    fun aFailureWithoutAMessageIsNamedByItsType() {
        assertEquals(
            "MQTT broker: IllegalStateException",
            MqttSupport.syncFailure(hasWebhooks = false, publish = Result.failure(IllegalStateException()), describe = describe)?.message
        )
    }
}
