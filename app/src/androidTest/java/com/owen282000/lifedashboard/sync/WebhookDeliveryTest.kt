package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.SLEEP
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.WebhookSupport
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Hmac
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.obj
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** The ordinary delivery: what one sync reads from Health Connect and how it goes over the wire. */
@RunWith(AndroidJUnit4::class)
class WebhookDeliveryTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    /**
     * T05. One sync of three types: every seeded record arrives exactly once, in one signed
     * POST that the documented schema accepts, with the headers and bookkeeping the app
     * promises. The signature is recomputed with the suite's own HMAC code.
     */
    @Test
    fun healthSyncSendsSignedValidPayload() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS, HEART_RATE, WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, HeartRateRecord::class, WeightRecord::class)
        val beats = listOf(ago(25) to 71L, ago(24) to 74L, ago(23) to 78L)
        val (stepsId, heartRateId, weightId) = fixture.insert(
            fixture.steps(1234, ago(40), ago(30)),
            fixture.heartRate(beats),
            fixture.weight(78.4, ago(20))
        )
        val heartRateUuids = beats.map { (time, _) -> "$heartRateId#${time.toEpochMilli()}" }
        val seeded = setOf(stepsId, weightId) + heartRateUuids

        val started = Instant.now()
        val result = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(mapOf(STEPS to 1, HEART_RATE to 3, WEIGHT to 1), webhookCount = 1), result)
        val post = receiver.exchanges.single()
        assertEquals("POST", post.request.method)
        assertEquals(TestSetup.HEALTH_PATH, post.path)
        assertEquals("application/json; charset=utf-8", post.header("Content-Type"))
        assertEquals("ci-key-health", post.header("X-Api-Key"))
        assertEquals("session=ci", post.header("Cookie"))
        assertTrue("User-Agent is OkHttp's own", post.header("User-Agent").orEmpty().startsWith("okhttp/"))
        assertEquals(Hmac.requestSignature(TestSetup.HEALTH_SECRET, post.body), post.header("X-Signature"))
        assertEquals(emptyList<String>(), Schema.errors(post.text))

        val body = Conservation.parse(post.text)
        Conservation.assertExactlyOnce(seeded, listOf(body))
        assertEquals("health_connect", body.str("source"))
        assertEquals(TestSetup.versionName(), body.str("app_version"))
        val timestamp = Instant.parse(body.str("timestamp"))
        assertTrue("timestamp $timestamp is the time of the sync", Duration.between(started, timestamp).abs() < Duration.ofMinutes(1))
        assertEquals("1", body.num("sequence"))
        Conservation.records(body).forEach { (key, uuid, source) ->
            assertTrue("$key record has a uuid", !uuid.isNullOrEmpty())
            assertEquals("$key record names its source", context.packageName, source)
        }
        assertEquals(heartRateUuids.toSet(), Conservation.records(body).filter { it.first == "heart_rate" }.map { it.second }.toSet())
        val steps = body.obj("_diagnostics")?.obj("steps")
        assertEquals("true", steps?.num("permission_granted"))
        assertEquals("0", steps?.num("own_records_skipped"))
        assertNull("no writeback block while Receive is off", body["writeback"])

        val log = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertTrue(log.success)
        assertEquals(200, log.statusCode)
        assertEquals(LogDirection.OUT.name, log.direction)
        assertEquals(5, SyncStatusStore.read(context, LogType.HEALTH_CONNECT).recordsToday)
    }

    /**
     * T06. daily_totals carries today's steps exactly as Health Connect's own aggregate
     * counts them (the app is in the priority list through the permission rule), and the key
     * is gone when daily totals are switched off.
     */
    @Test
    fun dailyTotalsMatchHealthConnectAggregate() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        // Inside today whatever the hour: awayFromMidnight guarantees it is past 00:03. (The
        // first version put the record 30 to 40 minutes back and failed just after midnight.)
        fixture.insert(fixture.steps(1234, ago(2), ago(1)))

        TestSetup.syncManager().performSync().getOrThrow()

        val today = LocalDate.now().toString()
        val totals = Conservation.parse(receiver.exchanges.single().text).arr("daily_totals").orEmpty().map { it as JsonObject }
        val todayTotal = totals.single { it.str("date") == today }
        val aggregate = fixture.client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(LocalDate.now().atStartOfDay(), LocalDateTime.now()))
        )[StepsRecord.COUNT_TOTAL]
        assertEquals(1234L, aggregate)
        assertEquals("1234", todayTotal.num("steps"))

        context.appPreferences().setIncludeDailyTotals(false)
        fixture.insert(fixture.steps(10, ago(15), ago(10)))
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()
        assertNull(Conservation.parse(receiver.since(mark).single().text)["daily_totals"])
    }

    /**
     * T07. A second sync with nothing new sends nothing; the watermark is the newest
     * modification time delivered; a record measured two days ago but written now still
     * goes out, because the sync filters on modification time (the Zepp and Garmin rule).
     */
    @Test
    fun secondSyncIsNoDataThenLateRecordArrives() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(500, ago(40), ago(30)))
        TestSetup.syncManager().performSync().getOrThrow()
        val newest = fixture.read(StepsRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() }
        assertEquals(newest, context.appPreferences().getHealthLastSyncTimestamp(STEPS))

        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())
        assertEquals(1, receiver.exchanges.size)

        val (late) = fixture.insert(fixture.steps(42, ago(2 * 24 * 60 + 10), ago(2 * 24 * 60)))
        TestSetup.syncManager().performSync().getOrThrow()
        Conservation.assertExactlyOnce(setOf(late), listOf(Conservation.parse(receiver.exchanges.last().text)))
    }

    /** T08. 250 weights over the cap of 200: two POSTs in one sync, consecutive sequences, disjoint, all 250. */
    @Test
    fun capSplitsIntoPasses() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        val ids = fixture.insertInBatches(List(250) { i -> fixture.weight(70.0 + i / 100.0, ago(600L - i)) }, 50)

        TestSetup.syncManager().performSync().getOrThrow()

        val posts = receiver.exchanges.map { Conservation.parse(it.text) }
        assertEquals(listOf(200, 50), posts.map { Conservation.records(it).size })
        assertEquals(listOf("1", "2"), posts.map { it.num("sequence") })
        Conservation.assertExactlyOnce(ids.toSet(), posts)
    }

    /** T09. Plain HTTP without the opt-in: nothing is sent, the reason is logged, the payload queued. */
    @Test
    fun plainHttpBlockedWithoutOptIn() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        context.appPreferences().setAllowHttpWebhooks(false)
        fixture.insert(fixture.steps(12, ago(30), ago(20)))

        val result = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Queued(1), result)
        assertEquals(0, receiver.exchanges.size)
        assertEquals(WebhookSupport.CLEARTEXT_BLOCKED_MESSAGE, context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single().errorMessage)
    }

    /** T10 (1.19.0). A client certificate that is not there: no request, one failed row per URL. */
    @Test
    fun missingClientCertificateIsLoggedPerUrl() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        val prefs = context.appPreferences()
        prefs.setHealthWebhookUrls(listOf(receiver.url(TestSetup.HEALTH_PATH), receiver.url("/api/webhook/ci-second")))
        prefs.setClientCertAlias("ci-missing")
        fixture.insert(fixture.steps(12, ago(30), ago(20)))

        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(0, receiver.exchanges.size)
        val rows = prefs.getWebhookLogs(LogType.HEALTH_CONNECT)
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.success })
        assertEquals(setOf(receiver.url(TestSetup.HEALTH_PATH), receiver.url("/api/webhook/ci-second")), rows.map { it.url }.toSet())
    }

    /**
     * T1 of report 04, the conservation law on the ordinary path: 2500 steps over six days,
     * more than one page of Health Connect and more than two passes of the cap, plus a night
     * with stages. Every uuid arrives exactly once over all payloads, each signed; the newest
     * record is there, the stages arrive as names, and the next sync has nothing.
     */
    @Test
    fun everySeededRecordArrivesExactlyOnce() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, SLEEP))
        fixture.assertNoForeignRecords(StepsRecord::class, SleepSessionRecord::class)
        val sixDays = 6 * 24 * 60L
        val steps = fixture.insertInBatches(List(2500) { i -> fixture.steps(10L + i % 90, ago(sixDays - i * 3 + 2), ago(sixDays - i * 3)) }, 500)
        val nightEnd = ago(sixDays - 600)
        val nightStart = nightEnd.minus(Duration.ofHours(7))
        val (night) = fixture.insert(
            fixture.sleep(
                nightStart, nightEnd,
                listOf(
                    Triple(nightStart, nightStart.plus(Duration.ofHours(2)), SleepSessionRecord.STAGE_TYPE_LIGHT),
                    Triple(nightStart.plus(Duration.ofHours(2)), nightStart.plus(Duration.ofHours(4)), SleepSessionRecord.STAGE_TYPE_DEEP),
                    Triple(nightStart.plus(Duration.ofHours(4)), nightEnd, SleepSessionRecord.STAGE_TYPE_REM)
                )
            )
        )

        TestSetup.syncManager().performSync().getOrThrow()

        val exchanges = receiver.exchanges
        exchanges.forEach { assertEquals(Hmac.requestSignature(TestSetup.HEALTH_SECRET, it.body), it.header("X-Signature")) }
        val posts = exchanges.map { Conservation.parse(it.text) }
        Conservation.assertExactlyOnce((steps + night).toSet(), posts)
        val newestEnd = fixture.read(StepsRecord::class).maxOf { it.endTime }
        assertTrue("the newest record is there", posts.any { p -> p.arr("steps").orEmpty().any { (it as JsonObject).str("end_time") == newestEnd.toString() } })
        val stages = posts.flatMap { it.arr("sleep").orEmpty() }.flatMap { (it as JsonObject).arr("stages").orEmpty() }.map { (it as JsonObject).str("stage") }
        assertEquals(listOf("light", "deep", "rem"), stages)
        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())
    }

    /**
     * T2 of report 04, the late upload: after a sync that found everything, a record is
     * written whose measurement lies six hours back, behind the record times already
     * delivered. It goes out exactly once, and the sync after that has nothing.
     */
    @Test
    fun lateUploadArrivesExactlyOnce() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        fixture.insert(fixture.weight(80.0, ago(30)))
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())

        val (late) = fixture.insert(fixture.weight(79.5, ago(6 * 60)))
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()

        Conservation.assertExactlyOnce(setOf(late), receiver.since(mark).map { Conservation.parse(it.text) })
        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())
    }

    /**
     * T3 of report 04, a backlog in one run: 5000 heart rate samples in 50 records written by
     * one insert, as a watch that was offline for a while uploads them. One sync delivers
     * every sample exactly once, within a minute, and the next sync has nothing.
     */
    @Test
    fun backlogDrainsInOneRun() = runBlocking {
        TestSetup.health(receiver, setOf(HEART_RATE))
        fixture.assertNoForeignRecords(HeartRateRecord::class)
        val start = ago(3 * 60)
        val records = List(50) { r ->
            fixture.heartRate(List(100) { s -> start.plusSeconds(r * 120L + s) to (60L + (r + s) % 40) })
        }
        val ids = fixture.insert(*records.toTypedArray())
        val seeded = records.zip(ids).flatMap { (record, id) -> record.samples.map { "$id#${it.time.toEpochMilli()}" } }.toSet()
        val lastModified = fixture.read(HeartRateRecord::class).map { it.metadata.lastModifiedTime }.distinct()
        Witness.save("backlog-modification-times.txt", "${lastModified.size} distinct lastModifiedTime values over 50 records: $lastModified")

        val started = System.currentTimeMillis()
        TestSetup.syncManager().performSync().getOrThrow()
        val took = System.currentTimeMillis() - started

        val posts = receiver.exchanges.map { Conservation.parse(it.text) }
        Witness.save("backlog-posts.txt", posts.map { Conservation.records(it).size }.toString())
        Conservation.assertExactlyOnce(seeded, posts)
        assertTrue("took $took ms", took < 60_000)
        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())
    }

    /**
     * T3 of report 04, the other half: every POST of that backlog stays under the per-sync
     * cap of 1000 heart rate samples, which exists to bound payload size and memory (#38, a
     * crash at 230,000 samples).
     *
     * Red on main, a new finding (F7): Health Connect gives the records of one insert the
     * same lastModifiedTime (two distinct values over 50 records here), and the cap extends
     * every batch with all records sharing its boundary time so the strict watermark never
     * skips one. The backlog went out as two POSTs of 3400 and 1600 samples (in either order,
     * depending on where the millisecond ticked). A watch that
     * uploads a large backlog in one insert makes one unbounded payload.
     */
    @Test
    fun backlogPostsStayUnderTheCap() = runBlocking {
        TestSetup.health(receiver, setOf(HEART_RATE))
        fixture.assertNoForeignRecords(HeartRateRecord::class)
        val start = ago(3 * 60)
        val records = List(50) { r ->
            fixture.heartRate(List(100) { s -> start.plusSeconds(r * 120L + s) to (60L + (r + s) % 40) })
        }
        val ids = fixture.insert(*records.toTypedArray())
        val seeded = records.zip(ids).flatMap { (record, id) -> record.samples.map { "$id#${it.time.toEpochMilli()}" } }.toSet()

        TestSetup.syncManager().performSync().getOrThrow()

        val posts = receiver.exchanges.map { Conservation.parse(it.text) }
        Conservation.assertExactlyOnce(seeded, posts)
        val sizes = posts.map { Conservation.records(it).size }
        assertTrue("every POST within the cap of 1000 samples: $sizes", sizes.all { it <= 1000 })
    }

    /**
     * T5 of report 04, at the level where it went wrong: with every data type switched on, the
     * payload's diagnostics say permission_granted for all of them, checked against Health
     * Connect's own list rather than against a string the app built (six months of HRV were
     * lost to a permission name that did not match).
     */
    @Test
    fun everyTypeReportsItsPermissionGranted() = runBlocking {
        TestSetup.health(receiver, HealthDataType.entries.toSet())
        fixture.insert(fixture.steps(12, ago(30), ago(20)))

        TestSetup.syncManager().performSync().getOrThrow()

        val granted = fixture.client.permissionController.getGrantedPermissions()
        HealthDataType.entries.forEach { assertTrue("read $it granted", HealthPermission.getReadPermission(it.recordClass) in granted) }
        val diagnostics = Conservation.parse(receiver.exchanges.single().text).obj("_diagnostics")!!
        assertEquals(HealthDataType.entries.size, diagnostics.size)
        diagnostics.forEach { (type, diag) -> assertEquals("$type permission_granted", "true", (diag as JsonObject).num("permission_granted")) }
    }
}
