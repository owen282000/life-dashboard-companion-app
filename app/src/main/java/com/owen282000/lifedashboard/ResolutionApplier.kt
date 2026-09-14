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
 * The one case left that sends a window twice is a record arriving late for a window already
 * sent. Buckets carry enough (`sample_count`, `avg`, `min`, `max`, `total`) for a receiver to
 * merge that exactly, and the docs say how.
 */
class ResolutionApplier(
    private val resolutions: Map<HealthDataType, SeriesResolution>,
    /** Samples collected earlier: from the last sync, or from earlier passes of this one. */
    private val carriedIn: Map<HealthDataType, List<CarriedSample>> = emptyMap(),
    /** False for a pass that only collects; true for the pass that sends. */
    private val emit: Boolean = true
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
        series[payloadKey] = ResolutionPayload.bucketsJson(split.closed, family)
        used[payloadKey] = resolution
    }

    companion object {
        /**
         * Buckets every configurable series in [data] according to [resolutions].
         *
         * Types with no resolution family, and anything left at raw, are untouched and keep
         * flowing through the payload builder as records. A type in [HealthData.cappedTypes]
         * gets the newest measurement collected so far as its boundary instead of [now].
         */
        fun from(
            data: HealthData,
            resolutions: Map<HealthDataType, SeriesResolution>,
            now: Instant = Instant.now(),
            carriedIn: Map<HealthDataType, List<CarriedSample>> = emptyMap(),
            emit: Boolean = true
        ): ResolutionApplier = ResolutionApplier(resolutions, carriedIn, emit).apply {
            fun <T> series(
                type: HealthDataType, key: String, records: List<T>, family: ResolutionFamily,
                timeOf: (T) -> Instant, valueOf: (T) -> Double, sourceOf: (T) -> String?
            ) {
                val samples = records.map { CarriedSample(timeOf(it), valueOf(it), sourceOf(it)) }
                val boundary = if (type in data.cappedTypes) {
                    (carriedIn[type].orEmpty() + samples).maxOfOrNull { it.time }?.let { minOf(it, now) } ?: now
                } else now
                bucketSeries(type, key, samples, boundary, family)
            }

            val sampled = ResolutionFamily.SAMPLED
            series(HealthDataType.HEART_RATE, "heart_rate", data.heartRate, sampled,
                { it.time }, { it.bpm.toDouble() }, { it.source })
            series(HealthDataType.HEART_RATE_VARIABILITY, "heart_rate_variability", data.hrv, sampled,
                { it.time }, { it.heartRateVariabilityMillis }, { it.source })
            series(HealthDataType.OXYGEN_SATURATION, "oxygen_saturation", data.oxygenSaturation, sampled,
                { it.time }, { it.percentage }, { it.source })
            series(HealthDataType.RESPIRATORY_RATE, "respiratory_rate", data.respiratoryRate, sampled,
                { it.time }, { it.rate }, { it.source })
            series(HealthDataType.SKIN_TEMPERATURE, "skin_temperature", data.skinTemperature, sampled,
                { it.time }, { it.deltaCelsius }, { it.source })

            val accumulated = ResolutionFamily.ACCUMULATED
            series(HealthDataType.STEPS, "steps", data.steps, accumulated,
                { it.startTime }, { it.count.toDouble() }, { it.source })
            series(HealthDataType.DISTANCE, "distance", data.distance, accumulated,
                { it.startTime }, { it.meters }, { it.source })
            series(HealthDataType.ACTIVE_CALORIES, "active_calories", data.activeCalories, accumulated,
                { it.startTime }, { it.calories }, { it.source })
            series(HealthDataType.TOTAL_CALORIES, "total_calories", data.totalCalories, accumulated,
                { it.startTime }, { it.calories }, { it.source })
        }
    }
}
