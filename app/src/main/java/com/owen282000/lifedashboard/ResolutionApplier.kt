package com.owen282000.lifedashboard

import kotlinx.serialization.json.JsonArray
import java.time.Instant

/**
 * Applies the configured resolutions to one batch of health data.
 *
 * The bucketed series are produced here, next to the raw ones they replace, rather than woven
 * through the payload builder: the builder keeps emitting raw records for everything left at
 * [SeriesResolution.RAW], and simply asks whether a series is bucketed before writing its raw
 * records. That way a raw payload is byte for byte what it always was.
 *
 * Bucketed series go out once per sync, in its last pass. A capped read splits a sync into
 * passes ordered by when records were written, which is not the order they were measured in,
 * so bucketing each pass on its own would send the same window several times. Earlier passes
 * therefore only collect samples ([emit] false); the last pass buckets everything collected.
 * A window still filling at that point is not sent either: its samples are kept in
 * preferences and bucketed together with the next sync's records, so the window goes out
 * once, whole. The sync's watermark is never touched; it tracks when records were written, a
 * different axis from when they were measured.
 *
 * Which windows go out is decided by the samples collected. A window can go out again: a
 * source rewrites the last hour on every export, edits a record, or a watch uploads late. For
 * an accumulated series a closed window is therefore built from [whole], everything the read
 * held of it (P2-16): built from only the record that changed, the bucket would make a receiver
 * that adds it to the stored window count a rewritten record twice. Built whole, it is marked
 * complete and replaces the stored window instead. A window the read could not hold whole goes
 * out as before, unmarked, for a receiver to combine, and so does every window of a measured
 * series, where combining a sample read again leaves the average, minimum and maximum alone.
 */
