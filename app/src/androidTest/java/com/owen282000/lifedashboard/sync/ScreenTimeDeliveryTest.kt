package com.owen282000.lifedashboard.sync

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.WriteBackType
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

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
}
