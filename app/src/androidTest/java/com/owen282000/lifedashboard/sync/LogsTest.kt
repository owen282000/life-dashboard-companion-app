package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.HeartRateRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.ReceiveLogLine
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.WebhookLogStore
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.FakeIntegration
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.UUID

/** The Logs tab's storage: what a row keeps of a payload, and what it never keeps. */
@RunWith(AndroidJUnit4::class)
class LogsTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()

    private fun outRows() = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { it.direction == LogDirection.OUT.name }

    /** A payload well over 16 KiB: 300 heart rate samples. */
    private fun bigSync(): String = runBlocking {
        val start = ago(60)
        fixture.insert(*Array(3) { r -> fixture.heartRate(List(100) { s -> start.plusSeconds(r * 200L + s) to 70L }) })
        TestSetup.syncManager().performSync().getOrThrow()
        receiver.exchanges.last().text
    }

    /** T21. An OUT row keeps the first 16 KiB and a marker, or the whole body when full payloads are kept. */
    @Test
    fun outRowsTruncateUnlessKeptInFull() {
        TestSetup.health(receiver, setOf(HEART_RATE))
        fixture.assertNoForeignRecords(HeartRateRecord::class)
        val body = bigSync()
        assertTrue(body.length > WebhookLogStore.DEFAULT_PAYLOAD_LIMIT)
        assertEquals(body.take(WebhookLogStore.DEFAULT_PAYLOAD_LIMIT) + WebhookLogStore.TRUNCATION_MARKER, outRows().single().rawPayload)

        AppStateRule.reset()
        TestSetup.health(receiver, setOf(HEART_RATE))
        prefs.setKeepFullPayloads(true)
        val full = bigSync()
        assertEquals(full, outRows().single().rawPayload)
    }

    /**
     * T22. A Receive round leaves an IN row with a line per reading and no values, unless full
     * payloads are kept. No row of either direction holds the secret or a signature.
     */
    @Test
    fun inRowsHaveLinesWithoutValuesUnlessKept() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS), receive = setOf(WriteBackType.WEIGHT))
        val integration = FakeIntegration(TestSetup.HEALTH_SECRET)
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        integration.offer(FakeIntegration.weight("sensor.scale_weight@1", 81.35, ago(10)))
        TestSetup.syncManager().performSync().getOrThrow()

        val row = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).single { it.direction == LogDirection.IN.name }
        assertEquals("receive", row.dataType)
        assertEquals(1, row.recordCount)
        val line = ReceiveLogLine.decode(row.rawPayload).orEmpty().single()
        assertEquals("sensor.scale_weight", line.entity)
        assertEquals("weight", line.type)
        assertEquals(ReceiveLogLine.WRITTEN, line.outcome)
        assertNull("no value unless full payloads are kept", line.value)

        prefs.setKeepFullPayloads(true)
        integration.offer(FakeIntegration.weight("sensor.scale_weight@2", 81.35, ago(5)))
        TestSetup.syncManager().performSync().getOrThrow()
        val kept = ReceiveLogLine.decode(prefs.getWebhookLogs(LogType.HEALTH_CONNECT).first { it.direction == LogDirection.IN.name }.rawPayload).orEmpty().single()
        assertEquals(81.35, kept.value!!, 0.001)
        assertEquals("kg", kept.unit)

        prefs.getWebhookLogs().forEach { log ->
            val stored = listOfNotNull(log.rawPayload, log.errorMessage, log.note, log.url).joinToString()
            assertTrue("no secret in a row", TestSetup.HEALTH_SECRET !in stored)
            assertTrue("no signature in a row", "sha256=" !in stored)
        }
    }

    /** T23. A heartbeat that succeeds leaves no OUT row; one that fails does, as a heartbeat. */
    @Test
    fun heartbeatLogsOnlyFailures() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS), receive = setOf(WriteBackType.WEIGHT))
        val integration = FakeIntegration(TestSetup.HEALTH_SECRET)
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(emptyList<WebhookLog>(), outRows())

        integration.failWith = 503
        TestSetup.syncManager().performSync().getOrThrow()
        val failed = outRows().single()
        assertEquals("heartbeat", failed.dataType)
        assertTrue(!failed.success)
    }

    /** The list reads a row's size and whether the store cut it short, without the payload; a delete takes both away. */
    @Test
    fun rowsKnowThePayloadWithoutReadingItAndDeleteTakesItAway() {
        fun row(payload: String?) = WebhookLog(
            id = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(), url = "https://example.invalid",
            statusCode = 200, success = true, errorMessage = null, dataType = "test", recordCount = 1, rawPayload = payload
        )
        val none = row(null)
        val small = row("""{"steps":[]}""")
        val cut = row("a".repeat(WebhookLogStore.DEFAULT_PAYLOAD_LIMIT + 1))
        listOf(none, small, cut).forEach(prefs::addWebhookLog)

        val rows = prefs.getWebhookLogRows().associateBy { it.log.id }
        assertNull(rows.getValue(none.id).payloadBytes)
        assertEquals(small.rawPayload!!.length.toLong(), rows.getValue(small.id).payloadBytes)
        assertEquals(false, rows.getValue(small.id).cutInStorage)
        assertEquals(WebhookLogStore.DEFAULT_PAYLOAD_LIMIT.toLong(), rows.getValue(cut.id).payloadBytes)
        assertEquals(true, rows.getValue(cut.id).cutInStorage)
        assertNull(rows.getValue(cut.id).log.rawPayload)

        assertEquals(small.rawPayload, prefs.getWebhookLog(small.id)?.rawPayload)
        prefs.deleteWebhookLogs(setOf(small.id))
        assertNull(prefs.getWebhookLog(small.id))
        assertEquals(2, prefs.getWebhookLogs().size)
    }

    /** T24. Clearing the Screen Time logs keeps the health ones, and reading by type filters. */
    @Test
    fun clearByTypeKeepsTheOther() {
        fun row(type: LogType) = WebhookLog(
            id = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(), url = "https://example.invalid",
            statusCode = 200, success = true, errorMessage = null, dataType = "test", recordCount = 1, logType = type.name
        )
        prefs.addWebhookLog(row(LogType.HEALTH_CONNECT))
        prefs.addWebhookLog(row(LogType.SCREEN_TIME))
        prefs.addWebhookLog(row(LogType.HEALTH_CONNECT))

        assertEquals(1, prefs.getWebhookLogs(LogType.SCREEN_TIME).size)
        prefs.clearWebhookLogs(LogType.SCREEN_TIME)

        assertEquals(0, prefs.getWebhookLogs(LogType.SCREEN_TIME).size)
        assertEquals(2, prefs.getWebhookLogs(LogType.HEALTH_CONNECT).size)
        assertEquals(2, prefs.getWebhookLogs().size)
    }

    @Test
    fun rowsWrittenAtTheSameTimeAreAllKept() {
        // A Health Connect sync, a Screen Time sync and Receive log from their own threads,
        // each through its own store. Every row and its payload must survive the overlap.
        val threads = 8
        val perThread = 10
        val ids = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (1..threads).map { t ->
            kotlin.concurrent.thread {
                start.await()
                repeat(perThread) {
                    val id = UUID.randomUUID().toString()
                    ids.add(id)
                    WebhookLogStore(context).add(
                        WebhookLog(
                            id = id, timestamp = System.currentTimeMillis(), url = "https://example.invalid/$t",
                            statusCode = 200, success = true, errorMessage = null, dataType = "test",
                            recordCount = 1, rawPayload = """{"thread":$t}"""
                        ),
                        keepFullPayloads = false
                    )
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        val rows = WebhookLogStore(context).getAll()
        assertEquals(threads * perThread, rows.size)
        assertEquals(ids.toSet(), rows.map { it.id }.toSet())
        assertTrue(rows.all { it.rawPayload != null })
    }
}
