package com.owen282000.lifedashboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * P2-16: a window that goes out again is sent whole, so a receiver can replace what it holds
 * instead of adding to it. A source that writes its last hour again on every export, under the
 * same record ids, otherwise doubles that hour on every receiver that follows the docs.
 */
class WholeWindowTest {

    private fun at(text: String) = Instant.parse(text)
    private fun steps(time: String, count: Double, source: String = "com.watch") = CarriedSample(at(time), count, source)

    private val hourly = mapOf(HealthDataType.STEPS to SeriesResolution.HOURLY)

    /** Sixty minutes of 10 steps from 08:00, everything a read of 08:00 to 12:00 holds. */
    private val hour = List(60) { steps("2026-09-14T08:%02d:00Z".format(it), 10.0) }
    private val coverage = ReadCoverage(at("2026-09-14T08:00:00Z"), at("2026-09-14T12:00:00Z"))

    private fun send(changed: List<CarriedSample>, whole: WholeContent?, carried: List<CarriedSample> = emptyList()): List<JsonObject> {
        val a = ResolutionApplier(
            hourly,
            carriedIn = if (carried.isEmpty()) emptyMap() else mapOf(HealthDataType.STEPS to carried),
            whole = whole?.let { mapOf(HealthDataType.STEPS to it) }.orEmpty()
        )
        a.bucketSeries(HealthDataType.STEPS, "steps", changed, at("2026-09-14T12:00:00Z"), ResolutionFamily.ACCUMULATED)
        return a.series.getValue("steps").map { it as JsonObject }
    }

    private fun JsonObject.total() = getValue("total").jsonPrimitive.content.toDouble()
    private fun JsonObject.count() = getValue("sample_count").jsonPrimitive.content.toInt()
    private fun JsonObject.complete() = this["complete"]?.jsonPrimitive?.content?.toBoolean() == true

    @Test
    fun `a window written again goes out with everything it holds, marked complete`() {
        // The source wrote ten of the minutes again: only those changed, the hour holds all sixty.
        val bucket = send(changed = hour.take(10), whole = WholeContent(hour, coverage)).single()
        assertEquals(600.0, bucket.total(), 0.0)
        assertEquals(60, bucket.count())
        assertTrue(bucket.complete())
    }

    @Test
    fun `an edit replaces the value instead of adding to it`() {
        val edited = hour.map { it.copy(value = 12.0) }
        val bucket = send(changed = edited, whole = WholeContent(edited, coverage)).single()
        assertEquals(720.0, bucket.total(), 0.0)
        assertTrue(bucket.complete())
    }

