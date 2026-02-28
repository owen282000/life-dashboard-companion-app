package com.owen282000.lifedashboard

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    HEART_RATE_VARIABILITY("Heart Rate Variability", HeartRateVariabilityRmssdRecord::class)
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
    val hrv: List<HrvData>
)

data class StepsData(
    val count: Long,
    val startTime: Instant,
    val endTime: Instant
)

data class SleepData(
    val sessionEndTime: Instant,
    val duration: Duration,
    val stages: List<SleepStage>
)

data class SleepStage(
    val stage: String,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration
)

data class HeartRateData(
    val bpm: Long,
    val time: Instant
)

data class DistanceData(
    val meters: Double,
    val startTime: Instant,
    val endTime: Instant
)

data class ActiveCaloriesData(
    val calories: Double,
    val startTime: Instant,
    val endTime: Instant
)

data class TotalCaloriesData(
    val calories: Double,
    val startTime: Instant,
    val endTime: Instant
)

data class WeightData(
    val kilograms: Double,
    val time: Instant
)

data class HeightData(
    val meters: Double,
    val time: Instant
)

data class BloodPressureData(
    val systolic: Double,
    val diastolic: Double,
    val time: Instant
)

data class BloodGlucoseData(
    val mmolPerLiter: Double,
    val time: Instant
)

data class OxygenSaturationData(
    val percentage: Double,
    val time: Instant
)

data class BodyTemperatureData(
    val celsius: Double,
    val time: Instant
)

data class RespiratoryRateData(
    val rate: Double,
    val time: Instant
)

data class RestingHeartRateData(
    val bpm: Long,
    val time: Instant
)

data class ExerciseData(
    val type: String,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration
)

data class HydrationData(
    val liters: Double,
    val startTime: Instant,
    val endTime: Instant
)

data class NutritionData(
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val startTime: Instant,
    val endTime: Instant
)

data class MindfulnessData(
    val title: String?,
    val startTime: Instant,
    val endTime: Instant,
    val duration: Duration
)

data class BodyFatData(
    val percentage: Double,
    val time: Instant
)

data class LeanBodyMassData(
    val kilograms: Double,
    val time: Instant
)

data class BoneMassData(
    val kilograms: Double,
    val time: Instant
)

data class BodyWaterMassData(
    val kilograms: Double,
    val time: Instant
)

