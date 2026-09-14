package com.owen282000.lifedashboard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns bucketed series into the JSON a receiver sees.
 *
 * A bucketed array replaces the raw one under the same key, so a receiver that reads
 * `heart_rate` keeps reading `heart_rate`. The objects inside are deliberately a different
 * shape: an aggregate carries `bucket_start`, `bucket_end`, `sample_count` and the statistics,
 * and never the raw field name (`bpm` and friends). Reusing the raw field name on an aggregate
 * would make the two shapes indistinguishable to a parser that only checks for it, which is
 * exactly the ambiguity worth avoiding.
 *
 * The payload also names its own resolution, per series, under `_resolutions`. Without it a
 * receiver can see that a value is an average but not over what window, and cannot store the
 * series correctly without being told out of band.
 */
object ResolutionPayload {

    /**
     * One bucket as JSON. [family] decides which statistics are meaningful: a sum for a
     * quantity, an average with its range for a measurement.
     */
    fun bucketJson(bucket: Bucket, family: ResolutionFamily): JsonObject = buildJsonObject {
        put("bucket_start", bucket.start.toString())
        put("bucket_end", bucket.end.toString())
        put("sample_count", bucket.sampleCount)
        when (family) {
            ResolutionFamily.ACCUMULATED -> put("total", bucket.total)
            ResolutionFamily.SAMPLED -> {
                put("avg", bucket.mean)
                put("min", bucket.minimum)
                put("max", bucket.maximum)
            }
        }
        if (bucket.sources.isNotEmpty()) {
            put("sources", buildJsonArray { bucket.sources.forEach { add(it) } })
        }
    }

    fun bucketsJson(buckets: List<Bucket>, family: ResolutionFamily): JsonArray = buildJsonArray {
        buckets.forEach { add(bucketJson(it, family)) }
    }

    /**
     * The `_resolutions` block: the series that were bucketed and the window each one used.
     * Series left at raw resolution are absent, so a payload with no bucketing carries an
     * empty object and nothing changes for an existing receiver.
     */
    fun resolutionsJson(resolutions: Map<String, SeriesResolution>): JsonObject = buildJsonObject {
        resolutions
            .filterValues { it != SeriesResolution.RAW }
            .toSortedMap()
            .forEach { (series, resolution) -> put(series, resolution.payloadName) }
    }
}

/** How a resolution is named in the payload: a duration a receiver can parse, not an enum name. */
val SeriesResolution.payloadName: String
    get() = when (this) {
        SeriesResolution.RAW -> "raw"
        SeriesResolution.ONE_MINUTE -> "1m"
        SeriesResolution.FIVE_MINUTES -> "5m"
        SeriesResolution.FIFTEEN_MINUTES -> "15m"
        SeriesResolution.HOURLY -> "1h"
    }
