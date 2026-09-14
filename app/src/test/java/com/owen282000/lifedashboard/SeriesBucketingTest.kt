package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Bucketing of dense series. The properties that matter to a receiver: windows are aligned to
 * the clock rather than to the first sample, nothing is invented, and nothing is lost.
 */
class SeriesBucketingTest {

    private data class Sample(val time: Instant, val value: Double, val source: String? = null)

    private fun at(text: String) = Instant.parse(text)

    private fun bucketsOf(samples: List<Sample>, resolution: SeriesResolution) =
        SeriesBucketing.bucket(samples, resolution, { it.time }, { it.value }, { it.source })

    @Test
    fun `raw resolution produces no buckets at all`() {
        val samples = listOf(Sample(at("2026-09-14T08:00:00Z"), 60.0))
        assertEquals(emptyList<Bucket>(), bucketsOf(samples, SeriesResolution.RAW))
    }

    @Test
    fun `samples in one window collapse to a single bucket`() {
        val samples = listOf(
            Sample(at("2026-09-14T08:00:10Z"), 60.0),
            Sample(at("2026-09-14T08:00:30Z"), 70.0),
            Sample(at("2026-09-14T08:00:50Z"), 80.0)
        )
        val buckets = bucketsOf(samples, SeriesResolution.ONE_MINUTE)

        assertEquals(1, buckets.size)
        with(buckets.single()) {
            assertEquals(at("2026-09-14T08:00:00Z"), start)
            assertEquals(at("2026-09-14T08:01:00Z"), end)
            assertEquals(70.0, mean, 0.0001)
            assertEquals(60.0, minimum, 0.0001)
            assertEquals(80.0, maximum, 0.0001)
            assertEquals(3, sampleCount)
        }
    }

    @Test
    fun `windows are aligned to the clock, not to the first sample`() {
        // First sample at 08:07, so a naive implementation would make windows 08:07 to 08:22.
        val samples = listOf(
            Sample(at("2026-09-14T08:07:00Z"), 1.0),
            Sample(at("2026-09-14T08:14:59Z"), 3.0),
            Sample(at("2026-09-14T08:15:00Z"), 5.0)
        )
        val buckets = bucketsOf(samples, SeriesResolution.FIFTEEN_MINUTES)

        assertEquals(2, buckets.size)
        assertEquals(at("2026-09-14T08:00:00Z"), buckets[0].start)
        assertEquals(at("2026-09-14T08:15:00Z"), buckets[0].end)
        assertEquals(2, buckets[0].sampleCount)
        assertEquals(at("2026-09-14T08:15:00Z"), buckets[1].start)
        assertEquals(1, buckets[1].sampleCount)
    }

    @Test
    fun `buckets come back in chronological order regardless of input order`() {
        val samples = listOf(
            Sample(at("2026-09-14T09:30:00Z"), 3.0),
            Sample(at("2026-09-14T08:30:00Z"), 1.0),
            Sample(at("2026-09-14T10:30:00Z"), 2.0)
        )
        val starts = bucketsOf(samples, SeriesResolution.HOURLY).map { it.start }
        assertEquals(
            listOf(at("2026-09-14T08:00:00Z"), at("2026-09-14T09:00:00Z"), at("2026-09-14T10:00:00Z")),
            starts
        )
    }

    @Test
    fun `an empty window in the middle simply has no bucket`() {
        // Nothing between 08:00 and 09:00: bucketing reports what was measured, and inventing
        // an empty bucket would tell a receiver the value was zero.
        val samples = listOf(
            Sample(at("2026-09-14T08:10:00Z"), 1.0),
            Sample(at("2026-09-14T10:10:00Z"), 2.0)
        )
        val buckets = bucketsOf(samples, SeriesResolution.HOURLY)
        assertEquals(2, buckets.size)
        assertEquals(at("2026-09-14T08:00:00Z"), buckets[0].start)
        assertEquals(at("2026-09-14T10:00:00Z"), buckets[1].start)
    }

    @Test
    fun `a partial window at the end is still a bucket`() {
        // A sync at 08:20 leaves the 08:15 window incomplete; it is emitted with what it has,
        // and the next sync re-reads from the watermark and completes it.
        val samples = listOf(Sample(at("2026-09-14T08:16:00Z"), 42.0))
        val bucket = bucketsOf(samples, SeriesResolution.FIFTEEN_MINUTES).single()
        assertEquals(at("2026-09-14T08:15:00Z"), bucket.start)
        assertEquals(at("2026-09-14T08:30:00Z"), bucket.end)
        assertEquals(1, bucket.sampleCount)
    }

