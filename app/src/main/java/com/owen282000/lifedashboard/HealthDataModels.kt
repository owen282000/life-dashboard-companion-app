package com.owen282000.lifedashboard

import androidx.annotation.StringRes
import androidx.health.connect.client.records.*
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Every type the app reads. [displayNameRes] is what the user sees, in the phone's language;
 * the enum name is what the payload, MQTT, the stored preferences and the logs use, so
 * renaming a type on screen never changes those.
 */
enum class HealthDataType(@StringRes val displayNameRes: Int, val recordClass: KClass<out Record>) {
    STEPS(R.string.data_type_steps, StepsRecord::class),
    SLEEP(R.string.data_type_sleep, SleepSessionRecord::class),
    HEART_RATE(R.string.data_type_heart_rate, HeartRateRecord::class),
    DISTANCE(R.string.data_type_distance, DistanceRecord::class),
    ACTIVE_CALORIES(R.string.data_type_active_calories, ActiveCaloriesBurnedRecord::class),
    TOTAL_CALORIES(R.string.data_type_total_calories, TotalCaloriesBurnedRecord::class),
    WEIGHT(R.string.data_type_weight, WeightRecord::class),
    HEIGHT(R.string.data_type_height, HeightRecord::class),
    BLOOD_PRESSURE(R.string.data_type_blood_pressure, BloodPressureRecord::class),
    BLOOD_GLUCOSE(R.string.data_type_blood_glucose, BloodGlucoseRecord::class),
    OXYGEN_SATURATION(R.string.data_type_oxygen_saturation, OxygenSaturationRecord::class),
    BODY_TEMPERATURE(R.string.data_type_body_temperature, BodyTemperatureRecord::class),
    RESPIRATORY_RATE(R.string.data_type_respiratory_rate, RespiratoryRateRecord::class),
    RESTING_HEART_RATE(R.string.data_type_resting_heart_rate, RestingHeartRateRecord::class),
    EXERCISE(R.string.data_type_exercise, ExerciseSessionRecord::class),
    HYDRATION(R.string.data_type_hydration, HydrationRecord::class),
    NUTRITION(R.string.data_type_nutrition, NutritionRecord::class),
    MINDFULNESS(R.string.data_type_mindfulness, MindfulnessSessionRecord::class),
    BODY_FAT(R.string.data_type_body_fat, BodyFatRecord::class),
    LEAN_BODY_MASS(R.string.data_type_lean_body_mass, LeanBodyMassRecord::class),
    BONE_MASS(R.string.data_type_bone_mass, BoneMassRecord::class),
    BODY_WATER_MASS(R.string.data_type_body_water_mass, BodyWaterMassRecord::class),
    HEART_RATE_VARIABILITY(R.string.data_type_heart_rate_variability, HeartRateVariabilityRmssdRecord::class),
    MENSTRUATION_PERIOD(R.string.data_type_menstruation_period, MenstruationPeriodRecord::class),
    MENSTRUATION_FLOW(R.string.data_type_menstruation_flow, MenstruationFlowRecord::class),
    BASAL_METABOLIC_RATE(R.string.data_type_basal_metabolic_rate, BasalMetabolicRateRecord::class),
    VO2_MAX(R.string.data_type_vo2_max, Vo2MaxRecord::class),
    SKIN_TEMPERATURE(R.string.data_type_skin_temperature, SkinTemperatureRecord::class),
    BASAL_BODY_TEMPERATURE(R.string.data_type_basal_body_temperature, BasalBodyTemperatureRecord::class),
    INTERMENSTRUAL_BLEEDING(R.string.data_type_intermenstrual_bleeding, IntermenstrualBleedingRecord::class),
    OVULATION_TEST(R.string.data_type_ovulation_test, OvulationTestRecord::class),
    CERVICAL_MUCUS(R.string.data_type_cervical_mucus, CervicalMucusRecord::class),
    SEXUAL_ACTIVITY(R.string.data_type_sexual_activity, SexualActivityRecord::class)
}

