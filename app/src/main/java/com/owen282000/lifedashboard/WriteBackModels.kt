package com.owen282000.lifedashboard

import androidx.health.connect.client.permission.HealthPermission
import java.time.Instant
import java.time.ZoneOffset

/*
 * Write-back into Health Connect (issue #62, phase 1): the types the app can write, and one
 * reading as it arrives from the Home Assistant integration, already parsed and validated.
 * Free of Android and Health Connect client calls, so the parser, the validation and the
 * ledger stay unit-testable on the JVM; the record construction and the inserts live in
 * HealthConnectManager.
 */

/**
 * The seven types the app writes, with the `type` key the protocol uses. Each one has its
 * own WRITE permission in the manifest and in the Play declaration, and nothing outside this
 * list is ever declared or requested: adding a type here is a new Play declaration.
 */
enum class WriteBackType(val key: String, val dataType: HealthDataType) {
    WEIGHT("weight", HealthDataType.WEIGHT),
    HEIGHT("height", HealthDataType.HEIGHT),
    BODY_FAT("body_fat", HealthDataType.BODY_FAT),
    LEAN_BODY_MASS("lean_body_mass", HealthDataType.LEAN_BODY_MASS),
    BONE_MASS("bone_mass", HealthDataType.BONE_MASS),
    BODY_WATER_MASS("body_water_mass", HealthDataType.BODY_WATER_MASS),
    BLOOD_PRESSURE("blood_pressure", HealthDataType.BLOOD_PRESSURE);

    /** The Health Connect write permission for this type, asked for when the type is switched on. */
    val writePermission: String get() = HealthPermission.getWritePermission(dataType.recordClass)

    /** The unit of the primary value, for the log: kg, m, % or mmHg. */
    val unit: String
        get() = when (this) {
            WEIGHT, LEAN_BODY_MASS, BONE_MASS, BODY_WATER_MASS -> "kg"
            HEIGHT -> "m"
            BODY_FAT -> "%"
            BLOOD_PRESSURE -> "mmHg"
        }

    companion object {
        fun fromKey(key: String): WriteBackType? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How the reading was taken; decides which Metadata factory the record gets.
 *
 * The integration says `auto`, while the app's own record metadata says `automatic` for the
 * same thing (webhook.md), so both are read as [AUTO]. Any other value falls back to [AUTO].
 */
enum class RecordingMethod(val key: String, private val alias: String? = null) {
    AUTO("auto", alias = "automatic"),
    ACTIVE("active"),
    MANUAL("manual");

    companion object {
        fun fromKey(key: String?): RecordingMethod =
            entries.firstOrNull { key != null && (it.key == key || it.alias == key) } ?: AUTO
    }
}

/** The device that took the reading, as the integration knows it from Home Assistant's device registry. */
data class ReadingDevice(
    /** One of the protocol's device type strings (unknown, watch, phone, scale, ...); anything else is treated as unknown. */
    val type: String = "unknown",
    val manufacturer: String? = null,
    val model: String? = null
)

/**
 * One validated reading, ready to become a record. [value] is the primary measurement in
 * the unit Health Connect wants (kilograms, meters, percent, or systolic mmHg); [diastolic]
 * is set for blood pressure only.
 */
data class PendingReading(
    /** `{entity_id}@{measured_at_ms}`; becomes the clientRecordId. */
    val id: String,
    /** From 1; a correction of the same reading is the same id with a higher version. */
    val version: Long,
    val type: WriteBackType,
    val value: Double,
    val diastolic: Double? = null,
    /** The measurement time, never the sync time. */
    val time: Instant,
    /** From the reading, or the phone's zone at [time] when the reading carries none. */
    val zoneOffset: ZoneOffset? = null,
    val recordingMethod: RecordingMethod = RecordingMethod.AUTO,
    val device: ReadingDevice? = null,
    /** Blood pressure only; unknown, standing_up, sitting_down, lying_down or reclining. */
    val bodyPosition: String = "unknown",
    /** Blood pressure only; unknown, left_wrist, right_wrist, left_upper_arm or right_upper_arm. */
    val measurementLocation: String = "unknown",
    /** "state" when the integration used the entity's last change as the time; informational, goes to the log. */
    val timeSource: String? = null
) {
    /** The Home Assistant entity the reading came from, for the log. */
    val entityId: String get() = id.substringBeforeLast('@')
}

/** The Receive settings as stored: the switch, the types, the history switch and the source URL. */
data class ReceiveSettings(
    val enabled: Boolean = false,
    val types: Set<WriteBackType> = emptySet(),
    val olderMeasurements: Boolean = false,
    /** The webhook URL whose response is read; null until Receive picked one. */
    val sourceUrl: String? = null
)

/** What the last responses told the app, for the Receive row. */
data class ReceiveStatus(
    /** The type keys the integration has a mapping for; empty until the first response. */
    val configured: List<String> = emptyList(),
    /** True once the integration answered at all; tells "nothing mapped" from "not asked yet". */
    val answered: Boolean = false,
    /** True when the source URL answered without the protocol block: the integration is too old. */
    val integrationOutdated: Boolean = false,
    val writtenToday: Int = 0
)

/**
 * What the Receive row's one-line summary says (issue #62), decided outside Compose so it
 * can be tested. It names only the types that actually arrive: switched on here, offered by
 * the integration and granted in Health Connect.
 */
sealed interface ReceiveSummary {
    data object Off : ReceiveSummary

    /** On, but the saved section lost its secret or its integration URL: nothing can arrive. */
    data object NeedsPairing : ReceiveSummary

    /** The source URL answered without the protocol block: the integration is too old. */
    data object IntegrationOutdated : ReceiveSummary

    /** On, but no answer from the integration has named its types yet. */
    data object AwaitingTypes : ReceiveSummary

    /** The integration answered, with nothing mapped for this phone. */
    data object NothingMapped : ReceiveSummary

    /** The integration's types are known; none is switched on here yet. */
    data object ChooseType : ReceiveSummary

    /** Switched on and offered, but Health Connect's write permission is missing for all of them. */
    data class PermissionMissing(val types: List<WriteBackType>) : ReceiveSummary

    data class Receiving(val types: List<WriteBackType>, val writtenToday: Int) : ReceiveSummary

    companion object {
        /**
         * [available] is whether the saved section still has a secret and an integration URL
         * (HealthUiState.receiveAvailable); the sync needs both, so the summary does too.
         */
        fun of(receive: ReceiveSettings, status: ReceiveStatus, granted: Set<String>, available: Boolean): ReceiveSummary {
            if (!receive.enabled || receive.sourceUrl == null) return Off
            if (!available) return NeedsPairing
            if (status.integrationOutdated) return IntegrationOutdated
            val offered = status.configured.mapNotNull { WriteBackType.fromKey(it) }
            if (offered.isEmpty()) return if (status.answered) NothingMapped else AwaitingTypes
            val chosen = WriteBackType.entries.filter { it in receive.types && it in offered }
            val working = chosen.filter { it.writePermission in granted }
            return when {
                working.isNotEmpty() -> Receiving(working, status.writtenToday)
                chosen.isNotEmpty() -> PermissionMissing(chosen)
                else -> ChooseType
            }
        }
    }
}
