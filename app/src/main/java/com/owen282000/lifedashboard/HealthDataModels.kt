package com.owen282000.lifedashboard

import androidx.health.connect.client.records.*
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

enum class HealthDataType(val displayName: String, val recordClass: KClass<out Record>) {
    STEPS("Steps", StepsRecord::class),
    SLEEP("Sleep", SleepSessionRecord::class),
    HEART_RATE("Heart Rate", HeartRateRecord::class),
    DISTANCE("Distance", DistanceRecord::class),
    ACTIVE_CALORIES("Active Calories", ActiveCaloriesBurnedRecord::class),
    TOTAL_CALORIES("Total Calories", TotalCaloriesBurnedRecord::class),
    WEIGHT("Weight", WeightRecord::class),
    HEIGHT("Height", HeightRecord::class),
    BLOOD_PRESSURE("Blood Pressure", BloodPressureRecord::class),
    BLOOD_GLUCOSE("Blood Glucose", BloodGlucoseRecord::class),
    OXYGEN_SATURATION("Oxygen Saturation", OxygenSaturationRecord::class),
    BODY_TEMPERATURE("Body Temperature", BodyTemperatureRecord::class),
    RESPIRATORY_RATE("Respiratory Rate", RespiratoryRateRecord::class),
    RESTING_HEART_RATE("Resting Heart Rate", RestingHeartRateRecord::class),
    EXERCISE("Exercise Sessions", ExerciseSessionRecord::class),
    HYDRATION("Hydration", HydrationRecord::class),
    NUTRITION("Nutrition", NutritionRecord::class),
    MINDFULNESS("Mindfulness", MindfulnessSessionRecord::class),
    BODY_FAT("Body Fat", BodyFatRecord::class),
    LEAN_BODY_MASS("Lean Body Mass", LeanBodyMassRecord::class),
    BONE_MASS("Bone Mass", BoneMassRecord::class),
    BODY_WATER_MASS("Body Water Mass", BodyWaterMassRecord::class),
    HEART_RATE_VARIABILITY("Heart Rate Variability", HeartRateVariabilityRmssdRecord::class),
    MENSTRUATION_PERIOD("Menstruation Period", MenstruationPeriodRecord::class),
    MENSTRUATION_FLOW("Menstruation Flow", MenstruationFlowRecord::class),
    BASAL_METABOLIC_RATE("Basal Metabolic Rate", BasalMetabolicRateRecord::class),
    VO2_MAX("VO2 Max", Vo2MaxRecord::class),
    SKIN_TEMPERATURE("Skin Temperature", SkinTemperatureRecord::class),
    BASAL_BODY_TEMPERATURE("Basal Body Temperature", BasalBodyTemperatureRecord::class),
    INTERMENSTRUAL_BLEEDING("Intermenstrual Bleeding", IntermenstrualBleedingRecord::class),
    OVULATION_TEST("Ovulation Test", OvulationTestRecord::class),
    CERVICAL_MUCUS("Cervical Mucus", CervicalMucusRecord::class),
    SEXUAL_ACTIVITY("Sexual Activity", SexualActivityRecord::class)
}

data class HealthData(
    val steps: List<StepsData>,
    val sleep: List<SleepData>,
    val heartRate: List<HeartRateData>,
    val distance: List<DistanceData>,
    val activeCalories: List<ActiveCaloriesData>,
    val totalCalories: List<TotalCaloriesData>,
    val weight: List<WeightData>,
    val height: List<HeightData>,
    val bloodPressure: List<BloodPressureData>,
    val bloodGlucose: List<BloodGlucoseData>,
    val oxygenSaturation: List<OxygenSaturationData>,
    val bodyTemperature: List<BodyTemperatureData>,
    val respiratoryRate: List<RespiratoryRateData>,
    val restingHeartRate: List<RestingHeartRateData>,
    val exercise: List<ExerciseData>,
    val hydration: List<HydrationData>,
    val nutrition: List<NutritionData>,
    val mindfulness: List<MindfulnessData>,
    val bodyFat: List<BodyFatData>,
    val leanBodyMass: List<LeanBodyMassData>,
    val boneMass: List<BoneMassData>,
    val bodyWaterMass: List<BodyWaterMassData>,
    val hrv: List<HrvData>,
    val menstruationPeriod: List<MenstruationPeriodData>,
    val menstruationFlow: List<MenstruationFlowData>,
    val basalMetabolicRate: List<BasalMetabolicRateData>,
    val vo2Max: List<Vo2MaxData>,
    val skinTemperature: List<SkinTemperatureData>,
    val basalBodyTemperature: List<BasalBodyTemperatureData>,
    val intermenstrualBleeding: List<IntermenstrualBleedingData>,
    val ovulationTest: List<OvulationTestData>,
    val cervicalMucus: List<CervicalMucusData>,
    val sexualActivity: List<SexualActivityData>,
    val diagnostics: Map<HealthDataType, TypeDiagnostics> = emptyMap(),
    /** Max metadata.lastModifiedTime per type of the delivered batch; the sync watermark. */
    val watermarks: Map<HealthDataType, Instant> = emptyMap()
)