data class HrvData(
    val heartRateVariabilityMillis: Double,
    val time: Instant
)

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            throw IllegalStateException("Health Connect is not available on this device: ${e.message}", e)
        }
    }

    suspend fun readHealthData(
        enabledTypes: Set<HealthDataType>,
        lastSyncTimestamps: Map<HealthDataType, Instant?>
    ): Result<HealthData> {
        return try {
            val endTime = Instant.now()
            val startTime = endTime.minus(LOOKBACK_HOURS, ChronoUnit.HOURS)

            val stepsData = if (HealthDataType.STEPS in enabledTypes)
                try { readStepsData(startTime, endTime, lastSyncTimestamps[HealthDataType.STEPS]) } catch (e: Exception) { emptyList() } else emptyList()
            val sleepData = if (HealthDataType.SLEEP in enabledTypes)
                try { readSleepData(startTime, endTime, lastSyncTimestamps[HealthDataType.SLEEP]) } catch (e: Exception) { emptyList() } else emptyList()
            val heartRateData = if (HealthDataType.HEART_RATE in enabledTypes)
                try { readHeartRateData(startTime, endTime, lastSyncTimestamps[HealthDataType.HEART_RATE]) } catch (e: Exception) { emptyList() } else emptyList()
            val distanceData = if (HealthDataType.DISTANCE in enabledTypes)
                try { readDistanceData(startTime, endTime, lastSyncTimestamps[HealthDataType.DISTANCE]) } catch (e: Exception) { emptyList() } else emptyList()
            val activeCaloriesData = if (HealthDataType.ACTIVE_CALORIES in enabledTypes)
                try { readActiveCaloriesData(startTime, endTime, lastSyncTimestamps[HealthDataType.ACTIVE_CALORIES]) } catch (e: Exception) { emptyList() } else emptyList()
            val totalCaloriesData = if (HealthDataType.TOTAL_CALORIES in enabledTypes)
                try { readTotalCaloriesData(startTime, endTime, lastSyncTimestamps[HealthDataType.TOTAL_CALORIES]) } catch (e: Exception) { emptyList() } else emptyList()
            val weightData = if (HealthDataType.WEIGHT in enabledTypes)
                try { readWeightData(startTime, endTime, lastSyncTimestamps[HealthDataType.WEIGHT]) } catch (e: Exception) { emptyList() } else emptyList()
            val heightData = if (HealthDataType.HEIGHT in enabledTypes)
                try { readHeightData(startTime, endTime, lastSyncTimestamps[HealthDataType.HEIGHT]) } catch (e: Exception) { emptyList() } else emptyList()
            val bloodPressureData = if (HealthDataType.BLOOD_PRESSURE in enabledTypes)
                try { readBloodPressureData(startTime, endTime, lastSyncTimestamps[HealthDataType.BLOOD_PRESSURE]) } catch (e: Exception) { emptyList() } else emptyList()
            val bloodGlucoseData = if (HealthDataType.BLOOD_GLUCOSE in enabledTypes)
                try { readBloodGlucoseData(startTime, endTime, lastSyncTimestamps[HealthDataType.BLOOD_GLUCOSE]) } catch (e: Exception) { emptyList() } else emptyList()
            val oxygenSaturationData = if (HealthDataType.OXYGEN_SATURATION in enabledTypes)
                try { readOxygenSaturationData(startTime, endTime, lastSyncTimestamps[HealthDataType.OXYGEN_SATURATION]) } catch (e: Exception) { emptyList() } else emptyList()
            val bodyTemperatureData = if (HealthDataType.BODY_TEMPERATURE in enabledTypes)
                try { readBodyTemperatureData(startTime, endTime, lastSyncTimestamps[HealthDataType.BODY_TEMPERATURE]) } catch (e: Exception) { emptyList() } else emptyList()
            val respiratoryRateData = if (HealthDataType.RESPIRATORY_RATE in enabledTypes)
                try { readRespiratoryRateData(startTime, endTime, lastSyncTimestamps[HealthDataType.RESPIRATORY_RATE]) } catch (e: Exception) { emptyList() } else emptyList()
            val restingHeartRateData = if (HealthDataType.RESTING_HEART_RATE in enabledTypes)
                try { readRestingHeartRateData(startTime, endTime, lastSyncTimestamps[HealthDataType.RESTING_HEART_RATE]) } catch (e: Exception) { emptyList() } else emptyList()
            val exerciseData = if (HealthDataType.EXERCISE in enabledTypes)
                try { readExerciseData(startTime, endTime, lastSyncTimestamps[HealthDataType.EXERCISE]) } catch (e: Exception) { emptyList() } else emptyList()
            val hydrationData = if (HealthDataType.HYDRATION in enabledTypes)
                try { readHydrationData(startTime, endTime, lastSyncTimestamps[HealthDataType.HYDRATION]) } catch (e: Exception) { emptyList() } else emptyList()
            val nutritionData = if (HealthDataType.NUTRITION in enabledTypes)
                try { readNutritionData(startTime, endTime, lastSyncTimestamps[HealthDataType.NUTRITION]) } catch (e: Exception) { emptyList() } else emptyList()
            val mindfulnessData = if (HealthDataType.MINDFULNESS in enabledTypes)
                readMindfulnessData(startTime, endTime, lastSyncTimestamps[HealthDataType.MINDFULNESS]) else emptyList()
            val bodyFatData = if (HealthDataType.BODY_FAT in enabledTypes)
                try { readBodyFatData(startTime, endTime, lastSyncTimestamps[HealthDataType.BODY_FAT]) } catch (e: Exception) { emptyList() } else emptyList()
            val leanBodyMassData = if (HealthDataType.LEAN_BODY_MASS in enabledTypes)
                try { readLeanBodyMassData(startTime, endTime, lastSyncTimestamps[HealthDataType.LEAN_BODY_MASS]) } catch (e: Exception) { emptyList() } else emptyList()
            val boneMassData = if (HealthDataType.BONE_MASS in enabledTypes)
                try { readBoneMassData(startTime, endTime, lastSyncTimestamps[HealthDataType.BONE_MASS]) } catch (e: Exception) { emptyList() } else emptyList()
            val bodyWaterMassData = if (HealthDataType.BODY_WATER_MASS in enabledTypes)
                try { readBodyWaterMassData(startTime, endTime, lastSyncTimestamps[HealthDataType.BODY_WATER_MASS]) } catch (e: Exception) { emptyList() } else emptyList()
            val hrvData = if (HealthDataType.HEART_RATE_VARIABILITY in enabledTypes)
                readHrvData(startTime, endTime, lastSyncTimestamps[HealthDataType.HEART_RATE_VARIABILITY]) else emptyList()

            Result.success(HealthData(
                steps = stepsData,
                sleep = sleepData,
                heartRate = heartRateData,
                distance = distanceData,
                activeCalories = activeCaloriesData,
                totalCalories = totalCaloriesData,
                weight = weightData,
                height = heightData,
                bloodPressure = bloodPressureData,
                bloodGlucose = bloodGlucoseData,
                oxygenSaturation = oxygenSaturationData,
                bodyTemperature = bodyTemperatureData,
                respiratoryRate = respiratoryRateData,
                restingHeartRate = restingHeartRateData,
                exercise = exerciseData,
                hydration = hydrationData,
                nutrition = nutritionData,
                mindfulness = mindfulnessData,
                bodyFat = bodyFatData,
                leanBodyMass = leanBodyMassData,
                boneMass = boneMassData,
                bodyWaterMass = bodyWaterMassData,
                hrv = hrvData
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun readStepsData(
        startTime: Instant,
        endTime: Instant,
        lastSync: Instant?
    ): List<StepsData> {
        val request = ReadRecordsRequest(
            recordType = StepsRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )

        val response = healthConnectClient.readRecords(request)

        return response.records
            .filter { record ->
                lastSync == null || record.endTime >= lastSync
            }
            .map { record ->
                StepsData(
                    count = record.count,
                    startTime = record.startTime,
                    endTime = record.endTime
                )
            }
    }

    private suspend fun readSleepData(
        startTime: Instant,
        endTime: Instant,
        lastSync: Instant?
    ): List<SleepData> {
        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
        )

        val response = healthConnectClient.readRecords(request)

        return response.records
            .filter { record ->
                lastSync == null || record.endTime >= lastSync
            }
            .map { record ->
                val stages = record.stages?.map { stage ->
                    SleepStage(
                        stage = stage.stage.toString(),
                        startTime = stage.startTime,
                        endTime = stage.endTime,
                        duration = Duration.between(stage.startTime, stage.endTime)
                    )
                } ?: emptyList()

                SleepData(
                    sessionEndTime = record.endTime,
                    duration = Duration.between(record.startTime, record.endTime),
                    stages = stages
                )
            }
    }

    private suspend fun readHeartRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HeartRateData> {
        val request = ReadRecordsRequest(recordType = HeartRateRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records
            .flatMap { record ->
                record.samples
                    .filter { lastSync == null || it.time >= lastSync }
                    .map { HeartRateData(it.beatsPerMinute, it.time) }
            }
    }

    private suspend fun readDistanceData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<DistanceData> {
        val request = ReadRecordsRequest(recordType = DistanceRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.endTime >= lastSync }
            .map { DistanceData(it.distance.inMeters, it.startTime, it.endTime) }
    }

    private suspend fun readActiveCaloriesData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<ActiveCaloriesData> {
        val request = ReadRecordsRequest(recordType = ActiveCaloriesBurnedRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.endTime >= lastSync }
            .map { ActiveCaloriesData(it.energy.inKilocalories, it.startTime, it.endTime) }
    }

    private suspend fun readTotalCaloriesData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<TotalCaloriesData> {
        val request = ReadRecordsRequest(recordType = TotalCaloriesBurnedRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.endTime >= lastSync }
            .map { TotalCaloriesData(it.energy.inKilocalories, it.startTime, it.endTime) }
    }

    private suspend fun readWeightData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<WeightData> {
        val request = ReadRecordsRequest(recordType = WeightRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { WeightData(it.weight.inKilograms, it.time) }
    }

    private suspend fun readHeightData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HeightData> {
        val request = ReadRecordsRequest(recordType = HeightRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { HeightData(it.height.inMeters, it.time) }
    }

    private suspend fun readBloodPressureData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BloodPressureData> {
        val request = ReadRecordsRequest(recordType = BloodPressureRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { BloodPressureData(it.systolic.inMillimetersOfMercury, it.diastolic.inMillimetersOfMercury, it.time) }
    }

    private suspend fun readBloodGlucoseData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BloodGlucoseData> {
        val request = ReadRecordsRequest(recordType = BloodGlucoseRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { BloodGlucoseData(it.level.inMillimolesPerLiter, it.time) }
    }

    private suspend fun readOxygenSaturationData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<OxygenSaturationData> {
        val request = ReadRecordsRequest(recordType = OxygenSaturationRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { OxygenSaturationData(it.percentage.value, it.time) }
    }

    private suspend fun readBodyTemperatureData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BodyTemperatureData> {
        val request = ReadRecordsRequest(recordType = BodyTemperatureRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { BodyTemperatureData(it.temperature.inCelsius, it.time) }
    }

    private suspend fun readRespiratoryRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<RespiratoryRateData> {
        val request = ReadRecordsRequest(recordType = RespiratoryRateRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { RespiratoryRateData(it.rate, it.time) }
    }

    private suspend fun readRestingHeartRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<RestingHeartRateData> {
        val request = ReadRecordsRequest(recordType = RestingHeartRateRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { RestingHeartRateData(it.beatsPerMinute, it.time) }
    }

    private suspend fun readExerciseData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<ExerciseData> {
        val request = ReadRecordsRequest(recordType = ExerciseSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.endTime >= lastSync }
            .map { ExerciseData(it.exerciseType.toString(), it.startTime, it.endTime, Duration.between(it.startTime, it.endTime)) }
    }

    private suspend fun readHydrationData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HydrationData> {
        val request = ReadRecordsRequest(recordType = HydrationRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.endTime >= lastSync }
            .map { HydrationData(it.volume.inLiters, it.startTime, it.endTime) }
    }

    private suspend fun readNutritionData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<NutritionData> {
        val request = ReadRecordsRequest(recordType = NutritionRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.endTime >= lastSync }
            .map { NutritionData(it.energy?.inKilocalories, it.protein?.inGrams, it.totalCarbohydrate?.inGrams, it.totalFat?.inGrams, it.startTime, it.endTime) }
    }

    private suspend fun readMindfulnessData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<MindfulnessData> {
        return try {
            val availabilityStatus = healthConnectClient.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION
            )
            if (availabilityStatus != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                return emptyList()
            }

            val request = ReadRecordsRequest(recordType = MindfulnessSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
            val response = healthConnectClient.readRecords(request)
            response.records.filter { lastSync == null || it.endTime >= lastSync }
                .map { MindfulnessData(it.title, it.startTime, it.endTime, Duration.between(it.startTime, it.endTime)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun readBodyFatData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BodyFatData> {
        val request = ReadRecordsRequest(recordType = BodyFatRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { BodyFatData(it.percentage.value, it.time) }
    }

    private suspend fun readLeanBodyMassData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<LeanBodyMassData> {
        val request = ReadRecordsRequest(recordType = LeanBodyMassRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { LeanBodyMassData(it.mass.inKilograms, it.time) }
    }

    private suspend fun readBoneMassData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BoneMassData> {
        val request = ReadRecordsRequest(recordType = BoneMassRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { BoneMassData(it.mass.inKilograms, it.time) }
    }

    private suspend fun readBodyWaterMassData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BodyWaterMassData> {
        val request = ReadRecordsRequest(recordType = BodyWaterMassRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
        val response = healthConnectClient.readRecords(request)
        return response.records.filter { lastSync == null || it.time >= lastSync }
            .map { BodyWaterMassData(it.mass.inKilograms, it.time) }
    }

    private suspend fun readHrvData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HrvData> {
        return try {
            val request = ReadRecordsRequest(recordType = HeartRateVariabilityRmssdRecord::class, timeRangeFilter = TimeRangeFilter.between(startTime, endTime))
            val response = healthConnectClient.readRecords(request)
            response.records.filter { lastSync == null || it.time >= lastSync }
                .map { HrvData(it.heartRateVariabilityMillis, it.time) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isHealthConnectAvailable(): Boolean {
        return try {
            HealthConnectClient.getOrCreate(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun hasPermissions(requiredPermissions: Set<String> = ALL_PERMISSIONS): Boolean {
        if (!isHealthConnectAvailable()) return false
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return requiredPermissions.all { it in granted }
    }

    suspend fun getGrantedPermissions(): Set<String> {
        if (!isHealthConnectAvailable()) return emptySet()
        return healthConnectClient.permissionController.getGrantedPermissions()
    }

    suspend fun requestPermissions(permissions: Set<String>): android.content.Intent {
        if (!isHealthConnectAvailable()) {
            throw IllegalStateException("Health Connect is not available on this device")
        }
        val contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        return contract.createIntent(context, permissions.toTypedArray())
    }

    companion object {
        private const val LOOKBACK_HOURS = 168L  // 7 days

        fun getPermissionsForTypes(types: Set<HealthDataType>): Set<String> {
            val permissions = types.map { HealthPermission.getReadPermission(it.recordClass) }.toMutableSet()
            permissions.add("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
            return permissions
        }

        val ALL_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(MindfulnessSessionRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        )
    }
}
