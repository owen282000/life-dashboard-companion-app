package com.owen282000.lifedashboard.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.owen282000.lifedashboard.FailedReading
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.ReceiveLogLine
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.TestPing
import com.owen282000.lifedashboard.WriteBackLedger
import com.owen282000.lifedashboard.WriteBackReport
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.FakeIntegration
import com.owen282000.lifedashboard.harness.HcCall
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.SlowHealthConnectClient
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.obj
import com.owen282000.lifedashboard.harness.str
import com.owen282000.lifedashboard.harness.strings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Receive (issue #62) end to end: the signed answer of the integration, written into Health
 * Connect, acknowledged on the next request, and never sent back out. The integration is
 * [FakeIntegration], which checks and signs with code of its own.
 */
@RunWith(AndroidJUnit4::class)
class ReceiveTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val integration = FakeIntegration(TestSetup.HEALTH_SECRET)

    /** The integration from 1.3.0, which can put the signature of its answer in the body. */
    private val newer = FakeIntegration(TestSetup.HEALTH_SECRET, version = "1.3.0", signedBody = true)

    /**
     * T32. Four syncs. 1: a heartbeat asks for weight and blood pressure, and the two offered
     * readings land in Health Connect exactly as sent. 2: the next request acknowledges both
     * and the integration drops them. 3: with weight read as well, a weight measured on the
     * phone goes out and the received one does not (the echo filter, e1f6ebe). 4: deleting
     * both from Health Connect reports only the phone's own weight as deleted, never the one
     * Home Assistant sent.
     */
    @Test
    fun roundTripWritesAcksAndDoesNotEcho() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS), receive = setOf(WriteBackType.WEIGHT, WriteBackType.BLOOD_PRESSURE))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class, BloodPressureRecord::class)
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        val weighed = ago(10)
        val measured = ago(8)
        val weightId = "sensor.scale_weight@${weighed.toEpochMilli()}"
        val pressureId = "sensor.omron_systolic@${measured.toEpochMilli()}"
        integration.offer(
            FakeIntegration.weight(weightId, 81.35, weighed),
            FakeIntegration.bloodPressure(pressureId, 128.0, 82.0, measured)
        )
        val sync = TestSetup.syncManager()

        // Sync 1: nothing to read, so a heartbeat carries the request for readings.
        val first = sync.performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(emptyMap(), written = 2), first)
        val heartbeat = integration.seen.single()
        assertTrue("the request is signed with the section's secret", heartbeat.signatureValid)
        assertEquals(setOf("timestamp", "app_version", "source", "writeback"), heartbeat.body.keys)
        val block = heartbeat.writeback!!
        assertEquals("1", block.num("protocol"))
        assertEquals(listOf("weight", "blood_pressure"), block["types"].strings())
        assertEquals("false", block.num("history"))
        assertEquals(emptyList<String>(), block["ack"].strings())
        assertEquals(0, block.arr("failed")?.size)

        val weight = fixture.read(WeightRecord::class).single()
        assertEquals(weightId, weight.metadata.clientRecordId)
        assertEquals(1L, weight.metadata.clientRecordVersion)
        assertEquals(weighed, weight.time)
        assertEquals(ZoneOffset.of("+02:00"), weight.zoneOffset)
        assertEquals(81.35, weight.weight.inKilograms, 0.001)
        assertEquals(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, weight.metadata.recordingMethod)
        assertEquals(Device.TYPE_SCALE, weight.metadata.device?.type)
        assertEquals("Xiaomi", weight.metadata.device?.manufacturer)
        val pressure = fixture.read(BloodPressureRecord::class).single()
        assertEquals(pressureId, pressure.metadata.clientRecordId)
        assertEquals(measured, pressure.time)
        assertEquals(128.0, pressure.systolic.inMillimetersOfMercury, 0.001)
        assertEquals(82.0, pressure.diastolic.inMillimetersOfMercury, 0.001)
        assertEquals(BloodPressureRecord.BODY_POSITION_SITTING_DOWN, pressure.bodyPosition)
        assertEquals(BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM, pressure.measurementLocation)
        assertEquals(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, pressure.metadata.recordingMethod)
        assertEquals("Omron", pressure.metadata.device?.manufacturer)

        val prefs = context.appPreferences()
        assertEquals(
            "the ledger names the records Health Connect holds",
            mapOf(weightId to weight.metadata.id, pressureId to pressure.metadata.id),
            prefs.getWriteBackLedger().entries.associate { it.id to it.recordId }
        )
        assertEquals(2, SyncStatusStore.writtenToday(context))
        val inRows = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { it.direction == LogDirection.IN.name }
        assertTrue(inRows.single().success)
        assertEquals(0, receiveStreak(context))

        // Sync 2: the acknowledgements ride on the next request; the integration drops both.
        sync.performSync().getOrThrow()

        val second = integration.seen[1]
        assertTrue(second.signatureValid)
        assertEquals(setOf(weightId, pressureId), second.ack().toSet())
        assertEquals(emptyList<String>(), integration.pendingIds())
        assertTrue("nothing left to acknowledge", prefs.getWriteBackReport().isEmpty)
        assertEquals("nothing offered, no new row", 1, prefs.getWebhookLogs(LogType.HEALTH_CONNECT).count { it.direction == LogDirection.IN.name })

        // Sync 3: weight is read too; the phone's own weight goes out, the received one does not.
        prefs.setHealthEnabledDataTypes(setOf(STEPS, WEIGHT))
        val (phoneWeightId) = fixture.insert(fixture.weight(80.9, ago(5)))
        val mark = receiver.exchanges.size
        sync.performSync().getOrThrow()

        val third = receiver.since(mark).single()
        assertEquals(emptyList<String>(), Schema.errors(third.text, "sync3"))
        val body = Conservation.parse(third.text)
        Conservation.assertExactlyOnce(setOf(phoneWeightId), listOf(body))
        assertEquals("1", body.obj("_diagnostics")?.obj("weight")?.num("own_records_skipped"))
        assertEquals("health_connect", body.str("source"))

        // Sync 4: both weights deleted; only the phone's own is reported as deleted.
        fixture.delete(WeightRecord::class, weight.metadata.id, phoneWeightId)
        val mark4 = receiver.exchanges.size
        sync.performSync().getOrThrow()

        val deletion = Conservation.parse(receiver.since(mark4).single().text)
        val deleted = deletion.arr("deleted_records").orEmpty().map { (it as kotlinx.serialization.json.JsonObject).str("uuid") }
        assertEquals(listOf(phoneWeightId), deleted)
        assertFalse("Home Assistant never hears back about its own weight", receiver.since(mark4).any { weight.metadata.id in it.text })
        assertNull(deletion["deletions_unavailable"])
    }

    private fun receiveStreak(context: Context): Int =
        context.getSharedPreferences("life_dashboard_prefs", Context.MODE_PRIVATE).getInt("sync_failure_streak_RECEIVE", 0)

    /** Receive on for weight and blood pressure, steps read, the integration on the health URL. */
    private fun receiveSetup(read: Set<HealthDataType> = setOf(STEPS), types: Set<WriteBackType> = setOf(WriteBackType.WEIGHT, WriteBackType.BLOOD_PRESSURE)) {
        TestSetup.health(receiver, read, receive = types)
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class, BloodPressureRecord::class)
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
    }

    private val prefs get() = context.appPreferences()

    private fun inRows() = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { it.direction == LogDirection.IN.name }

    /** T33. A request that fails keeps its acks for the next one. */
    @Test
    fun acksSurviveAFailedRequest() = runBlocking {
        receiveSetup()
        val id = "sensor.scale_weight@1"
        integration.offer(FakeIntegration.weight(id, 80.2, ago(10)))
        TestSetup.syncManager().performSync().getOrThrow()

        integration.failWith = 500
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(listOf(id), integration.seen.last().ack())
        assertEquals("kept for the next request", listOf(id), prefs.getWriteBackReport().ack)

        integration.failWith = null
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(listOf(id), integration.seen.last().ack())
        assertTrue(prefs.getWriteBackReport().isEmpty)
    }

    /**
     * T34. Nothing to send but Receive on: one heartbeat, to the source URL only, with exactly
     * the four keys of the protocol, signed; the integration's own is_heartbeat rule agrees.
     * A heartbeat that fails is not queued, and counts once in the webhook streak.
     */
    @Test
    fun heartbeatWhenNothingToSend() = runBlocking {
        receiveSetup()
        prefs.setHealthWebhookUrls(listOf(receiver.url(TestSetup.HEALTH_PATH), receiver.url("/other")))

        TestSetup.syncManager().performSync().getOrThrow()

        val only = receiver.exchanges.single()
        assertEquals(TestSetup.HEALTH_PATH, only.path)
        val seen = integration.seen.single()
        assertTrue(seen.signatureValid)
        assertEquals(setOf("timestamp", "app_version", "source", "writeback"), seen.body.keys)
        assertTrue("payload.py is_heartbeat", isHeartbeat(seen.body))

        integration.failWith = 503
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertEquals(1, TestSetup.streak("HEALTH_CONNECT"))
    }

    /** payload.py `is_heartbeat`, restated: a writeback block, no test flag, no totals, no record arrays. */
    private fun isHeartbeat(data: JsonObject): Boolean =
        data["writeback"] is JsonObject && data["test"] == null && data["daily_totals"] == null &&
            data.keys.none { it in setOf("steps", "sleep", "heart_rate", "weight", "blood_pressure", "distance") }

    /**
     * T35 (c9dd207). A phone that reads nothing and only receives: the sync is allowed, the
     * one weight is written, and "Last sync" moves while "records today" stays 0. A heartbeat
     * that brings nothing is NoData and leaves "Last sync" where it was.
     */
    @Test
    fun receiveOnlyPhoneMovesLastSync() = runBlocking {
        receiveSetup(read = emptySet(), types = setOf(WriteBackType.WEIGHT))
        integration.offer(FakeIntegration.weight("sensor.scale_weight@2", 80.2, ago(10)))

        val first = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(emptyMap(), written = 1), first)
        val status = SyncStatusStore.read(context, LogType.HEALTH_CONNECT)
        assertNotNull(status.lastSyncMillis)
        assertEquals(0, status.recordsToday)

        Thread.sleep(5)
        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())
        assertEquals(status.lastSyncMillis, SyncStatusStore.read(context, LogType.HEALTH_CONNECT).lastSyncMillis)
    }

    /** T36. One rejected answer: nothing written, one IN row with the reason, the Receive streak up, the sent acks gone. */
    private fun rejected(mode: FakeIntegration.Mode, reason: String, integration: FakeIntegration = this.integration) = runBlocking {
        receiveSetup()
        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        prefs.setWriteBackReport(WriteBackReport(ack = listOf("sensor.acked_before@1")))
        integration.offer(FakeIntegration.weight("sensor.scale_weight@3", 80.2, ago(10)))
        integration.mode = mode

        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(listOf("sensor.acked_before@1"), integration.seen.single().ack())
        assertEquals(emptyList<WeightRecord>(), fixture.read(WeightRecord::class))
        assertEquals("Receive: response rejected ($reason)", inRows().single().errorMessage)
        assertEquals(1, TestSetup.streak("RECEIVE"))
        assertTrue("the acks of the sent request are gone", prefs.getWriteBackReport().isEmpty)
    }

    /** An integration that knows the signed body and leaves the signature out of it, with no header either. */
    @Test fun rejectedWithoutSignature() = rejected(FakeIntegration.Mode.NO_SIGNATURE, "no signature on the response", newer)

    /** The signed body through the cloud, signed with the key of the other direction. */
    @Test fun rejectedSignedBodyWithTheSecretItself() =
        rejected(FakeIntegration.Mode.SIGNED_WITH_SECRET, "the signature does not match", newer.also { it.cloud = true })

    @Test fun rejectedSignedBodyNotInReply() =
        rejected(FakeIntegration.Mode.WRONG_IN_REPLY_TO, "in_reply_to does not name this request", newer.also { it.cloud = true })

    @Test fun rejectedSignedWithTheSecretItself() = rejected(FakeIntegration.Mode.SIGNED_WITH_SECRET, "the signature does not match")

    @Test fun rejectedNotInReply() = rejected(FakeIntegration.Mode.WRONG_IN_REPLY_TO, "in_reply_to does not name this request")

    @Test fun rejectedStale() = rejected(FakeIntegration.Mode.STALE, "issued_at is more than 10 minutes from the phone's clock")

    @Test fun rejectedTooMany() = rejected(FakeIntegration.Mode.TOO_MANY, "more than 200 readings")

    @Test fun rejectedTooLarge() = rejected(FakeIntegration.Mode.TOO_LARGE, "the body is larger than 256 KiB")

    @Test fun rejectedUnsupportedProtocol() = rejected(FakeIntegration.Mode.PROTOCOL_2, "the response speaks a protocol version this app does not")

    /** T36, the old integration: an empty 200 is no row, only the notice; a good answer lifts it again. */
    @Test
    fun integrationBeforeTheProtocolIsANoticeNotAFailure() = runBlocking {
        receiveSetup()
        integration.mode = FakeIntegration.Mode.BEFORE_THE_PROTOCOL
        TestSetup.syncManager().performSync().getOrThrow()
        assertTrue(prefs.getReceiveStatus().integrationOutdated)
        assertEquals(emptyList<Any>(), inRows())
        assertEquals(0, TestSetup.streak("RECEIVE"))

        integration.mode = FakeIntegration.Mode.HONEST
        TestSetup.syncManager().performSync().getOrThrow()
        assertFalse(prefs.getReceiveStatus().integrationOutdated)
    }

    /**
     * P1-47. Through Home Assistant Cloud only the status, the body and the Content-Type come
     * back. The request asks for the signed body, the integration 1.3.0 answers with it, and the
     * reading is written and acknowledged as on any route.
     */
    @Test
    fun signedBodyThroughTheCloudIsWrittenAndAcked() = runBlocking {
        receiveSetup(types = setOf(WriteBackType.WEIGHT))
        receiver.route(TestSetup.HEALTH_PATH, newer::handle)
        newer.cloud = true
        val id = "sensor.scale_weight@47"
        newer.offer(FakeIntegration.weight(id, 80.2, ago(10)))

        val first = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(emptyMap(), written = 1), first)
        assertEquals("body", newer.seen.single().writeback?.str("answer"))
        assertEquals(id, fixture.read(WeightRecord::class).single().metadata.clientRecordId)
        assertFalse(prefs.getReceiveStatus().signatureLost)
        assertEquals(0, TestSetup.streak("RECEIVE"))

        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(listOf(id), newer.seen.last().ack())
        assertEquals(emptyList<String>(), newer.pendingIds())
    }

    /** P1-47. The signed body on a route that keeps the header: the same outcome, the header not needed. */
    @Test
    fun signedBodyWithTheHeaderIsWritten() = runBlocking {
        receiveSetup(types = setOf(WriteBackType.WEIGHT))
        receiver.route(TestSetup.HEALTH_PATH, newer::handle)
        newer.offer(FakeIntegration.weight("sensor.scale_weight@48", 80.2, ago(10)))

        assertEquals(HealthSyncResult.Success(emptyMap(), written = 1), TestSetup.syncManager().performSync().getOrThrow())
    }

    /**
     * P1-47. An integration before 1.3.0 behind the cloud: its answer arrives without any
     * signature, so nothing is written, and it is the notice on the Receive page, not a failed
     * row and a streak per sync. The request's acks are still delivered. Updating the
     * integration lifts the notice and the reading arrives.
     */
    @Test
    fun olderIntegrationThroughTheCloudIsANoticeNotAFailure() = runBlocking {
        receiveSetup(types = setOf(WriteBackType.WEIGHT))
        integration.cloud = true
        val reading = FakeIntegration.weight("sensor.scale_weight@49", 80.2, ago(10))
        integration.offer(reading)

        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals("body", integration.seen.single().writeback?.str("answer"))
        assertTrue(prefs.getReceiveStatus().signatureLost)
        assertFalse(prefs.getReceiveStatus().integrationOutdated)
        assertEquals(emptyList<WeightRecord>(), fixture.read(WeightRecord::class))
        assertEquals(emptyList<Any>(), inRows())
        assertEquals(0, TestSetup.streak("RECEIVE"))

        receiver.route(TestSetup.HEALTH_PATH, newer::handle)
        newer.cloud = true
        newer.offer(reading)
        assertEquals(HealthSyncResult.Success(emptyMap(), written = 1), TestSetup.syncManager().performSync().getOrThrow())
        assertFalse(prefs.getReceiveStatus().signatureLost)
    }

    private fun ping() = runBlocking {
        TestPing.send(context, prefs.getDestinations(LogType.HEALTH_CONNECT), LogType.HEALTH_CONNECT)
    }

    /**
     * P1-47. The test ping asks for the signed body and checks the answer: it stands through the
     * cloud with 1.3.0 and with the header from an older integration, it stands with the notice
     * for an older integration behind the cloud, and it fails on a signature that is missing where
     * one belongs or does not match.
     */
    @Test
    fun testPingChecksTheAnswer() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        val url = receiver.url(TestSetup.HEALTH_PATH)
        prefs.setReceiveSourceUrl(url)

        receiver.route(TestSetup.HEALTH_PATH, newer::handle)
        newer.cloud = true
        assertTrue(ping().isSuccess)
        val sent = newer.seen.single()
        assertEquals("true", sent.body.num("test"))
        assertEquals(setOf("answer"), sent.writeback?.keys)
        assertEquals("body", sent.writeback?.str("answer"))
        assertFalse(prefs.getReceiveStatus().signatureLost)

        receiver.route(TestSetup.HEALTH_PATH, integration::handle)
        assertTrue("an older integration, answering with the header", ping().isSuccess)
        assertFalse(prefs.getReceiveStatus().signatureLost)

        integration.cloud = true
        assertTrue("delivered, with the notice for Receive", ping().isSuccess)
        assertTrue(prefs.getReceiveStatus().signatureLost)

        receiver.route(TestSetup.HEALTH_PATH, newer::handle)
        assertTrue(ping().isSuccess)
        assertFalse("a signed answer lifts the notice", prefs.getReceiveStatus().signatureLost)

        newer.mode = FakeIntegration.Mode.SIGNED_WITH_SECRET
        val failed = ping()
        assertEquals("The receiver took the ping, but its answer's signature is missing or wrong", failed.exceptionOrNull()?.message)
        val row = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).maxByOrNull { it.timestamp }!!
        assertFalse(row.success)
        assertEquals(200, row.statusCode)

        // 1.3.0 names signed_body, so an answer of it without any signature no longer confirms.
        newer.mode = FakeIntegration.Mode.NO_SIGNATURE
        assertTrue(ping().isFailure)
        // Too old for Receive is still a signed answer to this ping.
        newer.mode = FakeIntegration.Mode.STALE
        assertTrue(ping().isSuccess)

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        assertTrue("a receiver that is not the integration is not checked", ping().isSuccess)
        assertEquals(0, PendingSyncStore.forContext(context).size())
    }

    /**
     * T37. A second URL gets the same payload without the writeback block, and its answer,
     * however well signed, is never read: nothing it offers is written.
     */
    @Test
    fun otherUrlsAnswerIsIgnored() = runBlocking {
        receiveSetup()
        val other = FakeIntegration(TestSetup.HEALTH_SECRET)
        receiver.route("/api/webhook/ci-other", other::handle)
        prefs.setHealthWebhookUrls(listOf(receiver.url(TestSetup.HEALTH_PATH), receiver.url("/api/webhook/ci-other")))
        other.offer(FakeIntegration.weight("sensor.not_the_source@1", 99.9, ago(10)))
        fixture.insert(fixture.steps(12, ago(30), ago(20)))

        TestSetup.syncManager().performSync().getOrThrow()

        val source = Conservation.parse(receiver.to(TestSetup.HEALTH_PATH).single().text)
        val plain = Conservation.parse(receiver.to("/api/webhook/ci-other").single().text)
        assertNull(plain["writeback"])
        assertEquals("the same payload apart from the block", JsonObject(source - "writeback"), plain)
        assertEquals(emptyList<WeightRecord>(), fixture.read(WeightRecord::class))
    }

    /**
     * T38. Readings the app will not write are reported back per reading, in the next request,
     * as an id and a code and never a value: a type not asked for, values out of the app's
     * bounds, too old, in the future, a type the app does not know. With "Accept older
     * measurements" on, the old one is written.
     *
     * Report 03 expected a blood pressure of 250/150 (inside the app's bounds) to be refused
     * by Health Connect's own record bounds; on this image it was written and acknowledged, so
     * the case here is one outside the app's bounds.
     */
    @Test
    fun perReadingFailuresAreReportedBack() = runBlocking {
        receiveSetup()
        integration.offerUnrequested = true
        val now = Instant.now()
        integration.offer(
            FakeIntegration.reading("sensor.height@1", "height", ago(10), mapOf("meters" to 1.8)),
            FakeIntegration.weight("sensor.zero@1", 0.0, ago(10)),
            FakeIntegration.bloodPressure("sensor.bp_high@1", 310.0, 150.0, ago(10)),
            FakeIntegration.weight("sensor.old@1", 80.0, now.minus(Duration.ofDays(31))),
            FakeIntegration.weight("sensor.future@1", 80.0, now.plus(Duration.ofMinutes(10))),
            FakeIntegration.reading("sensor.bmi@1", "bmi", ago(10), mapOf("value" to 24.0))
        )

        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(emptyList<WeightRecord>(), fixture.read(WeightRecord::class, from = now.minus(Duration.ofDays(40))))
        TestSetup.syncManager().performSync().getOrThrow()

        val failed = integration.seen.last().failed().toMap()
        assertEquals(
            mapOf(
                "sensor.height@1" to "permission_denied",
                "sensor.zero@1" to "out_of_range",
                "sensor.bp_high@1" to "out_of_range",
                "sensor.old@1" to "too_old",
                "sensor.future@1" to "invalid",
                "sensor.bmi@1" to "unsupported_type"
            ),
            failed
        )
        val entries = integration.seen.last().writeback!!.arr("failed").orEmpty().map { (it as JsonObject).keys }
        assertTrue("an id and a code, never a value", entries.all { it == setOf("id", "code") })

        prefs.setReceiveOlderMeasurements(true)
        integration.offer(FakeIntegration.weight("sensor.old@2", 80.0, now.minus(Duration.ofDays(31))))
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(listOf("sensor.old@2"), fixture.read(WeightRecord::class, from = now.minus(Duration.ofDays(40))).map { it.metadata.clientRecordId })
    }

    /** T39. The same reading again is acknowledged, not written twice; a higher version overwrites. */
    @Test
    fun resendIsAckedAndHigherVersionWins() = runBlocking {
        receiveSetup()
        val id = "sensor.scale_weight@4"
        val time = ago(10)
        integration.offer(FakeIntegration.weight(id, 80.0, time))
        TestSetup.syncManager().performSync().getOrThrow()
        TestSetup.syncManager().performSync().getOrThrow() // the ack goes out, the queue empties

        integration.offer(FakeIntegration.weight(id, 80.0, time))
        TestSetup.syncManager().performSync().getOrThrow()
        val skipped = ReceiveLogLine.decode(inRows().first().rawPayload).orEmpty()
        assertEquals(listOf("skipped" to "already written"), skipped.map { it.outcome to it.reason })
        assertEquals(1, fixture.read(WeightRecord::class).size)

        TestSetup.syncManager().performSync().getOrThrow() // acked again
        integration.offer(FakeIntegration.weight(id, 81.0, time, version = 2))
        TestSetup.syncManager().performSync().getOrThrow()
        val record = fixture.read(WeightRecord::class).single()
        assertEquals(81.0, record.weight.inKilograms, 0.001)
        assertEquals(2L, record.metadata.clientRecordVersion)
        assertEquals(2L, prefs.getWriteBackLedger().knownVersion(id))
    }

    /**
     * T40. A backlog of 450 readings drains in one sync, three requests of 200, 200 and 50;
     * an integration that always says more gets exactly one request plus five follow-ups.
     */
    @Test
    fun moreDrainsInFollowUps() = runBlocking {
        receiveSetup()
        integration.offer(*Array(450) { i -> FakeIntegration.weight("sensor.backlog@$i", 70.0 + i / 100.0, ago(2000L - i)) })

        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(3, integration.seen.size)
        assertEquals(450, fixture.read(WeightRecord::class).size)

        integration.endless = { round -> FakeIntegration.weight("sensor.endless@$round", 75.0, ago(100L - round)) }
        val before = integration.seen.size
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals("one request and five follow-ups", 6, integration.seen.size - before)
    }

    /**
     * T41. The permission lookup hangs from the moment the answer is on its way: every offered
     * reading is failed as hc_unavailable, never permission_denied, and the integration
     * offers them again; once Health Connect answers, they are written.
     */
    @LargeTest
    @Test
    fun permissionLookupThatHangsIsHcUnavailable() = runBlocking {
        receiveSetup()
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        val sync = HealthSyncManager(context, HealthConnectManager(context) { slow })
        integration.offer(FakeIntegration.weight("sensor.scale_weight@5", 80.0, ago(10)))
        integration.onRequest = { slow.held += HcCall.GRANTED_PERMISSIONS }

        sync.performSync().getOrThrow()

        assertEquals("Receive: Health Connect did not answer the permission check", inRows().single().errorMessage)
        assertEquals(emptyList<WeightRecord>(), fixture.read(WeightRecord::class))
        integration.onRequest = null
        slow.held.clear()
        sync.performSync().getOrThrow()
        assertEquals(listOf("sensor.scale_weight@5" to "hc_unavailable"), integration.seen[1].failed())
        assertEquals(1, fixture.read(WeightRecord::class).size)
    }

    /** T42. An insert that never returns: after the 20 s write budget the readings are hc_unavailable, the ledger untouched. */
    @LargeTest
    @Test
    fun insertThatHangsRespectsTheWriteBudget() = runBlocking {
        receiveSetup()
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        slow.held += HcCall.INSERT_RECORDS
        integration.offer(FakeIntegration.weight("sensor.scale_weight@6", 80.0, ago(10)))

        val started = System.currentTimeMillis()
        HealthSyncManager(context, HealthConnectManager(context) { slow }).performSync().getOrThrow()
        val took = System.currentTimeMillis() - started

        assertTrue("took $took ms, the budget is 20 s", took in 19_000..30_000)
        assertEquals(WriteBackLedger.EMPTY, prefs.getWriteBackLedger())
        assertEquals(listOf(FailedReading("sensor.scale_weight@6", "hc_unavailable")), prefs.getWriteBackReport().failed)
    }
}
