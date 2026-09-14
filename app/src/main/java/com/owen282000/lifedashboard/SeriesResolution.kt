package com.owen282000.lifedashboard

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Duration
import java.time.Instant

/**
 * How densely a data type is reported. A chest strap writes a heart rate sample every second,
 * which is 86,400 records a day that no dashboard ever looks at one by one; bucketing turns
 * those into one value per minute, or per hour, before they reach a webhook.
 *
 * [RAW] is the default and changes nothing, so an existing receiver keeps seeing exactly what
 * it saw before. Bucketing happens after reading and deduplicating, purely on the payload, so
 * watermarks, the outbox and the MQTT sensors are unaffected: a bucketed sync advances the
 * same watermark and can be re-read at a different resolution later.
 */
enum class SeriesResolution(val bucket: Duration?) {
    RAW(null),
    ONE_MINUTE(Duration.ofMinutes(1)),
    FIVE_MINUTES(Duration.ofMinutes(5)),
    FIFTEEN_MINUTES(Duration.ofMinutes(15)),
    HOURLY(Duration.ofHours(1));

    companion object {
        /** Reads a stored name, falling back to [RAW] for anything unknown. */
        fun from(name: String?): SeriesResolution =
            entries.firstOrNull { it.name == name } ?: RAW
    }
}

/**
 * Which data types can be bucketed at all, and what happens to their values.
 *
 * Only two families exist because only two things can be done with a number over a window:
 * average it or add it up. Everything else the app syncs is a one-off event (a weight, a
 * workout, a meal) where a window means nothing, and those types simply have no setting.
 */
enum class ResolutionFamily {
    /**
     * A measurement at a moment: heart rate, HRV, oxygen saturation. Bucketing averages them
     * and keeps the minimum and maximum, because an average alone hides whether the wearer
     * was asleep or sprinting.
     */
    SAMPLED,

    /**
     * A quantity accumulated over a window: steps, distance, calories. Bucketing adds them up;
     * a minimum and maximum of a sum would mean nothing and is not reported.
     */
    ACCUMULATED;

    companion object {
        /**
         * The family of [type], or null when the type has no meaningful resolution.
         *
         * Sleep is deliberately absent: its stages are the data, and averaging them would
         * destroy exactly what makes a sleep record useful.
         */
        fun of(type: HealthDataType): ResolutionFamily? = when (type) {
            HealthDataType.HEART_RATE,
            HealthDataType.HEART_RATE_VARIABILITY,
            HealthDataType.OXYGEN_SATURATION,
            HealthDataType.RESPIRATORY_RATE,
            HealthDataType.SKIN_TEMPERATURE -> SAMPLED

            HealthDataType.STEPS,
            HealthDataType.DISTANCE,
            HealthDataType.ACTIVE_CALORIES,
            HealthDataType.TOTAL_CALORIES -> ACCUMULATED

            else -> null
        }

        /** Every type the resolution setting applies to, in the order the UI lists them. */
        val configurableTypes: List<HealthDataType>
            get() = HealthDataType.entries.filter { of(it) != null }
    }
}

/**
 * The resolution every type starts on.
 *
 * [SeriesResolution.RAW], including for the dense sample types. Bucketing is lossy, and an app
 * that quietly averaged away someone's heart rate data after an update would be deciding
 * something that is theirs to decide; the setting is offered, not applied for them.
 */
val DEFAULT_RESOLUTION: SeriesResolution = SeriesResolution.RAW

/**
 * One bucket of a series: the window, what the values in it came to, and how many there were.
 *
 * Both a mean and a min/max are kept because a single number hides what dense data is for.
 * An average heart rate of 78 over an hour is the same whether the wearer was asleep the whole
 * time or sprinted for five minutes; [minimum] and [maximum] keep that difference visible at a
 * fraction of the size of the raw records.
 */
data class Bucket(
    val start: Instant,
    val end: Instant,
    val mean: Double,
    val minimum: Double,
    val maximum: Double,
    val total: Double,
    val sampleCount: Int,
    /** Sources that contributed, so a bucket mixing phone and watch data is still traceable. */
    val sources: List<String> = emptyList()
)

