package com.owen282000.lifedashboard

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The shape a receiver actually parses. An aggregate has to be unmistakably an aggregate: if a
 * bucketed object still carried the raw field name, a parser checking for that name would read
 * an average as a measurement and never know.
 */
class ResolutionPayloadTest {

    private fun at(text: String) = Instant.parse(text)

    private fun bucket(
        start: String = "2026-09-14T08:00:00Z",
        end: String = "2026-09-14T08:01:00Z",
        mean: Double = 70.0,
        minimum: Double = 60.0,
        maximum: Double = 80.0,
        total: Double = 210.0,
        sampleCount: Int = 3,
        sources: List<String> = emptyList()
    ) = Bucket(at(start), at(end), mean, minimum, maximum, total, sampleCount, sources)

    @Test
    fun `a measurement bucket carries the average and its range`() {
        val json = ResolutionPayload.bucketJson(bucket(), ResolutionFamily.SAMPLED)

        assertEquals("2026-09-14T08:00:00Z", json["bucket_start"]?.jsonPrimitive?.content)
        assertEquals("2026-09-14T08:01:00Z", json["bucket_end"]?.jsonPrimitive?.content)
        assertEquals(3, json["sample_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(70.0, json["avg"]?.jsonPrimitive?.content?.toDouble()!!, 0.0001)
        assertEquals(60.0, json["min"]?.jsonPrimitive?.content?.toDouble()!!, 0.0001)
        assertEquals(80.0, json["max"]?.jsonPrimitive?.content?.toDouble()!!, 0.0001)
        // A sum of heart rates is meaningless and would invite a receiver to chart it.
        assertFalse("total" in json)
    }

    @Test
    fun `a quantity bucket carries the total and no misleading range`() {
        val json = ResolutionPayload.bucketJson(bucket(total = 1200.0), ResolutionFamily.ACCUMULATED)

        assertEquals(1200.0, json["total"]?.jsonPrimitive?.content?.toDouble()!!, 0.0001)
        // The min and max of a sum describe the records that went in, not the bucket.
        assertFalse("avg" in json)
        assertFalse("min" in json)
        assertFalse("max" in json)
    }

    @Test
    fun `an aggregate never carries a raw field name`() {
        // The whole point: a receiver can tell the two shapes apart by looking for bucket_start,
        // and no aggregate masquerades as a sample by also setting bpm.
        val json = ResolutionPayload.bucketJson(bucket(), ResolutionFamily.SAMPLED)
        listOf("bpm", "time", "meters", "count", "calories", "percentage", "rate", "uuid").forEach {
            assertFalse("aggregate should not carry $it", it in json)
        }
        assertTrue("bucket_start" in json)
    }

    @Test
    fun `every bucket says how many samples went into it`() {
        // Without this a receiver cannot tell a one-sample bucket from a sixty-sample one,
        // and cannot merge or verify buckets at all.
        val json = ResolutionPayload.bucketJson(bucket(sampleCount = 1), ResolutionFamily.SAMPLED)
        assertEquals(1, json["sample_count"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `sources are listed when known and left out when not`() {
        val withSources = ResolutionPayload.bucketJson(
            bucket(sources = listOf("com.phone", "com.watch")), ResolutionFamily.SAMPLED
        )
        assertEquals(2, withSources["sources"]?.let { (it as kotlinx.serialization.json.JsonArray).size })
        assertFalse("sources" in ResolutionPayload.bucketJson(bucket(), ResolutionFamily.SAMPLED))
    }

    @Test
    fun `the payload names the window each series used`() {
        val json = ResolutionPayload.resolutionsJson(
            mapOf(
                "heart_rate" to SeriesResolution.ONE_MINUTE,
                "steps" to SeriesResolution.HOURLY,
                "weight" to SeriesResolution.RAW
            )
        )
        assertEquals("1m", json["heart_rate"]?.jsonPrimitive?.content)
        assertEquals("1h", json["steps"]?.jsonPrimitive?.content)
        // Raw series are absent: naming them would imply they were processed.
        assertFalse("weight" in json)
    }

    @Test
    fun `resolutions are named as durations a receiver can read`() {
        assertEquals("raw", SeriesResolution.RAW.payloadName)
        assertEquals("1m", SeriesResolution.ONE_MINUTE.payloadName)
        assertEquals("5m", SeriesResolution.FIVE_MINUTES.payloadName)
        assertEquals("15m", SeriesResolution.FIFTEEN_MINUTES.payloadName)
        assertEquals("1h", SeriesResolution.HOURLY.payloadName)
    }

    // ==================== Carrying open windows ====================

    private fun sample(time: String, value: Double) = CarriedSample(at(time), value, "com.watch")

    private fun applier(
        carriedIn: Map<HealthDataType, List<CarriedSample>> = emptyMap(),
        resolution: SeriesResolution = SeriesResolution.FIVE_MINUTES
    ) = ResolutionApplier(mapOf(HealthDataType.HEART_RATE to resolution), carriedIn)

    private fun starts(applier: ResolutionApplier) = applier.series.getValue("heart_rate").map {
        (it as kotlinx.serialization.json.JsonObject)["bucket_start"]?.jsonPrimitive?.content
    }

    @Test
    fun `a window that is still filling is carried, not sent`() {
        val a = applier()
        a.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate",
            listOf(sample("2026-09-14T08:01:00Z", 60.0), sample("2026-09-14T08:06:00Z", 70.0)),
            boundary = at("2026-09-14T08:07:00Z"), family = ResolutionFamily.SAMPLED
        )
        assertEquals(listOf("2026-09-14T08:00:00Z"), starts(a))
        assertEquals(listOf(sample("2026-09-14T08:06:00Z", 70.0)), a.carriedOut[HealthDataType.HEART_RATE])
    }

    @Test
    fun `carried samples complete the window on the next pass and it goes out once`() {
        // Pass one holds 08:06; pass two brings 08:08 and the window closes: one bucket with
        // both samples, never a half followed by another half.
        val first = applier()
        first.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate",
            listOf(sample("2026-09-14T08:06:00Z", 60.0)),
            boundary = at("2026-09-14T08:07:00Z"), family = ResolutionFamily.SAMPLED
        )
        assertEquals(emptyList<String>(), starts(first))

        val second = applier(carriedIn = first.carriedOut)
        second.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate",
            listOf(sample("2026-09-14T08:08:00Z", 80.0)),
            boundary = at("2026-09-14T08:20:00Z"), family = ResolutionFamily.SAMPLED
        )
        val bucket = second.series.getValue("heart_rate").single() as kotlinx.serialization.json.JsonObject
        assertEquals("2026-09-14T08:05:00Z", bucket["bucket_start"]?.jsonPrimitive?.content)
        assertEquals(2, bucket["sample_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(70.0, bucket["avg"]?.jsonPrimitive?.content?.toDouble()!!, 0.0001)
        assertTrue(second.carriedOut[HealthDataType.HEART_RATE].isNullOrEmpty())
    }

    @Test
    fun `a carried window is sent when it closes even if nothing new arrived`() {
        val a = applier(carriedIn = mapOf(HealthDataType.HEART_RATE to listOf(sample("2026-09-14T08:06:00Z", 60.0))))
        a.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate", emptyList(),
            boundary = at("2026-09-14T09:00:00Z"), family = ResolutionFamily.SAMPLED
        )
        assertEquals(listOf("2026-09-14T08:05:00Z"), starts(a))
        assertTrue(a.carriedOut[HealthDataType.HEART_RATE].isNullOrEmpty())
    }

    @Test
    fun `a type set back to raw drops what it was holding`() {
        val a = applier(
            carriedIn = mapOf(HealthDataType.HEART_RATE to listOf(sample("2026-09-14T08:06:00Z", 60.0))),
            resolution = SeriesResolution.RAW
        )
        a.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate", listOf(sample("2026-09-14T08:08:00Z", 80.0)),
            boundary = at("2026-09-14T09:00:00Z"), family = ResolutionFamily.SAMPLED
        )
        assertFalse(a.isBucketed("heart_rate"))
        assertFalse(HealthDataType.HEART_RATE in a.carriedOut)
    }

    @Test
    fun `a type with nothing this pass keeps what it was holding`() {
        val held = listOf(sample("2026-09-14T08:56:00Z", 60.0))
        val a = applier(carriedIn = mapOf(HealthDataType.HEART_RATE to held))
        a.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate", emptyList(),
            boundary = at("2026-09-14T08:58:00Z"), family = ResolutionFamily.SAMPLED
        )
        assertEquals(held, a.carriedOut[HealthDataType.HEART_RATE])
    }

    @Test
    fun `a capped read treats its newest window as still open`() {
        // The read was capped, so the next pass continues from the newest measurement here;
        // the window containing it may still grow and must not be sent yet.
        val data = HealthData(
            steps = emptyList(), sleep = emptyList(),
            heartRate = listOf(
                HeartRateData(60, at("2026-09-14T08:01:00Z"), "com.watch"),
                HeartRateData(80, at("2026-09-14T08:06:00Z"), "com.watch")
            ),
            distance = emptyList(), activeCalories = emptyList(), totalCalories = emptyList(),
            weight = emptyList(), height = emptyList(), bloodPressure = emptyList(), bloodGlucose = emptyList(),
            oxygenSaturation = emptyList(), bodyTemperature = emptyList(), respiratoryRate = emptyList(),
            restingHeartRate = emptyList(), exercise = emptyList(), hydration = emptyList(), nutrition = emptyList(),
            mindfulness = emptyList(), bodyFat = emptyList(), leanBodyMass = emptyList(), boneMass = emptyList(),
            bodyWaterMass = emptyList(), hrv = emptyList(), menstruationPeriod = emptyList(), menstruationFlow = emptyList(),
            basalMetabolicRate = emptyList(), vo2Max = emptyList(), skinTemperature = emptyList(),
            basalBodyTemperature = emptyList(), intermenstrualBleeding = emptyList(), ovulationTest = emptyList(),
            cervicalMucus = emptyList(), sexualActivity = emptyList(),
            cappedTypes = setOf(HealthDataType.HEART_RATE)
        )
        val a = ResolutionApplier.from(
            data, mapOf(HealthDataType.HEART_RATE to SeriesResolution.FIVE_MINUTES),
            now = at("2026-09-14T12:00:00Z")
        )
        // Long past 08:10 by the clock, but the cap makes 08:06 the boundary: 08:00 is sent, 08:05 waits.
        assertEquals(listOf("2026-09-14T08:00:00Z"), starts(a))
        assertEquals(1, a.carriedOut[HealthDataType.HEART_RATE]?.size)
    }

    @Test
    fun `a collecting pass sends nothing and keeps everything`() {
        // Not the last pass of the sync: the series counts as bucketed (so its raw records stay
        // out of the payload) but no bucket is written, and every sample is carried.
        val a = ResolutionApplier(
            mapOf(HealthDataType.HEART_RATE to SeriesResolution.FIVE_MINUTES),
            carriedIn = mapOf(HealthDataType.HEART_RATE to listOf(sample("2026-09-14T07:59:00Z", 55.0))),
            emit = false
        )
        a.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate",
            listOf(sample("2026-09-14T08:01:00Z", 60.0), sample("2026-09-14T08:06:00Z", 70.0)),
            boundary = at("2026-09-14T09:00:00Z"), family = ResolutionFamily.SAMPLED
        )
        assertTrue(a.isBucketed("heart_rate"))
        assertFalse("heart_rate" in a.series)
        assertTrue(a.used.isEmpty())
        assertEquals(2, a.absorbedRecords)
        assertEquals(3, a.carriedOut[HealthDataType.HEART_RATE]?.size)
    }