    @Test
    fun `totals are preserved across bucketing`() {
        // The sum over all buckets equals the sum of the samples: bucketing loses resolution,
        // never quantity. This is what makes it safe for steps and calories.
        val samples = (0 until 120).map {
            Sample(at("2026-09-14T08:00:00Z").plusSeconds(it * 30L), it.toDouble())
        }
        val total = SeriesBucketing.bucket(samples, SeriesResolution.FIVE_MINUTES, { it.time }, { it.value })
            .sumOf { it.total }
        assertEquals(samples.sumOf { it.value }, total, 0.0001)
    }

    @Test
    fun `sample counts are preserved across bucketing`() {
        val samples = (0 until 50).map {
            Sample(at("2026-09-14T08:00:00Z").plusSeconds(it * 7L), 1.0)
        }
        val counted = bucketsOf(samples, SeriesResolution.ONE_MINUTE).sumOf { it.sampleCount }
        assertEquals(samples.size, counted)
    }

    @Test
    fun `a bucket lists the sources that contributed, deduplicated`() {
        val samples = listOf(
            Sample(at("2026-09-14T08:00:10Z"), 60.0, "com.watch"),
            Sample(at("2026-09-14T08:00:20Z"), 62.0, "com.watch"),
            Sample(at("2026-09-14T08:00:30Z"), 64.0, "com.phone"),
            Sample(at("2026-09-14T08:00:40Z"), 66.0, null)
        )
        assertEquals(listOf("com.phone", "com.watch"), bucketsOf(samples, SeriesResolution.ONE_MINUTE).single().sources)
    }

    @Test
    fun `interval records are counted whole in the bucket they start in`() {
        // A record spanning a window boundary is not split: splitting would invent values that
        // were never measured, so the total stays the sum of complete records.
        data class Interval(val start: Instant, val steps: Double)
        val records = listOf(
            Interval(at("2026-09-14T08:14:00Z"), 100.0),
            Interval(at("2026-09-14T08:16:00Z"), 200.0)
        )
        val buckets = SeriesBucketing.bucketIntervals(
            records, SeriesResolution.FIFTEEN_MINUTES, { it.start }, { it.steps }
        )
        assertEquals(2, buckets.size)
        assertEquals(100.0, buckets[0].total, 0.0001)
        assertEquals(200.0, buckets[1].total, 0.0001)
        assertEquals(300.0, buckets.sumOf { it.total }, 0.0001)
    }

    @Test
    fun `bucketing a dense day shrinks it by orders of magnitude`() {
        // The reason this feature exists: a sample per second is 86,400 records a day.
        val day = (0 until 86_400).map {
            Sample(at("2026-09-14T00:00:00Z").plusSeconds(it.toLong()), 60.0 + (it % 40))
        }
        val perMinute = bucketsOf(day, SeriesResolution.ONE_MINUTE)
        val hourly = bucketsOf(day, SeriesResolution.HOURLY)
        assertEquals(1440, perMinute.size)
        assertEquals(24, hourly.size)
        assertTrue(hourly.all { it.sampleCount == 3600 })
    }

    // ==================== Closed buckets ====================

    @Test
    fun `a bucket still filling is held back, with a watermark to resume from`() {
        // 08:15 to 08:30, asked at 08:20: the window is not over, so sending it now and again
        // at 08:30 would be the same timestamp twice with two different averages.
        val samples = listOf(
            Sample(at("2026-09-14T08:00:30Z"), 60.0),
            Sample(at("2026-09-14T08:16:00Z"), 70.0)
        )
        val buckets = bucketsOf(samples, SeriesResolution.FIFTEEN_MINUTES)
        val split = SeriesBucketing.splitClosed(buckets, now = at("2026-09-14T08:20:00Z"))

        assertEquals(1, split.closed.size)
        assertEquals(at("2026-09-14T08:00:00Z"), split.closed.single().start)
        assertEquals(at("2026-09-14T08:15:00Z"), split.watermark)
    }

    @Test
    fun `when every bucket is closed nothing is held back`() {
        val samples = listOf(Sample(at("2026-09-14T08:00:30Z"), 60.0))
        val split = SeriesBucketing.splitClosed(
            bucketsOf(samples, SeriesResolution.FIFTEEN_MINUTES),
            now = at("2026-09-14T09:00:00Z")
        )
        assertEquals(1, split.closed.size)
        assertNull(split.watermark)
    }

