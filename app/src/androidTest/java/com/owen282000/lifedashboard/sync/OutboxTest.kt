package com.owen282000.lifedashboard.sync

import android.content.Context
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingDrainer
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Hmac
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.ScreenTimeUse
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The outbox: a payload the receiver could not take is kept on disk and delivered first next time. */
@RunWith(AndroidJUnit4::class)
class OutboxTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    /**
     * T11. Three 503s: the app tries three times with its 1 s and 2 s backoff, queues the payload
     * byte for byte, logs the failure and still moves the watermarks, because the outbox now
     * owns delivery. The next sync delivers that same payload, same sequence and signature,
     * and nothing twice: every seeded record reaches the receiver in a 2xx exactly once.
     *
     * Whether the drain resets the failure streak is left to F5 (P2-4, fase 3), which changes it.
     */
    @Test
    fun transient503QueuesThenDrains() = runBlocking {
        HcFixture.awayFromMidnight()
        TestSetup.health(receiver, setOf(STEPS, HEART_RATE, WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, HeartRateRecord::class, WeightRecord::class)
        val beats = listOf(ago(25) to 71L, ago(24) to 74L, ago(23) to 78L)
        val (stepsId, heartRateId, weightId) = fixture.insert(
            fixture.steps(1234, ago(40), ago(30)),
            fixture.heartRate(beats),
            fixture.weight(78.4, ago(20))
        )
        val seeded = setOf(stepsId, weightId) + beats.map { (time, _) -> "$heartRateId#${time.toEpochMilli()}" }
        receiver.respond(TestSetup.HEALTH_PATH, 503, 503, 503, 200)

        val first = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Queued(5), first)
        val attempts = receiver.exchanges
        assertEquals("three attempts, then give up", 3, attempts.size)
        assertTrue(attempts.all { it.responseCode == 503 })
        attempts.forEach { assertArrayEquals("every retry is the same request", attempts[0].body, it.body) }
        val backoff = attempts.last().receivedAtMs - attempts.first().receivedAtMs
        assertTrue("backoff of 1 s and 2 s, took $backoff ms", backoff in 2_800..8_000)

        val queued = PendingSyncStore.forContext(context).peekAll().single()
        assertEquals("the outbox holds the payload byte for byte", attempts.last().text, queued.payload)
        val log = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertTrue(log.errorMessage.orEmpty(), log.errorMessage.orEmpty().startsWith("Failed after 3 attempts (transient errors): HTTP 503"))
        assertEquals(1, streak(context))
        assertWatermarksAtNewestRecord()

        val mark = receiver.exchanges.size
        val second = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.NoData, second)
        val drained = receiver.since(mark).single()
        assertEquals(200, drained.responseCode)
        assertArrayEquals("the drain sends the queued bytes", attempts.last().body, drained.body)
        assertEquals("and the original signature", attempts.last().header("X-Signature"), drained.header("X-Signature"))
        assertEquals(Hmac.requestSignature(TestSetup.HEALTH_SECRET, drained.body), drained.header("X-Signature"))
        assertEquals(0, PendingSyncStore.forContext(context).size())

        val delivered = receiver.exchanges.filter { it.responseCode in 200..299 }.map { Conservation.parse(it.text) }
        Conservation.assertExactlyOnce(seeded, delivered)
    }

    private fun streak(context: Context): Int =
        context.getSharedPreferences("life_dashboard_prefs", Context.MODE_PRIVATE).getInt("sync_failure_streak_HEALTH_CONNECT", 0)

    /** The failed sync moved each type's watermark to its newest record's modification time. */
    private fun assertWatermarksAtNewestRecord() {
        val newest: Map<HealthDataType, Long> = mapOf(
            STEPS to fixture.read(StepsRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() },
            HEART_RATE to fixture.read(HeartRateRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() },
            WEIGHT to fixture.read(WeightRecord::class).maxOf { it.metadata.lastModifiedTime.toEpochMilli() }
        )
        newest.forEach { (type, millis) ->
            assertEquals("watermark of $type", millis, context.appPreferences().getHealthLastSyncTimestamp(type))
        }
    }

    /** T12. A 503 followed by a 200 within one sync: delivered, and the log says it recovered. */
    @Test
    fun recoveredOnRetryNote() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.insert(fixture.steps(12, ago(30), ago(20)))
        receiver.respond(TestSetup.HEALTH_PATH, 503, 200)

        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(2, receiver.exchanges.size)
        val log = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertTrue(log.success)
        assertEquals("Recovered on attempt 2 of 3", log.note)
        assertEquals(0, PendingSyncStore.forContext(context).size())
    }

    /** How many requests one sync makes against a receiver that always answers [code]; checks the log text too. */
    private fun attemptsFor(code: Int): Int = runBlocking {
        AppStateRule.reset()
        TestSetup.health(receiver, setOf(STEPS))
        fixture.insert(fixture.steps(12, ago(30), ago(20)))
        receiver.respond(TestSetup.HEALTH_PATH, code)
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()
        val message = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single().errorMessage.orEmpty()
        if (code in 400..499 && code != 408 && code != 429) assertTrue("$code: $message", message.endsWith("(permanent error, not retried)"))
        receiver.since(mark).size
    }

    /** T13. Client errors are not retried, transient ones three times. */
    @Test
    fun permanentClientErrorsAreNotRetried() {
        val codes = listOf(400, 401, 404, 429, 500, 503)
        assertEquals(mapOf(400 to 1, 401 to 1, 404 to 1, 429 to 3, 500 to 3, 503 to 3), codes.associateWith { attemptsFor(it) })
    }

    /**
     * T13 for 408, a new finding (F8): a request timeout is transient and tried three times,
     * so the receiver should see three requests. Red on main, it sees six: OkHttp retries a
     * 408 once by itself inside every attempt (RetryAndFollowUpInterceptor, which only skips
     * that when retryOnConnectionFailure is off), so the app's count is doubled.
     */
    @Test
    fun requestTimeoutIsTriedThreeTimes() {
        assertEquals(3, attemptsFor(408))
    }

    /**
     * F6, the rule for refusals in the outbox. The drain stops at the first payload that fails
     * again, to keep the order while a receiver is down or misconfigured (401, 403, a 404 from a
     * mistyped URL): a correction delivers the whole queue. A refusal of the payload itself
     * (400, 413, 422) is skipped instead, so it cannot hold back what was queued after it, and
     * stays queued for a week, since a bug in the receiver answers 400 too; after that it is
     * dropped with a log row. Red on main: the drain stopped at a 422 and never got further.
     */
    @Test
    fun aRefusedPayloadIsSkippedNotStoppedAt() = runBlocking {
        for (code in listOf(401, 403, 404, 422)) {
            AppStateRule.reset()
            TestSetup.health(receiver, setOf(STEPS))
            fixture.insert(fixture.steps(12, ago(30), ago(20)))
            receiver.respond(TestSetup.HEALTH_PATH, code)
            TestSetup.syncManager().performSync().getOrThrow()
            assertEquals("a $code at the first attempt is queued", 1, PendingSyncStore.forContext(context).size())
        }

        // Two payloads queued by a 503. When drained the first is refused, the second arrives.
        AppStateRule.reset()
        TestSetup.health(receiver, setOf(STEPS))
        receiver.respond(TestSetup.HEALTH_PATH, 503)
        fixture.insert(fixture.steps(1, ago(50), ago(45)))
        TestSetup.syncManager().performSync().getOrThrow()
        val second = fixture.insert(fixture.steps(2, ago(40), ago(35)))
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(2, PendingSyncStore.forContext(context).size())
        receiver.respond(TestSetup.HEALTH_PATH, 422, 200)
        var mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()
        val delivered = receiver.since(mark).drop(1).map { Conservation.records(Conservation.parse(it.text)).map { r -> r.second } }
        assertEquals(listOf(second), delivered)
        assertEquals("the refused one stays queued", 1, PendingSyncStore.forContext(context).size())

        // With a second URL that is down, the drain waits for it: nothing after the refused one goes out.
        AppStateRule.reset()
        TestSetup.health(receiver, setOf(STEPS))
        context.appPreferences().setHealthWebhookUrls(listOf(receiver.url(TestSetup.HEALTH_PATH), receiver.url("/api/webhook/ci-down")))
        receiver.respond(TestSetup.HEALTH_PATH, 503)
        receiver.respond("/api/webhook/ci-down", 503)
        fixture.insert(fixture.steps(1, ago(50), ago(45)))
        TestSetup.syncManager().performSync().getOrThrow()
        val held = fixture.insert(fixture.steps(2, ago(40), ago(35)))
        TestSetup.syncManager().performSync().getOrThrow()
        receiver.respond(TestSetup.HEALTH_PATH, 422)
        mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(2, PendingSyncStore.forContext(context).size())
        assertTrue(receiver.since(mark).none { exchange -> Conservation.records(Conservation.parse(exchange.text)).any { it.second in held } })

        // Refused for a week: dropped, with a log row that says so.
        AppStateRule.reset()
        TestSetup.health(receiver, setOf(STEPS))
        val weekAgo = System.currentTimeMillis() - PendingDrainer.REFUSED_MAX_AGE_MS - 60_000
        PendingSyncStore.forContext(context).enqueue("""{"timestamp":"2026-01-01T00:00:00Z"}""", "health_connect", LogType.HEALTH_CONNECT.name, 1, weekAgo)
        receiver.respond(TestSetup.HEALTH_PATH, 422)
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertTrue(context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).any { it.errorMessage.orEmpty().contains("dropped from the outbox") })
    }

    /**
     * T14. Two failed syncs with new data each, then a healthy one: the receiver gets the old
     * outbox item, the newer one, then the new payload, in that order and with a strictly
     * rising sequence. With two URLs of which one fails, one success is a delivery and
     * nothing is queued.
     */
    @Test
    fun drainKeepsOrderAndSequence() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        receiver.respond(TestSetup.HEALTH_PATH, 503)
        val first = fixture.insert(fixture.steps(1, ago(50), ago(45)))
        TestSetup.syncManager().performSync().getOrThrow()
        val second = fixture.insert(fixture.steps(2, ago(40), ago(35)))
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(2, PendingSyncStore.forContext(context).size())

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        val third = fixture.insert(fixture.steps(3, ago(30), ago(25)))
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()

        val delivered = receiver.since(mark).map { Conservation.parse(it.text) }
        assertEquals(listOf(first, second, third), delivered.map { Conservation.records(it).map { r -> r.second } })
        val sequences = delivered.map { it.num("sequence")!!.toLong() }
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.size, sequences.toSet().size)

        AppStateRule.reset()
        TestSetup.health(receiver, setOf(STEPS))
        context.appPreferences().setHealthWebhookUrls(listOf(receiver.url(TestSetup.HEALTH_PATH), receiver.url("/api/webhook/ci-down")))
        receiver.respond("/api/webhook/ci-down", 503)
        receiver.respond(TestSetup.HEALTH_PATH, 200)
        fixture.insert(fixture.steps(4, ago(20), ago(15)))
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals("one URL took it: delivered", 0, PendingSyncStore.forContext(context).size())
    }

    /** T15. A queued Screen Time payload is drained with the Screen Time secret and headers, not the health ones. */
    @Test
    fun screenTimeOutboxUsesScreenTimeSecret() = runBlocking {
        ScreenTimeUse.ensureToday()
        TestSetup.screenTime(receiver)
        TestSetup.health(receiver, setOf(STEPS))
        receiver.respond(TestSetup.SCREEN_PATH, 503)
        ScreenTimeSyncManager(context).performSync().getOrThrow()
        assertEquals(1, PendingSyncStore.forContext(context).size())

        receiver.respond(TestSetup.SCREEN_PATH, 200)
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow() // the health sync drains the outbox first

        val drained = receiver.since(mark).single { it.path == TestSetup.SCREEN_PATH }
        assertEquals(Hmac.requestSignature(TestSetup.SCREEN_SECRET, drained.body), drained.header("X-Signature"))
        assertEquals("ci-key-screen", drained.header("X-Api-Key"))
        assertEquals("screen_time", Conservation.parse(drained.text).str("source"))
    }

    /**
     * A full outbox drops its oldest Health Connect payload, never silently: a row in the Logs
     * tab with that payload's records, and a loss notification that the delivery after the
     * outage does not take away, since the records stay lost.
     */
    @Test
    fun aFullOutboxReportsWhatItDrops() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        val store = PendingSyncStore.forContext(context)
        val hourAgo = System.currentTimeMillis() - 60 * 60 * 1000
        repeat(PendingSyncStore.MAX_HEALTH_ITEMS) { i ->
            store.enqueue("""{"timestamp":"2026-01-01T00:00:00Z","sequence":$i}""", "health_connect", LogType.HEALTH_CONNECT.name, i + 1, hourAgo + i)
        }
        fixture.insert(fixture.steps(12, ago(30), ago(20)))
        receiver.respond(TestSetup.HEALTH_PATH, 503)

        TestSetup.syncManager().performSync().getOrThrow()

        val queued = store.peekAll()
        assertEquals(PendingSyncStore.MAX_HEALTH_ITEMS, queued.size)
        assertTrue("the oldest went", queued.none { it.createdAt == hourAgo })
        val row = context.appPreferences().getWebhookLogs(LogType.HEALTH_CONNECT).single { it.errorMessage.orEmpty().startsWith("Dropped from the outbox") }
        assertEquals("the dropped payload's records", 1, row.recordCount)
        assertTrue(row.rawPayload.orEmpty(), row.rawPayload.orEmpty().contains("\"sequence\":0"))
        assertTrue("a loss notification", lossNotificationShows())

        // Home Assistant is back. Only the sync's own payload is left to drain, to spare 700 posts.
        queued.filter { it.createdAt < hourAgo + PendingSyncStore.MAX_HEALTH_ITEMS }.forEach { store.remove(it.id) }
        receiver.respond(TestSetup.HEALTH_PATH, 200)
        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(0, store.size())
        assertEquals("the drain delivered, the streak is over", 0, TestSetup.streak("HEALTH_CONNECT"))
        assertTrue("the records are still lost, so the notification stays", lossNotificationShows())
    }

    private fun lossNotificationShows(): Boolean =
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == 4001 + 20 + LogType.HEALTH_CONNECT.ordinal }

    /**
     * Two failed Screen Time syncs leave one queued week, the newer one: it carries all 7 days,
     * so it replaces the week before it, and within the week nothing is reported lost.
     */
    @Test
    fun aFailedScreenTimeWeekReplacesTheQueuedOne() = runBlocking {
        ScreenTimeUse.ensureToday()
        TestSetup.screenTime(receiver)
        receiver.respond(TestSetup.SCREEN_PATH, 503)
        ScreenTimeSyncManager(context).performSync().getOrThrow()
        val first = Conservation.parse(PendingSyncStore.forContext(context).peekAll().single().payload).num("sequence")!!.toLong()

        ScreenTimeSyncManager(context).performSync().getOrThrow()

        val queued = PendingSyncStore.forContext(context).peekAll().single()
        assertTrue("the newer week is kept", Conservation.parse(queued.payload).num("sequence")!!.toLong() > first)
        assertTrue(context.appPreferences().getWebhookLogs(LogType.SCREEN_TIME).none { it.errorMessage.orEmpty().startsWith("Replaced in the outbox") })
    }

    /**
     * F5. A sync that only drains the outbox did deliver: the failure streak ends and "Last
     * sync" moves. Red on main: PendingDrainer tells neither SyncFailureNotifier nor
     * SyncStatusStore, so after an outage without new data the failure notification stays.
     */
    @Test
    fun drainEndsTheFailureStreak() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.insert(fixture.steps(12, ago(30), ago(20)))
        receiver.respond(TestSetup.HEALTH_PATH, 503)
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(1, TestSetup.streak("HEALTH_CONNECT"))
        val failedAt = SyncStatusStore.read(context, LogType.HEALTH_CONNECT)
        assertFalse(failedAt.lastSuccess)

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        Thread.sleep(5)
        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertEquals("the drain delivered, the streak is over", 0, TestSetup.streak("HEALTH_CONNECT"))
        val after = SyncStatusStore.read(context, LogType.HEALTH_CONNECT)
        assertTrue(after.lastSuccess)
        assertTrue(after.lastSyncMillis!! > failedAt.lastSyncMillis!!)
    }
}
