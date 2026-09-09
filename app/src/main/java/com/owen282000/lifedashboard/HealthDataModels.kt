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
    val watermarks: Map<HealthDataType, Instant> = emptyMap(),
    /**
     * Types whose eligible records exceeded maxRecordsPerSync in this read, meaning a backlog
     * remains beyond the delivered batch. The sync loop keeps draining until this is empty.
     */
    val cappedTypes: Set<HealthDataType> = emptySet()
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