    @Test
    fun `a bucket that ends exactly now counts as closed`() {
        val samples = listOf(Sample(at("2026-09-14T08:00:30Z"), 60.0))
        val split = SeriesBucketing.splitClosed(
            bucketsOf(samples, SeriesResolution.ONE_MINUTE),
            now = at("2026-09-14T08:01:00Z")
        )
        assertEquals(1, split.closed.size)
        assertNull(split.watermark)
    }

    @Test
    fun `holding back the open bucket makes repeated syncs emit each window once`() {
        // Two syncs over one stream of samples: the second re-reads from the watermark the
        // first returned, and no bucket start appears twice.
        val samples = (0 until 40).map { Sample(at("2026-09-14T08:00:00Z").plusSeconds(it * 30L), it.toDouble()) }

        val firstSync = SeriesBucketing.splitClosed(
            bucketsOf(samples.filter { it.time < at("2026-09-14T08:12:00Z") }, SeriesResolution.FIVE_MINUTES),
            now = at("2026-09-14T08:12:00Z")
        )
        val resumeFrom = firstSync.watermark!!
        val secondSync = SeriesBucketing.splitClosed(
            bucketsOf(samples.filter { it.time >= resumeFrom }, SeriesResolution.FIVE_MINUTES),
            now = at("2026-09-14T08:25:00Z")
        )

        val starts = (firstSync.closed + secondSync.closed).map { it.start }
        assertEquals(starts.distinct(), starts)
        assertEquals(
            listOf(at("2026-09-14T08:00:00Z"), at("2026-09-14T08:05:00Z"), at("2026-09-14T08:10:00Z"), at("2026-09-14T08:15:00Z")),
            starts
        )
    }

    // ==================== Families ====================

    @Test
    fun `dense measurements are averaged and quantities are summed`() {
        assertEquals(ResolutionFamily.SAMPLED, ResolutionFamily.of(HealthDataType.HEART_RATE))
        assertEquals(ResolutionFamily.SAMPLED, ResolutionFamily.of(HealthDataType.HEART_RATE_VARIABILITY))
        assertEquals(ResolutionFamily.ACCUMULATED, ResolutionFamily.of(HealthDataType.STEPS))
        assertEquals(ResolutionFamily.ACCUMULATED, ResolutionFamily.of(HealthDataType.DISTANCE))
    }

    @Test
    fun `types where a window means nothing have no resolution`() {
        assertNull(ResolutionFamily.of(HealthDataType.WEIGHT))
        assertNull(ResolutionFamily.of(HealthDataType.EXERCISE))
        assertNull(ResolutionFamily.of(HealthDataType.NUTRITION))
        // Sleep stages are the data; averaging them would destroy what makes the record useful.
        assertNull(ResolutionFamily.of(HealthDataType.SLEEP))
    }

    @Test
    fun `every configurable type has a family and the rest do not`() {
        val configurable = ResolutionFamily.configurableTypes
        assertTrue(configurable.all { ResolutionFamily.of(it) != null })
        assertEquals(9, configurable.size)
        assertTrue(HealthDataType.entries.filterNot { it in configurable }.none { ResolutionFamily.of(it) != null })
    }

    @Test
    fun `nothing is bucketed until the user asks for it`() {
        assertEquals(SeriesResolution.RAW, DEFAULT_RESOLUTION)
    }

    @Test
    fun `resolutions are read back by name and fall back to raw`() {
        assertEquals(SeriesResolution.FIVE_MINUTES, SeriesResolution.from("FIVE_MINUTES"))
        assertEquals(SeriesResolution.RAW, SeriesResolution.from(null))
        assertEquals(SeriesResolution.RAW, SeriesResolution.from("SOMETHING_ELSE"))
    }

    @Test
    fun `samples before the epoch still align downwards`() {
        // Math.floorDiv rather than plain division: a negative epoch millis must round down,
        // not towards zero, or a bucket would start after the sample it contains.
        val sample = Sample(at("1969-12-31T23:59:30Z"), 1.0)
        val bucket = bucketsOf(listOf(sample), SeriesResolution.ONE_MINUTE).single()
        assertEquals(at("1969-12-31T23:59:00Z"), bucket.start)
        assertTrue(bucket.start <= sample.time && sample.time < bucket.end)
    }
}
