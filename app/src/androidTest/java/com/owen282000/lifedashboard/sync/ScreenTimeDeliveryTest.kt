package com.owen282000.lifedashboard.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.AppFilterMode
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.ScreenTimeAppFilter
import com.owen282000.lifedashboard.ScreenTimeManager
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.Hmac
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.ScreenTimeUse
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Screen Time over the webhook, with the emulator's own usage (ScreenTimeUse). */
@RunWith(AndroidJUnit4::class)
class ScreenTimeDeliveryTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context

    /**
     * T25. One POST to the Screen Time URL with that section's secret and headers, the device,
     * a sequence and no writeback block even with Receive on, one to eight days with unique
     * dates and every app at least a minute, valid against the schema. A second sync sends the
     * week again with a higher sequence. Without usage access the sync says so.
     */
    @Test
    fun screenTimePayload() = runBlocking {
        ScreenTimeUse.ensureToday()
        TestSetup.health(receiver, setOf(STEPS), receive = setOf(WriteBackType.WEIGHT))
        TestSetup.screenTime(receiver)

        ScreenTimeSyncManager(context).performSync().getOrThrow()

        val post = receiver.exchanges.single()
        assertEquals(TestSetup.SCREEN_PATH, post.path)
        assertEquals(Hmac.requestSignature(TestSetup.SCREEN_SECRET, post.body), post.header("X-Signature"))
        assertEquals("ci-key-screen", post.header("X-Api-Key"))
        assertEquals(emptyList<String>(), Schema.errors(post.text))
        val body = Conservation.parse(post.text)
        assertEquals("screen_time", body.str("source"))
        assertEquals("${Build.MANUFACTURER} ${Build.MODEL}", body.str("device"))
        val sequence = body.num("sequence")!!.toLong()
        assertNull(body["writeback"])
        val days = body.arr("screen_time").orEmpty().map { it as JsonObject }
        assertTrue("1 to 8 days, got ${days.size}", days.size in 1..8)
        assertEquals(days.size, days.map { it.str("date") }.toSet().size)
        days.flatMap { it.arr("apps").orEmpty() }.forEach { app ->
            assertTrue("every app at least a minute", (app as JsonObject).num("minutes")!!.toLong() >= 1)
        }

        ScreenTimeSyncManager(context).performSync().getOrThrow()
        assertEquals("the week goes out again", 2, receiver.exchanges.size)
        val again = Conservation.parse(receiver.exchanges.last().text).num("sequence")!!.toLong()
        assertTrue("a newer week has a higher sequence", again > sequence)

        ScreenTimeUse.shell("appops set ${context.packageName} GET_USAGE_STATS deny")
        try {
            assertEquals("Usage stats permission not granted", ScreenTimeSyncManager(context).performSync().exceptionOrNull()?.message)
        } finally {
            ScreenTimeUse.allowUsageAccess()
        }
    }

    /**
     * Issue #63. The app ScreenTimeUse brings to the front (Settings) left out: it is in the
     * picker's apps, but nowhere in the payload, the day's total still counts it, and the sum of
     * what is sent goes out next to it. Then only that app: it is all the payload holds.
     */
    @Test
    fun anAppFilteredOutNeverLeavesThePhone() = runBlocking {
        ScreenTimeUse.ensureToday()
        TestSetup.screenTime(receiver)
        val prefs = context.appPreferences()
        val settings = "com.android.settings"
        assertTrue("the picker offers what was used", ScreenTimeManager(context, prefs).recentApps().any { it.packageName == settings })
        try {
            prefs.setScreenTimeAppFilter(ScreenTimeAppFilter(AppFilterMode.BLOCKLIST, setOf(settings)))
            ScreenTimeSyncManager(context).performSync().getOrThrow()
            val blocked = receiver.exchanges.last().text
            assertEquals(emptyList<String>(), Schema.errors(blocked))
            assertFalse("the app left out is not in the payload", blocked.contains(settings))
            val body = Conservation.parse(blocked)
            assertEquals("blocklist", body.str("app_filter"))
            body.arr("screen_time").orEmpty().map { it as JsonObject }.forEach { day ->
                val filtered = day.num("filtered_screen_time_minutes")!!.toLong()
                assertTrue("the total counts every app", day.num("total_screen_time_minutes")!!.toLong() >= filtered)
                // Each app's minutes are rounded down on their own, so their sum can fall short by one per app.
                val apps = day.arr("apps").orEmpty().map { (it as JsonObject).num("minutes")!!.toLong() }
                assertTrue("the sum of what is sent: $filtered vs $apps", filtered in apps.sum()..(apps.sum() + apps.size))
            }

            prefs.setScreenTimeAppFilter(ScreenTimeAppFilter(AppFilterMode.ALLOWLIST, setOf(settings)))
            ScreenTimeSyncManager(context).performSync().getOrThrow()
            val only = Conservation.parse(receiver.exchanges.last().text)
            assertEquals("allowlist", only.str("app_filter"))
            val packages = only.arr("screen_time").orEmpty().flatMap { (it as JsonObject).arr("apps").orEmpty() }.map { (it as JsonObject).str("package") }.toSet()
            assertEquals(setOf(settings), packages)
        } finally {
            prefs.setScreenTimeAppFilter(ScreenTimeAppFilter.ALL)
        }
    }

    /**
     * T54. Two Screen Time syncs started together, as the tile and the worker can, take turns:
     * with every answer held for a second, the second POST arrives only after the first was
     * answered, and the sequences rise in the order they arrived.
     */
    @Test
    fun concurrentSyncsTakeTurns() = runBlocking {
        ScreenTimeUse.ensureToday()
        TestSetup.screenTime(receiver)
        // Arrival, answer and sequence of each POST, noted in the handler itself because the
        // receiver logs an exchange only once it has been answered.
        val posts = CopyOnWriteArrayList<Triple<Long, Long, Long>>()
        receiver.route(TestSetup.SCREEN_PATH) { request ->
            val arrived = System.currentTimeMillis()
            Thread.sleep(1_000)
            val sequence = Conservation.parse(request.body!!.utf8()).num("sequence")!!.toLong()
            posts += Triple(arrived, System.currentTimeMillis(), sequence)
            MockResponse(code = 200)
        }

        List(2) { async(Dispatchers.IO) { ScreenTimeSyncManager(context).performSync() } }
            .awaitAll()
            .forEach { it.getOrThrow() }

        assertEquals(2, posts.size)
        val (first, second) = posts.sortedBy { it.first }
        assertTrue("the second POST waits for the first answer", second.first >= first.second)
        assertTrue("sequences rise in arrival order", second.third > first.third)
    }
}