    @Test
    fun `a late record from another source is part of the whole window`() {
        val late = steps("2026-09-14T08:10:00Z", 300.0, "com.phone")
        val bucket = send(changed = listOf(late), whole = WholeContent(hour + late, coverage)).single()
        assertEquals(900.0, bucket.total(), 0.0)
        assertEquals(listOf("com.phone", "com.watch"), bucket.getValue("sources").let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a window the read did not hold whole goes out as before, unmarked`() {
        // The read kept only from 08:30, so 08:00 cannot be built whole: the changed records go out
        // as they are, for a receiver to combine, the way every bucket went out before.
        val partial = ReadCoverage(at("2026-09-14T08:30:00Z"), at("2026-09-14T12:00:00Z"))
        val bucket = send(changed = hour.take(10), whole = WholeContent(hour.drop(30), partial)).single()
        assertEquals(100.0, bucket.total(), 0.0)
        assertFalse(bucket.complete())
        assertFalse("complete" in bucket)
    }

    @Test
    fun `without anything held whole every bucket is unmarked`() {
        val bucket = send(changed = hour.take(10), whole = null).single()
        assertEquals(100.0, bucket.total(), 0.0)
        assertFalse(bucket.complete())
    }

    @Test
    fun `a held window that closed is sent whole even when nothing in it changed this sync`() {
        // Held from the last sync while it was filling; this sync read nothing new for it.
        val bucket = send(changed = emptyList(), whole = WholeContent(hour, coverage), carried = hour.take(30)).single()
        assertEquals(600.0, bucket.total(), 0.0)
        assertTrue(bucket.complete())
    }

    @Test
    fun `a held window whose records were all deleted is not sent`() {
        // Never sent before, since it was still filling, so there is nothing to replace.
        assertTrue(send(changed = emptyList(), whole = WholeContent(emptyList(), coverage), carried = hour.take(30)).isEmpty())
    }

    @Test
    fun `only the windows with a change go out, each built from its own samples`() {
        val nine = List(60) { steps("2026-09-14T09:%02d:00Z".format(it), 5.0) }
        val buckets = send(changed = listOf(nine[3]), whole = WholeContent(hour + nine, coverage))
        assertEquals(listOf("2026-09-14T09:00:00Z"), buckets.map { it.getValue("bucket_start").jsonPrimitive.content })
        assertEquals(300.0, buckets.single().total(), 0.0)
    }

    @Test
    fun `a measured series goes out as before, never marked complete`() {
        // Combining a sample read again leaves the average, minimum and maximum as they were,
        // so a measured series keeps the old behaviour even when something was held whole.
        val beats = List(5) { CarriedSample(at("2026-09-14T08:0$it:00Z"), 60.0 + it * 10, "com.watch") }
        val a = ResolutionApplier(
            mapOf(HealthDataType.HEART_RATE to SeriesResolution.HOURLY),
            whole = mapOf(HealthDataType.HEART_RATE to WholeContent(beats, coverage))
        )
        a.bucketSeries(HealthDataType.HEART_RATE, "heart_rate", beats.take(1), at("2026-09-14T12:00:00Z"), ResolutionFamily.SAMPLED)
        val bucket = a.series.getValue("heart_rate").single() as JsonObject
        assertEquals(1, bucket.count())
        assertFalse(bucket.complete())
    }

    // ==================== What a read keeps ====================

    @Test
    fun `a read keeps whole windows of bucketed accumulated types only, from the earliest held sample`() {
        val requests = ResolutionApplier.wholeRequests(
            mapOf(
                HealthDataType.STEPS to SeriesResolution.HOURLY,
                HealthDataType.HEART_RATE to SeriesResolution.HOURLY,
                HealthDataType.DISTANCE to SeriesResolution.RAW,
                HealthDataType.WEIGHT to SeriesResolution.HOURLY
            ),
            carried = mapOf(HealthDataType.STEPS to listOf(steps("2026-09-14T08:40:00Z", 1.0), steps("2026-09-14T08:20:00Z", 1.0)))
        )
        assertEquals(setOf(HealthDataType.STEPS), requests.keys)
        assertEquals(WholeWindowRequest(SeriesResolution.HOURLY, at("2026-09-14T08:20:00Z")), requests[HealthDataType.STEPS])
    }

    @Test
    fun `coverage holds a window only when the whole window lies inside it`() {
        assertTrue(coverage.covers(at("2026-09-14T08:00:00Z"), at("2026-09-14T09:00:00Z")))
        assertTrue(coverage.covers(at("2026-09-14T11:00:00Z"), at("2026-09-14T12:00:00Z")))
        assertFalse(coverage.covers(at("2026-09-14T07:00:00Z"), at("2026-09-14T08:00:00Z")))
        assertFalse(coverage.covers(at("2026-09-14T11:30:00Z"), at("2026-09-14T12:30:00Z")))
    }

    @Test
    fun `alignment rounds down to the window of the resolution`() {
        assertEquals(at("2026-09-14T08:00:00Z"), SeriesBucketing.alignDown(at("2026-09-14T08:37:12Z"), SeriesResolution.HOURLY))
        assertEquals(at("2026-09-14T08:30:00Z"), SeriesBucketing.alignDown(at("2026-09-14T08:37:12Z"), SeriesResolution.FIFTEEN_MINUTES))
        assertEquals(at("2026-09-14T08:37:12Z"), SeriesBucketing.alignDown(at("2026-09-14T08:37:12Z"), SeriesResolution.RAW))
    }

    // ==================== Backfill ====================

    private fun backfillData(kept: List<StepsData>, coverage: ReadCoverage?, read: List<StepsData> = kept) = HealthData(
        steps = read,
        whole = HealthData(steps = kept),
        wholeCoverage = coverage?.let { mapOf(HealthDataType.STEPS to it) }.orEmpty()
    )

    private fun minute(time: String, count: Long) = StepsData(count, at(time), at(time).plusSeconds(60), "com.watch")

    @Test
    fun `a backfill chunk sends every window of its aligned range whole`() {
        val kept = List(120) { minute("2026-09-14T%02d:%02d:00Z".format(8 + it / 60, it % 60), 10) }
        val a = ResolutionApplier.forBackfill(backfillData(kept, ReadCoverage(at("2026-09-14T08:00:00Z"), at("2026-09-14T10:00:00Z"))), hourly, emit = true)
        val buckets = a.series.getValue("steps").map { it as JsonObject }
        assertEquals(listOf(600.0, 600.0), buckets.map { it.total() })
        assertTrue(buckets.all { it.complete() })
        assertEquals(120, a.absorbedRecords)
    }

    @Test
    fun `a later backfill chunk keeps the series empty, so the raw records stay out`() {
        val kept = List(60) { minute("2026-09-14T08:%02d:00Z".format(it), 10) }
        val a = ResolutionApplier.forBackfill(backfillData(kept, ReadCoverage(at("2026-09-14T08:00:00Z"), at("2026-09-14T09:00:00Z"))), hourly, emit = false)
        assertTrue(a.isBucketed("steps"))
        assertTrue(a.series.getValue("steps").isEmpty())
    }

    @Test
    fun `a backfill read that kept nothing whole sends no partial window`() {
        val a = ResolutionApplier.forBackfill(backfillData(emptyList(), null, read = listOf(minute("2026-09-14T08:05:00Z", 10))), hourly, emit = true)
        assertTrue(a.series.getValue("steps").isEmpty())
        assertNull(ResolutionApplier.wholeContent(HealthData())[HealthDataType.STEPS])
    }

    @Test
    fun `a backfill chunk buckets a measured series from its own samples and holds nothing back`() {
        // Capped: the newest measurement would have been the bound, and everything after it held
        // and then dropped, since a backfill keeps no carry between chunks.
        val beats = List(3) { HeartRateData(60L + it, at("2026-09-14T%02d:10:00Z".format(8 + it)), "com.watch") }
        val a = ResolutionApplier.forBackfill(
            HealthData(heartRate = beats, cappedTypes = setOf(HealthDataType.HEART_RATE)),
            mapOf(HealthDataType.HEART_RATE to SeriesResolution.HOURLY), emit = false
        )
        val buckets = a.series.getValue("heart_rate").map { it as JsonObject }
        assertEquals(3, buckets.size)
        assertTrue(buckets.none { it.complete() })
        assertTrue(a.carriedOut.isEmpty())
    }

    @Test
    fun `a complete bucket says so in the payload and an unmarked one does not`() {
        val bucket = Bucket(at("2026-09-14T08:00:00Z"), at("2026-09-14T09:00:00Z"), 10.0, 10.0, 10.0, 600.0, 60)
        assertFalse("complete" in ResolutionPayload.bucketJson(bucket, ResolutionFamily.ACCUMULATED))
        assertEquals("true", ResolutionPayload.bucketJson(bucket.copy(complete = true), ResolutionFamily.ACCUMULATED)["complete"]?.jsonPrimitive?.content)
    }
}