    @Test
    fun `the sending pass buckets what the collecting passes gathered`() {
        val collecting = ResolutionApplier(mapOf(HealthDataType.HEART_RATE to SeriesResolution.FIVE_MINUTES), emit = false)
        collecting.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate",
            listOf(sample("2026-09-14T08:07:00Z", 60.0), sample("2026-09-14T08:01:00Z", 50.0)),
            boundary = at("2026-09-14T09:00:00Z"), family = ResolutionFamily.SAMPLED
        )
        val sending = ResolutionApplier(
            mapOf(HealthDataType.HEART_RATE to SeriesResolution.FIVE_MINUTES),
            carriedIn = collecting.carriedOut
        )
        sending.bucketSeries(
            HealthDataType.HEART_RATE, "heart_rate",
            listOf(sample("2026-09-14T08:03:00Z", 70.0)),
            boundary = at("2026-09-14T09:00:00Z"), family = ResolutionFamily.SAMPLED
        )
        // 08:00 holds 08:01 and 08:03 from two different passes; 08:05 holds 08:07. Each once.
        assertEquals(listOf("2026-09-14T08:00:00Z", "2026-09-14T08:05:00Z"), starts(sending))
        val first = sending.series.getValue("heart_rate")[0] as kotlinx.serialization.json.JsonObject
        assertEquals(2, first["sample_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(60.0, first["avg"]?.jsonPrimitive?.content?.toDouble()!!, 0.0001)
    }

    @Test
    fun `carried samples survive a round trip through JSON`() {
        val samples = listOf(sample("2026-09-14T08:06:00Z", 60.5), CarriedSample(at("2026-09-14T08:07:00Z"), 61.0, null))
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(CarriedSample.serializer()), samples
        )
        val back = kotlinx.serialization.json.Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(CarriedSample.serializer()), json
        )
        assertEquals(samples, back)
    }

    @Test
    fun `an array of buckets keeps its order`() {
        val buckets = listOf(
            bucket(start = "2026-09-14T08:00:00Z", end = "2026-09-14T08:01:00Z"),
            bucket(start = "2026-09-14T08:01:00Z", end = "2026-09-14T08:02:00Z")
        )
        val json = ResolutionPayload.bucketsJson(buckets, ResolutionFamily.SAMPLED)
        assertEquals(2, json.size)
        assertEquals(
            "2026-09-14T08:00:00Z",
            json[0].let { (it as kotlinx.serialization.json.JsonObject)["bucket_start"]?.jsonPrimitive?.content }
        )
    }
}
