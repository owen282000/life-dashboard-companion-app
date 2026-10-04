package com.owen282000.lifedashboard

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset

/**
 * What Health Connect knows about a record beyond its values (P2-7): when it was last written,
 * the writing app's own id and version for it, how it was recorded, on which device, and the
 * zone offset it was measured in. Sent under `metadata` on every record when Record metadata
 * is on (off by default: it adds bytes to every record, a lot of them for dense heart rate).
 *
 * The motivating case is a night of sleep that a band keeps revising after waking: without
 * [lastModified] a receiver cannot tell an intermediate duration from the settled one.
 */
data class RecordMeta(
    val lastModified: Instant,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val recordingMethod: Int,
    val device: Device?,
    /** The offset of an instantaneous record, or the start of an interval record. */
    val startZoneOffset: ZoneOffset?,
    /** The end of an interval record; null for an instantaneous one. */
    val endZoneOffset: ZoneOffset?,
    val interval: Boolean
) {

    /** The `metadata` object of a record. Anything Health Connect did not have is left out. */
    fun toJson(): JsonObject = buildJsonObject {
        put("last_modified", lastModified.toString())
        clientRecordId?.let {
            put("client_record_id", it)
            put("client_record_version", clientRecordVersion)
        }
        put("recording_method", recordingMethodName(recordingMethod))
        device?.let { d ->
            put(
                "device",
                buildJsonObject {
                    d.manufacturer?.let { put("manufacturer", it) }
                    d.model?.let { put("model", it) }
                    put("type", deviceTypeName(d.type))
                }
            )
        }
        if (interval) {
            startZoneOffset?.let { put("start_zone_offset", it.id) }
            endZoneOffset?.let { put("end_zone_offset", it.id) }
        } else {
            startZoneOffset?.let { put("zone_offset", it.id) }
        }
    }

    companion object {
        fun of(record: Record): RecordMeta {
            val meta = record.metadata
            val offsets = offsets(record)
            return RecordMeta(
                lastModified = meta.lastModifiedTime,
                clientRecordId = meta.clientRecordId,
                clientRecordVersion = meta.clientRecordVersion,
                recordingMethod = meta.recordingMethod,
                device = meta.device,
                startZoneOffset = offsets.start,
                endZoneOffset = offsets.end,
                interval = offsets.interval
            )
        }

        private class Offsets(val start: ZoneOffset?, val end: ZoneOffset?, val interval: Boolean)

        /**
         * The zone offsets of [record]. Health Connect's interval and instantaneous record
         * interfaces are internal to its library, so every type the app reads is named here; a
         * type missing from the list gets no offset rather than a wrong one.
         */
        private fun offsets(record: Record): Offsets = when (record) {
            is ActiveCaloriesBurnedRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is DistanceRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is ExerciseSessionRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is HeartRateRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is HydrationRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is MenstruationPeriodRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is MindfulnessSessionRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is NutritionRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is SkinTemperatureRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is SleepSessionRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is StepsRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is TotalCaloriesBurnedRecord -> Offsets(record.startZoneOffset, record.endZoneOffset, interval = true)
            is BasalBodyTemperatureRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BasalMetabolicRateRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BloodGlucoseRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BloodPressureRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BodyFatRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BodyTemperatureRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BodyWaterMassRecord -> Offsets(record.zoneOffset, null, interval = false)
            is BoneMassRecord -> Offsets(record.zoneOffset, null, interval = false)
            is CervicalMucusRecord -> Offsets(record.zoneOffset, null, interval = false)
            is HeartRateVariabilityRmssdRecord -> Offsets(record.zoneOffset, null, interval = false)
            is HeightRecord -> Offsets(record.zoneOffset, null, interval = false)
            is IntermenstrualBleedingRecord -> Offsets(record.zoneOffset, null, interval = false)
            is LeanBodyMassRecord -> Offsets(record.zoneOffset, null, interval = false)
            is MenstruationFlowRecord -> Offsets(record.zoneOffset, null, interval = false)
            is OvulationTestRecord -> Offsets(record.zoneOffset, null, interval = false)
            is OxygenSaturationRecord -> Offsets(record.zoneOffset, null, interval = false)
            is RespiratoryRateRecord -> Offsets(record.zoneOffset, null, interval = false)
            is RestingHeartRateRecord -> Offsets(record.zoneOffset, null, interval = false)
            is SexualActivityRecord -> Offsets(record.zoneOffset, null, interval = false)
            is Vo2MaxRecord -> Offsets(record.zoneOffset, null, interval = false)
            is WeightRecord -> Offsets(record.zoneOffset, null, interval = false)
            else -> Offsets(null, null, interval = false)
        }

        fun recordingMethodName(method: Int): String = when (method) {
            Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "active"
            Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "automatic"
            Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "manual"
            else -> "unknown"
        }

        fun deviceTypeName(type: Int): String = when (type) {
            Device.TYPE_WATCH -> "watch"
            Device.TYPE_PHONE -> "phone"
            Device.TYPE_SCALE -> "scale"
            Device.TYPE_RING -> "ring"
            Device.TYPE_HEAD_MOUNTED -> "head_mounted"
            Device.TYPE_FITNESS_BAND -> "fitness_band"
            Device.TYPE_CHEST_STRAP -> "chest_strap"
            Device.TYPE_SMART_DISPLAY -> "smart_display"
            else -> "unknown"
        }
    }
}