data class HealthData(
    val steps: List<StepsData> = emptyList(),
    val sleep: List<SleepData> = emptyList(),
    val heartRate: List<HeartRateData> = emptyList(),
    val distance: List<DistanceData> = emptyList(),
    val activeCalories: List<ActiveCaloriesData> = emptyList(),
    val totalCalories: List<TotalCaloriesData> = emptyList(),
    val weight: List<WeightData> = emptyList(),
    val height: List<HeightData> = emptyList(),
    val bloodPressure: List<BloodPressureData> = emptyList(),
    val bloodGlucose: List<BloodGlucoseData> = emptyList(),
    val oxygenSaturation: List<OxygenSaturationData> = emptyList(),
    val bodyTemperature: List<BodyTemperatureData> = emptyList(),
    val respiratoryRate: List<RespiratoryRateData> = emptyList(),
    val restingHeartRate: List<RestingHeartRateData> = emptyList(),
    val exercise: List<ExerciseData> = emptyList(),
    val hydration: List<HydrationData> = emptyList(),
    val nutrition: List<NutritionData> = emptyList(),
    val mindfulness: List<MindfulnessData> = emptyList(),
    val bodyFat: List<BodyFatData> = emptyList(),
    val leanBodyMass: List<LeanBodyMassData> = emptyList(),
    val boneMass: List<BoneMassData> = emptyList(),
    val bodyWaterMass: List<BodyWaterMassData> = emptyList(),
    val hrv: List<HrvData> = emptyList(),
    val menstruationPeriod: List<MenstruationPeriodData> = emptyList(),
    val menstruationFlow: List<MenstruationFlowData> = emptyList(),
    val basalMetabolicRate: List<BasalMetabolicRateData> = emptyList(),
    val vo2Max: List<Vo2MaxData> = emptyList(),
    val skinTemperature: List<SkinTemperatureData> = emptyList(),
    val basalBodyTemperature: List<BasalBodyTemperatureData> = emptyList(),
    val intermenstrualBleeding: List<IntermenstrualBleedingData> = emptyList(),
    val ovulationTest: List<OvulationTestData> = emptyList(),
    val cervicalMucus: List<CervicalMucusData> = emptyList(),
    val sexualActivity: List<SexualActivityData> = emptyList(),
    val diagnostics: Map<HealthDataType, TypeDiagnostics> = emptyMap(),
    /** How far each type was read, see [Watermark]; the sync watermark. */
    val watermarks: Map<HealthDataType, Watermark> = emptyMap(),
    /**
     * Types whose eligible records exceeded maxRecordsPerSync in this read, meaning a backlog
     * remains beyond the delivered batch. The sync loop keeps draining until this is empty.
     */
    val cappedTypes: Set<HealthDataType> = emptySet(),
    /**
     * Enabled types this read could not read: an error, a Health Connect call that did not
     * answer in time, or a read step that had used its budget. They keep their watermark and
     * come back empty, so an empty list here does not mean "nothing new" for them.
     */
    val unreadTypes: Set<HealthDataType> = emptySet(),
    /**
     * The id of every record each type's read returned, before the watermark or anything else
     * filtered it: what Health Connect holds in the range read. A deletion of one of these is
     * stale, the record exists (issues #71, #72).
     */
    val readIds: Map<HealthDataType, Set<String>> = emptyMap(),
    /**
     * Health Connect refused a read for its quota, and the read stopped there: the types it did
     * not reach are in [unreadTypes] and keep their watermarks (issue #73).
     */
    val quotaExhausted: Boolean = false,
    /**
     * The lookback anchor to store per type: the moment this read went up to for a type it
     * took completely, the anchor it used for a capped one (see [LookbackWindow.covered]).
     * Stored with the watermarks, the next sync's range reaches back from there. Empty for a
     * backfill.
     */
    val coveredUntil: Map<HealthDataType, Instant> = emptyMap(),
    /**
     * For the bucketed types the read was asked to keep whole windows of (see
     * [WholeWindowRequest]): every record Health Connect holds in [wholeCoverage], changed or
     * not, the app's own Receive writes left out as everywhere else. Null when nothing was kept.
     * Only for building complete buckets; never sent as records.
     */
    val whole: HealthData? = null,
    /** The range per type that [whole] holds everything of. */
    val wholeCoverage: Map<HealthDataType, ReadCoverage> = emptyMap()
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
    val error: String?,
    /** Timestamp range and newest modification time of everything Health Connect returned, before the watermark filter (issue #53). */
    val rawMinTime: Instant? = null,
    val rawMaxTime: Instant? = null,
    val rawLatestModifiedTime: Instant? = null,
    /**
     * Records this app wrote itself (Receive, issue #62) that were new since the watermark and
     * left out of the payload: what came from Home Assistant does not go back to it.
     */
    val ownRecordsSkipped: Int = 0,
    /** Where this read's time range started, see [LookbackWindow]. */
    val readFrom: Instant? = null,
    /** Set when the range could not reach back far enough, see [LookbackWindow.Window.gapFrom]. */
    val lookbackGapFrom: Instant? = null
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
    val uuid: String? = null,
    /** Food or drink name as written by the source app, e.g. Cronometer. */
    val name: String? = null,
    /** "breakfast", "lunch", "dinner", "snack" or "unknown"; null when not set. */
    val mealType: String? = null,
    val details: NutritionDetails = NutritionDetails()
)

/**
 * Every further nutrient Health Connect's NutritionRecord exposes (issue #50). Units are in the
 * property names; null means the source app did not write the nutrient, while a real zero (for
 * example trans fat 0 g) is preserved as a value. [fields] is the single ordered source of truth
 * for the webhook keys, shared by the JSON writer and the schema lockstep test.
 */