data class BasalMetabolicRateData(
    val kilocaloriesPerDay: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class Vo2MaxData(
    val vo2MillilitersPerMinuteKilogram: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class SkinTemperatureData(
    val deltaCelsius: Double,
    val baselineCelsius: Double?,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class BasalBodyTemperatureData(
    val celsius: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class IntermenstrualBleedingData(
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class OvulationTestData(
    val result: String,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class CervicalMucusData(
    val appearance: String,
    val sensation: String,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class SexualActivityData(
    val protectionUsed: String,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class MenstruationPeriodData(
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class MenstruationFlowData(
    val flow: String,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

/**
 * Per-data-type read diagnostics, surfaced in the webhook payload so users can see exactly
 * what Health Connect returned for each type. Helps diagnose stale/missing data (e.g. the
 * pagination bug where high-volume types lagged behind).
 */
data class TypeDiagnostics(
    val permissionGranted: Boolean,
    val pageCount: Int,
    val rawRecordCount: Int,
    val filteredRecordCount: Int,
    val minTime: Instant?,
    val maxTime: Instant?,
    val lastSync: Instant?,
    val error: String?
)

data class StepsData(
    val count: Long,
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class SleepData(
    val sessionEndTime: Instant,
    val duration: Duration,
    val stages: List<SleepStage>,
    val source: String? = null,
    val uuid: String? = null
)

data class SleepStage(
    val stage: String,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration
)

data class HeartRateData(
    val bpm: Long,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class DistanceData(
    val meters: Double,
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class ActiveCaloriesData(
    val calories: Double,
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class TotalCaloriesData(
    val calories: Double,
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class WeightData(
    val kilograms: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class HeightData(
    val meters: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class BloodPressureData(
    val systolic: Double,
    val diastolic: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class BloodGlucoseData(
    val mmolPerLiter: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class OxygenSaturationData(
    val percentage: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class BodyTemperatureData(
    val celsius: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class RespiratoryRateData(
    val rate: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class RestingHeartRateData(
    val bpm: Long,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class ExerciseData(
    val type: String,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration,
    val source: String? = null,
    val uuid: String? = null
)

data class HydrationData(
    val liters: Double,
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class NutritionData(
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val startTime: Instant,
    val endTime: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class MindfulnessData(
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration,
    val source: String? = null,
    val uuid: String? = null
)

data class BodyFatData(
    val percentage: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class LeanBodyMassData(
    val kilograms: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class BoneMassData(
    val kilograms: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class BodyWaterMassData(
    val kilograms: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

data class HrvData(
    val heartRateVariabilityMillis: Double,
    val time: Instant,
    val source: String? = null,
    val uuid: String? = null
)

/**
 * One day of deduplicated totals from Health Connect's aggregate API, which merges
 * overlapping records from multiple sources (phone plus watch) instead of double counting.
 */
data class DailyTotals(
    val date: String,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val activeCalories: Double? = null,
    val totalCalories: Double? = null
)

/**
 * Max records delivered per sync for this type, to bound payload size and memory. The batch is
 * capped oldest-first (see [ResilientReadLogic.capOldestFirst]) so later syncs catch up without
 * skipping records.
 */
val HealthDataType.maxRecordsPerSync: Int
    get() = when (this) {
        HealthDataType.HEART_RATE, HealthDataType.STEPS -> 1000
        HealthDataType.HEART_RATE_VARIABILITY, HealthDataType.RESPIRATORY_RATE,
        HealthDataType.SKIN_TEMPERATURE -> 500
        else -> 200
    }

