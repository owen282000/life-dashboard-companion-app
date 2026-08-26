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

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            throw IllegalStateException("Health Connect is not available on this device: ${e.message}", e)
        }
    }

    // Per-run diagnostics, keyed by data type. Reset at the start of every readHealthData() call.
    // Populated by readAllRecords() (page/raw counts) and the per-type read methods (filtered
    // count + min/max), then enriched with permission + lastSync info before being returned.
    private val diagnostics = mutableMapOf<HealthDataType, TypeDiagnostics>()

    suspend fun readHealthData(
        enabledTypes: Set<HealthDataType>,
        lastSyncTimestamps: Map<HealthDataType, Instant?>
    ): Result<HealthData> {
        return try {
            diagnostics.clear()
            val grantedPermissions = getGrantedPermissions()
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
            val menstruationPeriodData = if (HealthDataType.MENSTRUATION_PERIOD in enabledTypes)
                try { readMenstruationPeriodData(startTime, endTime, lastSyncTimestamps[HealthDataType.MENSTRUATION_PERIOD]) } catch (e: Exception) { emptyList() } else emptyList()
            val menstruationFlowData = if (HealthDataType.MENSTRUATION_FLOW in enabledTypes)
                try { readMenstruationFlowData(startTime, endTime, lastSyncTimestamps[HealthDataType.MENSTRUATION_FLOW]) } catch (e: Exception) { emptyList() } else emptyList()

            // Ensure every enabled type has a diagnostics entry (even if it read 0 records or
            // its permission is missing) and enrich each with permission + lastSync info.
            enabledTypes.forEach { type ->
                val permission = HealthPermission.getReadPermission(type.recordClass)
                val granted = permission in grantedPermissions
                val existing = diagnostics[type]
                diagnostics[type] = (existing ?: TypeDiagnostics(
                    permissionGranted = granted,
                    pageCount = 0,
                    rawRecordCount = 0,
                    filteredRecordCount = 0,
                    minTime = null,
                    maxTime = null,
                    lastSync = null,
                    error = null
                )).copy(
                    permissionGranted = granted,
                    lastSync = lastSyncTimestamps[type]
                )
            }

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
                hrv = hrvData,
                menstruationPeriod = menstruationPeriodData,
                menstruationFlow = menstruationFlowData,
                diagnostics = diagnostics.toMap()
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads all records with the bisection fallback from [ResilientReadLogic.readResilient],
     * so a malformed record from a source app cannot fail the entire type (issue #12).
     */
    private suspend fun <T : Record> readAllRecordsResilient(
        recordType: KClass<T>,
        startTime: Instant,
        endTime: Instant
    ): PagedResult<T> {
        return ResilientReadLogic.readResilient(
            startTime = startTime,
            endTime = endTime,
            idOf = { record: T -> record.metadata.id }
        ) { windowStart, windowEnd -> readAllRecords(recordType, windowStart, windowEnd) }
    }

    /**
     * Reads ALL records of the given type within the time range, following Health Connect's
     * pagination via pageToken. A single readRecords() call only returns the first page
     * (Health Connect caps pages, default ~1000 records), so high-volume types like Steps and
     * HeartRate would otherwise have their newest records left out of the first page and appear
     * stale. Looping until pageToken is null guarantees the full result set, including the most
     * recent records.
     */
    private suspend fun <T : Record> readAllRecords(
        recordType: KClass<T>,
        startTime: Instant,
        endTime: Instant
    ): PagedResult<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            val request = ReadRecordsRequest(
                recordType = recordType,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                pageToken = pageToken
            )
            val response = healthConnectClient.readRecords(request)
            records.addAll(response.records)
            pageCount++
            pageToken = response.pageToken
        } while (pageToken != null)
        return PagedResult(records, pageCount)
    }

    /**
     * Reads all pages for a record type, filters out records at/before lastSync (using `>` so the
     * exact boundary record is not re-sent), records per-type diagnostics (page/raw/filtered counts
     * and the min/max timestamp of the filtered set), and returns the filtered records ready to map.
     * Any read error is captured into diagnostics and rethrown so the caller's catch keeps the
     * existing "empty list on failure" behavior.
     *
     * [timeOf] selects the record timestamp to compare against lastSync and to compute min/max from.
     */
    private suspend fun <T : Record> readFiltered(
        type: HealthDataType,
        recordType: KClass<T>,
        startTime: Instant,
        endTime: Instant,
        lastSync: Instant?,
        timeOf: (T) -> Instant
    ): List<T> {
        try {
            val paged = readAllRecordsResilient(recordType, startTime, endTime)
            val filtered = paged.records.filter { lastSync == null || timeOf(it) > lastSync }
            val limited = ResilientReadLogic.capOldestFirst(filtered, type.maxRecordsPerSync, timeOf)
            val times = limited.map(timeOf)
            recordDiag(
                type = type,
                pageCount = paged.pageCount,
                rawRecordCount = paged.records.size,
                filteredRecordCount = limited.size,
                minTime = times.minOrNull(),
                maxTime = times.maxOrNull(),
                error = skippedWindowsNote(paged.skippedWindows)
            )
            return limited
        } catch (e: Exception) {
            recordDiag(type = type, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun recordDiag(
        type: HealthDataType,
        pageCount: Int = 0,
        rawRecordCount: Int = 0,
        filteredRecordCount: Int = 0,
        minTime: Instant? = null,
        maxTime: Instant? = null,
        error: String? = null
    ) {
        diagnostics[type] = TypeDiagnostics(
            permissionGranted = false, // filled in later in readHealthData()
            pageCount = pageCount,
            rawRecordCount = rawRecordCount,
            filteredRecordCount = filteredRecordCount,
            minTime = minTime,
            maxTime = maxTime,
            lastSync = null, // filled in later in readHealthData()
            error = error
        )
    }

    /** Most recent heart rate sample of the past day, used by the About screen easter egg. */
    suspend fun latestHeartRateBpm(): Long? = try {
        val now = Instant.now()
        val response = healthConnectClient.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofDays(1)), now),
                ascendingOrder = false,
                pageSize = 1
            )
        )
        response.records.firstOrNull()?.samples?.maxByOrNull { it.time }?.beatsPerMinute
    } catch (e: Exception) {
        null
    }

    private suspend fun readStepsData(
        startTime: Instant,
        endTime: Instant,
        lastSync: Instant?
    ): List<StepsData> {
        return readFiltered(HealthDataType.STEPS, StepsRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { record ->
                StepsData(
                    count = record.count,
                    startTime = record.startTime,
                    endTime = record.endTime,
                    source = record.metadata.dataOrigin.packageName,
                    uuid = record.metadata.id
                )
            }
    }

    private suspend fun readSleepData(
        startTime: Instant,
        endTime: Instant,
        lastSync: Instant?
    ): List<SleepData> {
        return readFiltered(HealthDataType.SLEEP, SleepSessionRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { record ->
                val stages = record.stages?.map { stage ->
                    SleepStage(
                        stage = sleepStageToString(stage.stage),
                        startTime = stage.startTime,
                        endTime = stage.endTime,
                        duration = Duration.between(stage.startTime, stage.endTime)
                    )
                } ?: emptyList()

                SleepData(
                    sessionEndTime = record.endTime,
                    duration = Duration.between(record.startTime, record.endTime),
                    stages = stages,
                    source = record.metadata.dataOrigin.packageName,
                    uuid = record.metadata.id
                )
            }
    }

    private suspend fun readHeartRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HeartRateData> {
        try {
            val paged = readAllRecordsResilient(HeartRateRecord::class, startTime, endTime)
            val rawSamples = paged.records.sumOf { it.samples.size }
            // Samples are paired with their parent record's data origin so each delivered
            // sample can carry its source app.
            val filtered = paged.records.flatMap { record ->
                record.samples.filter { lastSync == null || it.time > lastSync }
                    .map { sample -> sample to record }
            }
            // Heart-rate records can contain many samples. Keep the oldest pending samples so
            // lastSync advances in bounded batches without making newer samples unreachable.
            val limited = ResilientReadLogic.capOldestFirst(
                filtered, HealthDataType.HEART_RATE.maxRecordsPerSync
            ) { it.first.time }
            val times = limited.map { it.first.time }
            recordDiag(
                type = HealthDataType.HEART_RATE,
                pageCount = paged.pageCount,
                rawRecordCount = rawSamples,
                filteredRecordCount = limited.size,
                minTime = times.minOrNull(),
                maxTime = times.maxOrNull(),
                error = skippedWindowsNote(paged.skippedWindows)
            )
            // Samples within one record share the record id; suffix the sample time so the
            // delivered uuid stays unique yet stable across re-sends.
            return limited.map { (sample, record) ->
                HeartRateData(
                    sample.beatsPerMinute,
                    sample.time,
                    record.metadata.dataOrigin.packageName,
                    "${record.metadata.id}#${sample.time.toEpochMilli()}"
                )
            }
        } catch (e: Exception) {
            recordDiag(type = HealthDataType.HEART_RATE, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private suspend fun readDistanceData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<DistanceData> {
        return readFiltered(HealthDataType.DISTANCE, DistanceRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { DistanceData(it.distance.inMeters, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readActiveCaloriesData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<ActiveCaloriesData> {
        return readFiltered(HealthDataType.ACTIVE_CALORIES, ActiveCaloriesBurnedRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { ActiveCaloriesData(it.energy.inKilocalories, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readTotalCaloriesData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<TotalCaloriesData> {
        return readFiltered(HealthDataType.TOTAL_CALORIES, TotalCaloriesBurnedRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { TotalCaloriesData(it.energy.inKilocalories, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readWeightData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<WeightData> {
        return readFiltered(HealthDataType.WEIGHT, WeightRecord::class, startTime, endTime, lastSync) { it.time }
            .map { WeightData(it.weight.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readHeightData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HeightData> {
        return readFiltered(HealthDataType.HEIGHT, HeightRecord::class, startTime, endTime, lastSync) { it.time }
            .map { HeightData(it.height.inMeters, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBloodPressureData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BloodPressureData> {
        return readFiltered(HealthDataType.BLOOD_PRESSURE, BloodPressureRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BloodPressureData(it.systolic.inMillimetersOfMercury, it.diastolic.inMillimetersOfMercury, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBloodGlucoseData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BloodGlucoseData> {
        return readFiltered(HealthDataType.BLOOD_GLUCOSE, BloodGlucoseRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BloodGlucoseData(it.level.inMillimolesPerLiter, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readOxygenSaturationData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<OxygenSaturationData> {
        return readFiltered(HealthDataType.OXYGEN_SATURATION, OxygenSaturationRecord::class, startTime, endTime, lastSync) { it.time }
            .map { OxygenSaturationData(it.percentage.value, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBodyTemperatureData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BodyTemperatureData> {
        return readFiltered(HealthDataType.BODY_TEMPERATURE, BodyTemperatureRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BodyTemperatureData(it.temperature.inCelsius, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readRespiratoryRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<RespiratoryRateData> {
        return readFiltered(HealthDataType.RESPIRATORY_RATE, RespiratoryRateRecord::class, startTime, endTime, lastSync) { it.time }
            .map { RespiratoryRateData(it.rate, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readRestingHeartRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<RestingHeartRateData> {
        return readFiltered(HealthDataType.RESTING_HEART_RATE, RestingHeartRateRecord::class, startTime, endTime, lastSync) { it.time }
            .map { RestingHeartRateData(it.beatsPerMinute, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readExerciseData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<ExerciseData> {
        return readFiltered(HealthDataType.EXERCISE, ExerciseSessionRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { ExerciseData(it.exerciseType.toString(), it.startTime, it.endTime, Duration.between(it.startTime, it.endTime), it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readHydrationData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HydrationData> {
        return readFiltered(HealthDataType.HYDRATION, HydrationRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { HydrationData(it.volume.inLiters, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readNutritionData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<NutritionData> {
        return readFiltered(HealthDataType.NUTRITION, NutritionRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { NutritionData(it.energy?.inKilocalories, it.protein?.inGrams, it.totalCarbohydrate?.inGrams, it.totalFat?.inGrams, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readMindfulnessData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<MindfulnessData> {
        return try {
            val availabilityStatus = healthConnectClient.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION
            )
            if (availabilityStatus != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                return emptyList()
            }

            readFiltered(HealthDataType.MINDFULNESS, MindfulnessSessionRecord::class, startTime, endTime, lastSync) { it.endTime }
                .map { MindfulnessData(it.title, it.startTime, it.endTime, Duration.between(it.startTime, it.endTime), it.metadata.dataOrigin.packageName, it.metadata.id) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun readBodyFatData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BodyFatData> {
        return readFiltered(HealthDataType.BODY_FAT, BodyFatRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BodyFatData(it.percentage.value, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readLeanBodyMassData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<LeanBodyMassData> {
        return readFiltered(HealthDataType.LEAN_BODY_MASS, LeanBodyMassRecord::class, startTime, endTime, lastSync) { it.time }
            .map { LeanBodyMassData(it.mass.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBoneMassData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BoneMassData> {
        return readFiltered(HealthDataType.BONE_MASS, BoneMassRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BoneMassData(it.mass.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBodyWaterMassData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BodyWaterMassData> {
        return readFiltered(HealthDataType.BODY_WATER_MASS, BodyWaterMassRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BodyWaterMassData(it.mass.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readHrvData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<HrvData> {
        return try {
            readFiltered(HealthDataType.HEART_RATE_VARIABILITY, HeartRateVariabilityRmssdRecord::class, startTime, endTime, lastSync) { it.time }
                .map { HrvData(it.heartRateVariabilityMillis, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
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

    private suspend fun readMenstruationPeriodData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<MenstruationPeriodData> {
        return readFiltered(HealthDataType.MENSTRUATION_PERIOD, MenstruationPeriodRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { MenstruationPeriodData(it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readMenstruationFlowData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<MenstruationFlowData> {
        return readFiltered(HealthDataType.MENSTRUATION_FLOW, MenstruationFlowRecord::class, startTime, endTime, lastSync) { it.time }
            .map { MenstruationFlowData(menstruationFlowToString(it.flow), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private fun menstruationFlowToString(flow: Int): String = when (flow) {
        MenstruationFlowRecord.FLOW_LIGHT -> "light"
        MenstruationFlowRecord.FLOW_MEDIUM -> "medium"
        MenstruationFlowRecord.FLOW_HEAVY -> "heavy"
        else -> "unknown"
    }

    private fun sleepStageToString(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        else -> "unknown"
    }

    companion object {
        private const val LOOKBACK_HOURS = 168L  // 7 days
        private fun skippedWindowsNote(skippedWindows: Int): String? =
            if (skippedWindows > 0) {
                "Skipped $skippedWindows unreadable window(s) of max " +
                    "${ResilientReadLogic.MIN_BISECT_WINDOW.toMinutes()} min " +
                    "containing malformed records from the source app"
            } else {
                null
            }

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