data class NutritionDetails(
    val energyFromFatKcal: Double? = null,
    val dietaryFibreG: Double? = null,
    val sugarsG: Double? = null,
    val saturatedFatG: Double? = null,
    val monounsaturatedFatG: Double? = null,
    val polyunsaturatedFatG: Double? = null,
    val unsaturatedFatG: Double? = null,
    val transFatG: Double? = null,
    val cholesterolMg: Double? = null,
    val sodiumMg: Double? = null,
    val potassiumMg: Double? = null,
    val calciumMg: Double? = null,
    val chlorideMg: Double? = null,
    val chromiumMcg: Double? = null,
    val copperMg: Double? = null,
    val iodineMcg: Double? = null,
    val ironMg: Double? = null,
    val magnesiumMg: Double? = null,
    val manganeseMg: Double? = null,
    val molybdenumMcg: Double? = null,
    val phosphorusMg: Double? = null,
    val seleniumMcg: Double? = null,
    val zincMg: Double? = null,
    val vitaminAMcg: Double? = null,
    val vitaminB6Mg: Double? = null,
    val vitaminB12Mcg: Double? = null,
    val vitaminCMg: Double? = null,
    val vitaminDMcg: Double? = null,
    val vitaminEMg: Double? = null,
    val vitaminKMcg: Double? = null,
    val thiaminMg: Double? = null,
    val riboflavinMg: Double? = null,
    val niacinMg: Double? = null,
    val pantothenicAcidMg: Double? = null,
    val biotinMcg: Double? = null,
    val folateMcg: Double? = null,
    val folicAcidMcg: Double? = null,
    val caffeineMg: Double? = null
) {
    /** Webhook key to value, in payload order. */
    val fields: List<Pair<String, Double?>>
        get() = listOf(
            "energy_from_fat_kcal" to energyFromFatKcal,
            "dietary_fibre_g" to dietaryFibreG,
            "sugars_g" to sugarsG,
            "saturated_fat_g" to saturatedFatG,
            "monounsaturated_fat_g" to monounsaturatedFatG,
            "polyunsaturated_fat_g" to polyunsaturatedFatG,
            "unsaturated_fat_g" to unsaturatedFatG,
            "trans_fat_g" to transFatG,
            "cholesterol_mg" to cholesterolMg,
            "sodium_mg" to sodiumMg,
            "potassium_mg" to potassiumMg,
            "calcium_mg" to calciumMg,
            "chloride_mg" to chlorideMg,
            "chromium_mcg" to chromiumMcg,
            "copper_mg" to copperMg,
            "iodine_mcg" to iodineMcg,
            "iron_mg" to ironMg,
            "magnesium_mg" to magnesiumMg,
            "manganese_mg" to manganeseMg,
            "molybdenum_mcg" to molybdenumMcg,
            "phosphorus_mg" to phosphorusMg,
            "selenium_mcg" to seleniumMcg,
            "zinc_mg" to zincMg,
            "vitamin_a_mcg" to vitaminAMcg,
            "vitamin_b6_mg" to vitaminB6Mg,
            "vitamin_b12_mcg" to vitaminB12Mcg,
            "vitamin_c_mg" to vitaminCMg,
            "vitamin_d_mcg" to vitaminDMcg,
            "vitamin_e_mg" to vitaminEMg,
            "vitamin_k_mcg" to vitaminKMcg,
            "thiamin_mg" to thiaminMg,
            "riboflavin_mg" to riboflavinMg,
            "niacin_mg" to niacinMg,
            "pantothenic_acid_mg" to pantothenicAcidMg,
            "biotin_mcg" to biotinMcg,
            "folate_mcg" to folateMcg,
            "folic_acid_mcg" to folicAcidMcg,
            "caffeine_mg" to caffeineMg
        )

    companion object {
        /** All webhook keys this class can emit, for the schema lockstep test. */
        val JSON_KEYS: List<String> = NutritionDetails().fields.map { it.first }
    }
}

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
 * One day of totals from Health Connect's aggregate API, which counts every stretch of time once:
 * overlapping records, from several sources (phone plus watch) or from one, are merged by the
 * priority list and, within one app, the record written last. Records that do not overlap all
 * count, so a copy a source misplaces into the next minute is counted too (DailyTotalsOverlapTest).
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
 *
 * Total calories is a minute series like steps: Fitbit writes one record a minute and rewrites a
 * day of them at a time, about 180 bytes each, so 1000 is about 180 KB. At the old 200 such a
 * rewrite took every pass of a sync (issue #73).
 */
val HealthDataType.maxRecordsPerSync: Int
    get() = when (this) {
        HealthDataType.HEART_RATE, HealthDataType.STEPS, HealthDataType.TOTAL_CALORIES -> 1000
        HealthDataType.HEART_RATE_VARIABILITY, HealthDataType.RESPIRATORY_RATE,
        HealthDataType.SKIN_TEMPERATURE -> 500
        else -> 200
    }