class ResolutionApplier(
    private val resolutions: Map<HealthDataType, SeriesResolution>,
    /** Samples collected earlier: from the last sync, or from earlier passes of this one. */
    private val carriedIn: Map<HealthDataType, List<CarriedSample>> = emptyMap(),
    /** False for a pass that only collects; true for the pass that sends. */
    private val emit: Boolean = true,
    /** Everything the reads of this sync held per type over a range, to build windows whole from. */
    private val whole: Map<HealthDataType, WholeContent> = emptyMap()
) {

    /** The bucketed series, keyed by the payload name they replace ("heart_rate", "steps"). */
    val series: MutableMap<String, JsonArray> = mutableMapOf()

    /** The resolution actually used per emitted key, for the payload's own `_resolutions` block. */
    val used: MutableMap<String, SeriesResolution> = mutableMapOf()

    /**
     * Samples to hand to the next pass or the next sync. Starts as a copy of [carriedIn] so a
     * type that saw no records this pass keeps what it was holding.
     */
    val carriedOut: MutableMap<HealthDataType, List<CarriedSample>> = carriedIn.toMutableMap()

    /** Raw records absorbed into buckets this pass, so the caller can tell an empty payload from a full one. */
    var absorbedRecords: Int = 0
        private set

    private val bucketedKeys = mutableSetOf<String>()

    /** True when the raw records of this series must be left out of the payload. */
    fun isBucketed(payloadKey: String): Boolean = payloadKey in bucketedKeys

    /**
     * Buckets one series, or only collects its samples when [emit] is false.
     *
     * [boundary] is the moment up to which windows are considered complete: normally now, but
     * for a type whose read was capped it is the newest measurement collected so far, because
     * the next pass continues from there and may still add to the window that contains it.
     */
    fun bucketSeries(
        type: HealthDataType,
        payloadKey: String,
        samples: List<CarriedSample>,
        boundary: Instant,
        family: ResolutionFamily
    ) {
        val resolution = resolutions[type] ?: DEFAULT_RESOLUTION
        val held = carriedIn[type].orEmpty()
        if (resolution == SeriesResolution.RAW) {
            // Nothing to hold once a type goes back to raw: the records themselves are sent.
            carriedOut.remove(type)
            return
        }
        bucketedKeys += payloadKey
        absorbedRecords += samples.size

        val all = held + samples
        if (!emit) {
            if (all.isEmpty()) carriedOut.remove(type) else carriedOut[type] = all
            return
        }
        if (all.isEmpty()) return

        val buckets = SeriesBucketing.bucket(all, resolution, { it.time }, { it.value }, { it.source })
        val split = SeriesBucketing.splitClosed(buckets, boundary)
        val openFrom = split.watermark
        if (openFrom != null) carriedOut[type] = all.filter { it.time >= openFrom } else carriedOut.remove(type)

        // An empty array still goes in when everything is being held: the series is bucketed,
        // so the payload must not fall back to the raw samples the receiver asked not to get.
        series[payloadKey] = ResolutionPayload.bucketsJson(split.closed.mapNotNull { wholeOrAsIs(type, it) }, family)
        used[payloadKey] = resolution
    }

    /**
     * Every window [content] holds, built whole and marked complete, for a backfill: its read
     * of an accumulated type runs from bucket bound to bucket bound, so each window lies wholly
     * inside it. Nothing is collected or held. Without [content] the series goes out empty,
     * which still keeps its raw records out of the payload.
     */
    fun bucketWhole(type: HealthDataType, payloadKey: String, content: WholeContent?, family: ResolutionFamily, recordsRead: Int) {
        val resolution = resolutions[type] ?: DEFAULT_RESOLUTION
        if (resolution == SeriesResolution.RAW) return
        bucketedKeys += payloadKey
        absorbedRecords += recordsRead
        val buckets = content?.let { c ->
            SeriesBucketing.bucket(c.samples, resolution, { it.time }, { it.value }, { it.source })
                .filter { c.coverage.covers(it.start, it.end) }
                .map { it.copy(complete = true) }
        }.orEmpty()
        series[payloadKey] = ResolutionPayload.bucketsJson(buckets, family)
        used[payloadKey] = resolution
    }

    /**
     * [bucket] rebuilt from everything the reads held for its window, marked complete; [bucket]
     * itself, unmarked, when they did not hold the window whole. Null when the window turns
     * out to hold nothing any more: the records the held samples came from were deleted, and a
     * window never sent before has nothing to replace.
     */
    private fun wholeOrAsIs(type: HealthDataType, bucket: Bucket): Bucket? {
        if (ResolutionFamily.of(type) != ResolutionFamily.ACCUMULATED) return bucket
        val content = whole[type]?.takeIf { it.coverage.covers(bucket.start, bucket.end) } ?: return bucket
        val resolution = resolutions[type] ?: return bucket
        val inWindow = content.samples.filter { !it.time.isBefore(bucket.start) && it.time.isBefore(bucket.end) }
        return SeriesBucketing.bucket(inWindow, resolution, { it.time }, { it.value }, { it.source })
            .singleOrNull()?.copy(complete = true)
    }

    /** One configurable series: which type, under which payload key, and how to read its samples. */
    private class Series(
        val type: HealthDataType,
        val key: String,
        val family: ResolutionFamily,
        val samplesOf: (HealthData) -> List<CarriedSample>
    )

    companion object {

        private val SERIES: List<Series> = listOf(
            Series(HealthDataType.HEART_RATE, "heart_rate", ResolutionFamily.SAMPLED) { d ->
                d.heartRate.map { CarriedSample(it.time, it.bpm.toDouble(), it.source) }
            },
            Series(HealthDataType.HEART_RATE_VARIABILITY, "heart_rate_variability", ResolutionFamily.SAMPLED) { d ->
                d.hrv.map { CarriedSample(it.time, it.heartRateVariabilityMillis, it.source) }
            },
            Series(HealthDataType.OXYGEN_SATURATION, "oxygen_saturation", ResolutionFamily.SAMPLED) { d ->
                d.oxygenSaturation.map { CarriedSample(it.time, it.percentage, it.source) }
            },
            Series(HealthDataType.RESPIRATORY_RATE, "respiratory_rate", ResolutionFamily.SAMPLED) { d ->
                d.respiratoryRate.map { CarriedSample(it.time, it.rate, it.source) }
            },
            Series(HealthDataType.SKIN_TEMPERATURE, "skin_temperature", ResolutionFamily.SAMPLED) { d ->
                d.skinTemperature.map { CarriedSample(it.time, it.deltaCelsius, it.source) }
            },
            Series(HealthDataType.STEPS, "steps", ResolutionFamily.ACCUMULATED) { d ->
                d.steps.map { CarriedSample(it.startTime, it.count.toDouble(), it.source) }
            },
            Series(HealthDataType.DISTANCE, "distance", ResolutionFamily.ACCUMULATED) { d ->
                d.distance.map { CarriedSample(it.startTime, it.meters, it.source) }
            },
            Series(HealthDataType.ACTIVE_CALORIES, "active_calories", ResolutionFamily.ACCUMULATED) { d ->
                d.activeCalories.map { CarriedSample(it.startTime, it.calories, it.source) }
            },
            Series(HealthDataType.TOTAL_CALORIES, "total_calories", ResolutionFamily.ACCUMULATED) { d ->
                d.totalCalories.map { CarriedSample(it.startTime, it.calories, it.source) }
            }
        )

        /**
         * What a read should keep whole of each bucketed type: from the window of the earliest
         * changed record, or of the earliest sample [carried] for it when that is earlier, so a
         * window still being held can be built whole once it closes.
         */
        fun wholeRequests(
            resolutions: Map<HealthDataType, SeriesResolution>,
            carried: Map<HealthDataType, List<CarriedSample>> = emptyMap()
        ): Map<HealthDataType, WholeWindowRequest> =
            resolutions.filter { (type, resolution) -> resolution != SeriesResolution.RAW && ResolutionFamily.of(type) == ResolutionFamily.ACCUMULATED }
                .mapValues { (type, resolution) -> WholeWindowRequest(resolution, carried[type]?.minOfOrNull { it.time }) }

        /** What [data]'s read kept whole, per type, as samples. Empty when it kept nothing. */
        fun wholeContent(data: HealthData): Map<HealthDataType, WholeContent> {
            val kept = data.whole ?: return emptyMap()
            return SERIES.mapNotNull { s -> data.wholeCoverage[s.type]?.let { s.type to WholeContent(s.samplesOf(kept), it) } }.toMap()
        }

        /**
         * Buckets every configurable series in [data] according to [resolutions].
         *
         * Types with no resolution family, and anything left at raw, are untouched and keep
         * flowing through the payload builder as records. A type in [HealthData.cappedTypes]
         * gets the newest measurement collected so far as its boundary instead of [now].
         * [whole] is what the reads of the sync so far held whole, see [wholeContent].
         */
        fun from(
            data: HealthData,
            resolutions: Map<HealthDataType, SeriesResolution>,
            now: Instant = Instant.now(),
            carriedIn: Map<HealthDataType, List<CarriedSample>> = emptyMap(),
            emit: Boolean = true,
            whole: Map<HealthDataType, WholeContent> = wholeContent(data)
        ): ResolutionApplier = ResolutionApplier(resolutions, carriedIn, emit, whole).apply {
            SERIES.forEach { s ->
                val samples = s.samplesOf(data)
                val boundary = if (s.type in data.cappedTypes) {
                    (carriedIn[s.type].orEmpty() + samples).maxOfOrNull { it.time }?.let { minOf(it, now) } ?: now
                } else now
                bucketSeries(s.type, s.key, samples, boundary, s.family)
            }
        }

        /**
         * Buckets a backfill chunk. An accumulated series goes out whole from what the read kept
         * ([HealthData.whole], over the type's bucket-aligned range), every window complete,
         * on a window's first chunk ([emit]); a later chunk reads the types still draining,
         * whose windows went out whole already, and sends their series empty so the raw records
         * stay out. A measured series buckets each chunk's own samples and holds nothing back:
         * chunks follow modification time, not measurement time, so there is no bound to hold
         * to, and their buckets combine on the receiver.
         */
        fun forBackfill(
            data: HealthData,
            resolutions: Map<HealthDataType, SeriesResolution>,
            emit: Boolean
        ): ResolutionApplier = ResolutionApplier(resolutions).apply {
            val content = wholeContent(data)
            SERIES.forEach { s ->
                when (s.family) {
                    ResolutionFamily.ACCUMULATED -> bucketWhole(s.type, s.key, content[s.type].takeIf { emit }, s.family, s.samplesOf(data).size)
                    ResolutionFamily.SAMPLED -> bucketSeries(s.type, s.key, s.samplesOf(data), Instant.MAX, s.family)
                }
            }
        }
    }
}