/**
 * The result of holding back buckets that are still filling: [closed] is safe to send, and
 * [watermark] is the start of the first open bucket, so its samples can be carried forward
 * and the bucket sent once it is complete. Null means nothing was held back.
 */
data class BucketSplit(val closed: List<Bucket>, val watermark: Instant?)

/**
 * One measurement reduced to what bucketing needs, so the samples of a window that is still
 * filling can be carried to the next pass or stored until the next sync. A record id is
 * deliberately not kept: an aggregate has no single record to point at.
 */
@Serializable
data class CarriedSample(
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val value: Double,
    val source: String? = null
)

/** ISO-8601 for [Instant], which kotlinx.serialization does not ship. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * Bucketing of a time series, kept free of Android types so it can be unit tested on the JVM.
 *
 * Buckets are aligned to the epoch rather than to the first sample: with a 15-minute bucket the
 * windows start at :00, :15, :30 and :45 regardless of when the sync ran, so two syncs of the
 * same day produce windows that line up instead of drifting by the offset of the first record.
 */
object SeriesBucketing {

    /**
     * Groups [samples] into buckets of [resolution].
     *
     * [timeOf] gives the moment a sample belongs to and [valueOf] its value. Returns an empty
     * list for [SeriesResolution.RAW], because at raw resolution the caller emits the records
     * themselves and never asks for buckets.
     */
    fun <T> bucket(
        samples: List<T>,
        resolution: SeriesResolution,
        timeOf: (T) -> Instant,
        valueOf: (T) -> Double,
        sourceOf: (T) -> String? = { null }
    ): List<Bucket> {
        val window = resolution.bucket ?: return emptyList()
        if (samples.isEmpty()) return emptyList()

        val windowMillis = window.toMillis()
        return samples
            .groupBy { alignDown(timeOf(it), windowMillis) }
            .toSortedMap()
            .map { (start, inBucket) ->
                val values = inBucket.map(valueOf)
                Bucket(
                    start = start,
                    end = start.plusMillis(windowMillis),
                    mean = values.average(),
                    minimum = values.min(),
                    maximum = values.max(),
                    total = values.sum(),
                    sampleCount = values.size,
                    sources = inBucket.mapNotNull(sourceOf).distinct().sorted()
                )
            }
    }

    /**
     * Buckets an interval series (steps, distance, calories) by the moment each record starts.
     *
     * A record is counted whole, in the bucket its start falls in, rather than split across the
     * windows it overlaps. Health Connect's own records are already short (minutes at most) and
     * splitting would invent values that were never measured; [Bucket.total] therefore stays the
     * honest sum of complete records.
     */
    fun <T> bucketIntervals(
        records: List<T>,
        resolution: SeriesResolution,
        startOf: (T) -> Instant,
        valueOf: (T) -> Double,
        sourceOf: (T) -> String? = { null }
    ): List<Bucket> = bucket(records, resolution, startOf, valueOf, sourceOf)

    /**
     * Splits [buckets] into the ones that are complete at [now] and the moment the first
     * incomplete one begins.
     *
     * A sync landing mid-bucket would otherwise emit that bucket from the samples it has, and
     * the next sync would emit the same bucket start again with a different average. Holding
     * the open bucket's samples back, and bucketing them together with what arrives next, sends
     * the bucket once, complete. [BucketSplit.watermark] is null when every bucket is closed.
     */
    fun splitClosed(buckets: List<Bucket>, now: Instant): BucketSplit {
        val firstOpen = buckets.firstOrNull { it.end > now }
        return BucketSplit(
            closed = buckets.filter { it.end <= now },
            watermark = firstOpen?.start
        )
    }

    /** The start of the bucket [time] falls in, aligned to the epoch. */
    private fun alignDown(time: Instant, windowMillis: Long): Instant =
        Instant.ofEpochMilli(Math.floorDiv(time.toEpochMilli(), windowMillis) * windowMillis)
}
