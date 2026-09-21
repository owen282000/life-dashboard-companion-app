package com.owen282000.lifedashboard

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Deletion propagation (issue #61).
 *
 * A sync reads records and filters them on metadata.lastModifiedTime. A deletion produces no
 * record at all, so it is invisible to that read: a receiver keeps a record that Health Connect
 * no longer has. Cronometer, for instance, replaces a meal by deleting it and inserting a new
 * one, which leaves the receiver holding both.
 *
 * Health Connect answers this with a changes token per record type. [ChangesTracker] walks the
 * changes since the stored token and reports the ids of deleted records; the payload carries
 * them as `deleted_records` so a receiver can drop exactly those ids.
 *
 * This file holds the parts that are decidable without Health Connect, so they can be unit
 * tested: what a token is worth, and how the result is shaped. The client calls live in
 * [HealthConnectManager].
 */

/** One deleted record, as it appears in the payload. */
@kotlinx.serialization.Serializable
data class DeletedRecord(
    /** The payload key of the type it belonged to, e.g. "nutrition". */
    val type: String,
    /** metadata.id of the record Health Connect deleted. */
    val uuid: String
)

/**
 * The outcome of reading changes for one type.
 *
 * [expired] says the stored token was no longer accepted, which Health Connect does after about
 * 30 days without a sync. Deletions in that gap are lost, and only a backfill snapshot can
 * reconcile them, so the sync reports it rather than silently continuing.
 */
data class ChangesResult(
    val deleted: List<DeletedRecord> = emptyList(),
    val nextToken: String? = null,
    val expired: Boolean = false,
    val error: String? = null
)

/**
 * What one sync found across every enabled type: the deletions to publish, and the types whose
 * token had expired so a receiver knows where a deletion could have been missed.
 *
 * Reading the changes feed consumes it, so a summary that was read but not delivered cannot be
 * read again. It is therefore carried across syncs until a payload actually goes out, the same
 * way still-open buckets are (see `getBucketCarry`); [merge] joins the carried summary with what
 * the current sync found.
 */
@kotlinx.serialization.Serializable
data class DeletionSummary(
    val deleted: List<DeletedRecord> = emptyList(),
    val expiredTypes: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = deleted.isEmpty() && expiredTypes.isEmpty()

    /**
     * This summary plus [other], without duplicates and in the same stable order a single sync
     * would produce, so a deletion carried from an earlier sync is indistinguishable from a
     * fresh one.
     */
    fun merge(other: DeletionSummary): DeletionSummary {
        if (other.isEmpty) return this
        if (isEmpty) return other
        return DeletionSummary(
            deleted = (deleted + other.deleted)
                .distinctBy { it.type to it.uuid }
                .sortedWith(compareBy({ it.type }, { it.uuid })),
            expiredTypes = (expiredTypes + other.expiredTypes).distinct().sorted()
        )
    }

    companion object {
        val EMPTY = DeletionSummary()
    }
}

object DeletionTracking {

    /**
     * The deletion fields of a payload, as [HealthSyncManager] writes them.
     *
     * Kept here rather than inline so the shape a receiver parses can be asserted directly:
     * these fields instruct a receiver to remove data it already stored, so a wrong key or a
     * field that appears when it should not is destructive rather than cosmetic.
     *
     * Absent beats empty throughout: no `deleted_records` says nothing was deleted, while an
     * empty array would say the app looked and found none, and those are different claims.
     */
    fun payloadFields(summary: DeletionSummary): Map<String, JsonElement> = buildMap {
        if (summary.deleted.isNotEmpty()) {
            put(
                "deleted_records",
                buildJsonArray {
                    summary.deleted.forEach { record ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive(record.type))
                                put("uuid", JsonPrimitive(record.uuid))
                            }
                        )
                    }
                }
            )
        }
        if (summary.expiredTypes.isNotEmpty()) {
            put(
                "deletions_unavailable",
                buildJsonArray { summary.expiredTypes.forEach { add(JsonPrimitive(it)) } }
            )
        }
    }

    /**
     * Health Connect expires a changes token after 30 days of not being used. The app treats a
     * token older than this as expired without asking, so a phone that has not synced for a
     * month starts a fresh token instead of spending a call on a certain rejection.
     */
    const val TOKEN_MAX_AGE_DAYS = 30L

    /**
     * How long one type's changes read may take, and how long the whole deletion step may take
     * across every enabled type.
     *
     * The step runs before the first delivery and does one round trip to Health Connect per
     * enabled type, up to 33. Warm, that is milliseconds each. A scheduled run that starts
     * while the phone is dozing can find the service cold and a call that does not return, and
     * a hang is not an exception, so without a bound the whole sync would sit there until
     * Android stopped the worker, which delivers nothing at all. A type that does not fit keeps
     * its token, so the next sync reads it from the same position; it only costs that type a
     * turn in `deletions_unavailable`, which is the honest report (1.18.1, after a report of
     * background syncs stalling on 1.18.0).
     */
    const val PER_TYPE_TIMEOUT_MS = 5_000L
    const val TOTAL_BUDGET_MS = 20_000L

    /**
     * How long the next type may take given how much of the budget is already spent: the
     * per-type limit, or whatever is left of the total if that is less, or zero when the total
     * is gone, which the caller reads as "skip this type".
     */
    fun timeoutFor(
        elapsedMs: Long,
        perTypeMs: Long = PER_TYPE_TIMEOUT_MS,
        totalMs: Long = TOTAL_BUDGET_MS
    ): Long = minOf(perTypeMs, totalMs - elapsedMs).coerceAtLeast(0)

    /**
     * The payload key each type's records are published under, which is also the key a receiver
     * stores them by. `deleted_records` uses these names so an entry points at the same
     * collection the record itself arrived in.
     */
    fun payloadKey(type: HealthDataType): String = when (type) {
        HealthDataType.STEPS -> "steps"
        HealthDataType.SLEEP -> "sleep"
        HealthDataType.HEART_RATE -> "heart_rate"
        HealthDataType.DISTANCE -> "distance"
        HealthDataType.ACTIVE_CALORIES -> "active_calories"
        HealthDataType.TOTAL_CALORIES -> "total_calories"
        HealthDataType.WEIGHT -> "weight"
        HealthDataType.HEIGHT -> "height"
        HealthDataType.BLOOD_PRESSURE -> "blood_pressure"
        HealthDataType.BLOOD_GLUCOSE -> "blood_glucose"
        HealthDataType.OXYGEN_SATURATION -> "oxygen_saturation"
        HealthDataType.BODY_TEMPERATURE -> "body_temperature"
        HealthDataType.RESPIRATORY_RATE -> "respiratory_rate"
        HealthDataType.RESTING_HEART_RATE -> "resting_heart_rate"
        HealthDataType.EXERCISE -> "exercise"
        HealthDataType.HYDRATION -> "hydration"
        HealthDataType.NUTRITION -> "nutrition"
        HealthDataType.MINDFULNESS -> "mindfulness"
        HealthDataType.BODY_FAT -> "body_fat"
        HealthDataType.LEAN_BODY_MASS -> "lean_body_mass"
        HealthDataType.BONE_MASS -> "bone_mass"
        HealthDataType.BODY_WATER_MASS -> "body_water_mass"
        HealthDataType.HEART_RATE_VARIABILITY -> "heart_rate_variability"
        HealthDataType.MENSTRUATION_PERIOD -> "menstruation_period"
        HealthDataType.MENSTRUATION_FLOW -> "menstruation_flow"
        HealthDataType.BASAL_METABOLIC_RATE -> "basal_metabolic_rate"
        HealthDataType.VO2_MAX -> "vo2_max"
        HealthDataType.SKIN_TEMPERATURE -> "skin_temperature"
        HealthDataType.BASAL_BODY_TEMPERATURE -> "basal_body_temperature"
        HealthDataType.INTERMENSTRUAL_BLEEDING -> "intermenstrual_bleeding"
        HealthDataType.OVULATION_TEST -> "ovulation_test"
        HealthDataType.CERVICAL_MUCUS -> "cervical_mucus"
        HealthDataType.SEXUAL_ACTIVITY -> "sexual_activity"
    }

    /**
     * Whether a stored token is worth spending a call on.
     *
     * A token is refused once it is older than [TOKEN_MAX_AGE_DAYS]; asking anyway costs a round
     * trip to learn what the timestamp already says. A missing token or a missing timestamp both
     * mean "no usable token": the first sync for a type has neither.
     */
    fun isTokenUsable(token: String?, issuedAtMs: Long?, nowMs: Long): Boolean {
        if (token.isNullOrEmpty() || issuedAtMs == null) return false
        if (issuedAtMs > nowMs) return false // clock moved backwards; do not trust the token
        val ageMs = nowMs - issuedAtMs
        return ageMs < TOKEN_MAX_AGE_DAYS * 24 * 60 * 60 * 1000
    }

    /**
     * Deletions from every type in one list, in a stable order (type, then uuid), so two syncs
     * that carry the same deletions produce the same payload and a diff of two payloads is
     * readable.
     */
    fun merge(results: Map<HealthDataType, ChangesResult>): List<DeletedRecord> =
        results.values
            .flatMap { it.deleted }
            .distinctBy { it.type to it.uuid }
            .sortedWith(compareBy({ it.type }, { it.uuid }))

    /**
     * The types whose deletions this sync cannot vouch for, so the payload can name them.
     *
     * Three things land here and mean the same to a receiver: a token that expired, a feed too
     * long to read in one sync, and a type that could not be read at all. In each case the app
     * does not know what was deleted, and a receiver that assumed otherwise would keep records
     * Health Connect no longer has.
     */
    fun expiredTypes(results: Map<HealthDataType, ChangesResult>): List<String> =
        results.filterValues { it.expired || it.error != null }
            .keys
            .map { payloadKey(it) }
            .sorted()
}
