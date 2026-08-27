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

    // Per-run sync watermarks: the max metadata.lastModifiedTime of each type's DELIVERED
    // batch. Stored by HealthSyncManager after the payload is read, so late backfills (whose
    // modification time is recent even when their record timestamps are old) are picked up
    // by the next sync instead of being skipped forever.
    private val watermarks = mutableMapOf<HealthDataType, Instant>()

    /**
     * Reads all enabled types. The default window is the trailing [LOOKBACK_HOURS]; backfill
     * passes an explicit historical window (with empty lastSyncTimestamps so nothing is
     * filtered against watermarks).
     */
    suspend fun readHealthData(
        enabledTypes: Set<HealthDataType>,
        lastSyncTimestamps: Map<HealthDataType, Instant?>,
        windowStart: Instant? = null,
        windowEnd: Instant? = null
    ): Result<HealthData> {
        return try {
            diagnostics.clear()
            watermarks.clear()
            val grantedPermissions = getGrantedPermissions()
            val endTime = windowEnd ?: Instant.now()
            val startTime = windowStart ?: endTime.minus(LOOKBACK_HOURS, ChronoUnit.HOURS)

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
            val basalMetabolicRateData = if (HealthDataType.BASAL_METABOLIC_RATE in enabledTypes)
                try { readBasalMetabolicRateData(startTime, endTime, lastSyncTimestamps[HealthDataType.BASAL_METABOLIC_RATE]) } catch (e: Exception) { emptyList() } else emptyList()
            val vo2MaxData = if (HealthDataType.VO2_MAX in enabledTypes)
                try { readVo2MaxData(startTime, endTime, lastSyncTimestamps[HealthDataType.VO2_MAX]) } catch (e: Exception) { emptyList() } else emptyList()
            val skinTemperatureData = if (HealthDataType.SKIN_TEMPERATURE in enabledTypes)
                try { readSkinTemperatureData(startTime, endTime, lastSyncTimestamps[HealthDataType.SKIN_TEMPERATURE]) } catch (e: Exception) { emptyList() } else emptyList()
            val basalBodyTemperatureData = if (HealthDataType.BASAL_BODY_TEMPERATURE in enabledTypes)
                try { readBasalBodyTemperatureData(startTime, endTime, lastSyncTimestamps[HealthDataType.BASAL_BODY_TEMPERATURE]) } catch (e: Exception) { emptyList() } else emptyList()
            val intermenstrualBleedingData = if (HealthDataType.INTERMENSTRUAL_BLEEDING in enabledTypes)
                try { readIntermenstrualBleedingData(startTime, endTime, lastSyncTimestamps[HealthDataType.INTERMENSTRUAL_BLEEDING]) } catch (e: Exception) { emptyList() } else emptyList()
            val ovulationTestData = if (HealthDataType.OVULATION_TEST in enabledTypes)
                try { readOvulationTestData(startTime, endTime, lastSyncTimestamps[HealthDataType.OVULATION_TEST]) } catch (e: Exception) { emptyList() } else emptyList()
            val cervicalMucusData = if (HealthDataType.CERVICAL_MUCUS in enabledTypes)
                try { readCervicalMucusData(startTime, endTime, lastSyncTimestamps[HealthDataType.CERVICAL_MUCUS]) } catch (e: Exception) { emptyList() } else emptyList()
            val sexualActivityData = if (HealthDataType.SEXUAL_ACTIVITY in enabledTypes)
                try { readSexualActivityData(startTime, endTime, lastSyncTimestamps[HealthDataType.SEXUAL_ACTIVITY]) } catch (e: Exception) { emptyList() } else emptyList()

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
                basalMetabolicRate = basalMetabolicRateData,
                vo2Max = vo2MaxData,
                skinTemperature = skinTemperatureData,
                basalBodyTemperature = basalBodyTemperatureData,
                intermenstrualBleeding = intermenstrualBleedingData,
                ovulationTest = ovulationTestData,
                cervicalMucus = cervicalMucusData,
                sexualActivity = sexualActivityData,
                diagnostics = diagnostics.toMap(),
                watermarks = watermarks.toMap()
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
     * Reads all pages for a record type and filters against the per-type watermark using
     * metadata.lastModifiedTime rather than the record's own timestamp. Source apps like Zepp
     * and Garmin upload watch data with the ORIGINAL timestamps hours later; a time-based
     * watermark would skip those backfilled records forever, while their modification time is
     * recent and picks them up on the next sync. Edited records re-sync the same way (servers
     * can deduplicate on the record uuid). The advanced watermark per type is collected in
     * [watermarks] as the max modification time of the DELIVERED batch, so records dropped by
     * the oldest-first cap stay above the watermark and catch up in later syncs.
     *
     * [timeOf] selects the record timestamp used for the diagnostics min/max display.
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
            val filtered = paged.records.filter {
                lastSync == null || it.metadata.lastModifiedTime > lastSync
            }
            val limited = ResilientReadLogic.capOldestFirst(filtered, type.maxRecordsPerSync) {
                it.metadata.lastModifiedTime
            }
            limited.maxOfOrNull { it.metadata.lastModifiedTime }?.let { watermarks[type] = it }
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

    /**
     * Deduplicated per-day totals for the last [days] full days plus today, computed with the
     * aggregate API: Health Connect merges overlapping records from multiple sources (phone
     * plus watch), so these totals never double count the way raw record sums can. Only
     * metrics whose type is enabled are requested, to stay within granted permissions.
     */
    suspend fun readDailyTotals(days: Int, enabledTypes: Set<HealthDataType>): List<DailyTotals> {
        val metrics = buildSet {
            if (HealthDataType.STEPS in enabledTypes) add(StepsRecord.COUNT_TOTAL)
            if (HealthDataType.DISTANCE in enabledTypes) add(DistanceRecord.DISTANCE_TOTAL)
            if (HealthDataType.ACTIVE_CALORIES in enabledTypes) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthDataType.TOTAL_CALORIES in enabledTypes) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (metrics.isEmpty()) return emptyList()

        return try {
            val today = java.time.LocalDate.now()
            val response = healthConnectClient.aggregateGroupByPeriod(
                androidx.health.connect.client.request.AggregateGroupByPeriodRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(
                        today.minusDays(days.toLong()).atStartOfDay(),
                        java.time.LocalDateTime.now()
                    ),
                    timeRangeSlicer = java.time.Period.ofDays(1)
                )
            )
            response.map { bucket ->
                DailyTotals(
                    date = bucket.startTime.toLocalDate().toString(),
                    steps = bucket.result[StepsRecord.COUNT_TOTAL],
                    distanceMeters = bucket.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                    activeCalories = bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                    totalCalories = bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                )
            }.filter { it.steps != null || it.distanceMeters != null || it.activeCalories != null || it.totalCalories != null }
        } catch (e: Exception) {
            emptyList()
        }
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
            // Sample-carrying records are filtered and capped at RECORD granularity on their
            // modification time: a record is either fully delivered or fully deferred, so the
            // watermark (max delivered modification time) never splits a record.
            val newRecords = paged.records
                .filter { lastSync == null || it.metadata.lastModifiedTime > lastSync }
                .sortedBy { it.metadata.lastModifiedTime }
            val includedRecords = mutableListOf<HeartRateRecord>()
            var sampleCount = 0
            for (record in newRecords) {
                if (sampleCount >= HealthDataType.HEART_RATE.maxRecordsPerSync) break
                includedRecords += record
                sampleCount += record.samples.size
            }
            includedRecords.maxOfOrNull { it.metadata.lastModifiedTime }
                ?.let { watermarks[HealthDataType.HEART_RATE] = it }
            val limited = includedRecords.flatMap { record ->
                record.samples.map { sample -> sample to record }
            }
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

    private suspend fun readBasalMetabolicRateData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BasalMetabolicRateData> {
        return readFiltered(HealthDataType.BASAL_METABOLIC_RATE, BasalMetabolicRateRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BasalMetabolicRateData(it.basalMetabolicRate.inKilocaloriesPerDay, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readVo2MaxData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<Vo2MaxData> {
        return readFiltered(HealthDataType.VO2_MAX, Vo2MaxRecord::class, startTime, endTime, lastSync) { it.time }
            .map { Vo2MaxData(it.vo2MillilitersPerMinuteKilogram, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    /**
     * Skin temperature records hold many delta samples (e.g. one per few minutes overnight), so
     * like heart rate this filters and caps at RECORD granularity on modification time; samples
     * inherit the parent record's baseline and data origin.
     */
    private suspend fun readSkinTemperatureData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<SkinTemperatureData> {
        try {
            val paged = readAllRecordsResilient(SkinTemperatureRecord::class, startTime, endTime)
            val rawSamples = paged.records.sumOf { it.deltas.size }
            val newRecords = paged.records
                .filter { lastSync == null || it.metadata.lastModifiedTime > lastSync }
                .sortedBy { it.metadata.lastModifiedTime }
            val includedRecords = mutableListOf<SkinTemperatureRecord>()
            var sampleCount = 0
            for (record in newRecords) {
                if (sampleCount >= HealthDataType.SKIN_TEMPERATURE.maxRecordsPerSync) break
                includedRecords += record
                sampleCount += record.deltas.size
            }
            includedRecords.maxOfOrNull { it.metadata.lastModifiedTime }
                ?.let { watermarks[HealthDataType.SKIN_TEMPERATURE] = it }
            val limited = includedRecords.flatMap { record ->
                record.deltas.map { SkinSample(it, record.baseline, record.metadata.dataOrigin.packageName, "${record.metadata.id}#${it.time.toEpochMilli()}") }
            }
            val times = limited.map { it.delta.time }
            recordDiag(
                type = HealthDataType.SKIN_TEMPERATURE,
                pageCount = paged.pageCount,
                rawRecordCount = rawSamples,
                filteredRecordCount = limited.size,
                minTime = times.minOrNull(),
                maxTime = times.maxOrNull(),
                error = skippedWindowsNote(paged.skippedWindows)
            )
            return limited.map { s ->
                SkinTemperatureData(s.delta.delta.inCelsius, s.baseline?.inCelsius, s.delta.time, s.source, s.uuid)
            }
        } catch (e: Exception) {
            recordDiag(type = HealthDataType.SKIN_TEMPERATURE, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private suspend fun readBasalBodyTemperatureData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<BasalBodyTemperatureData> {
        return readFiltered(HealthDataType.BASAL_BODY_TEMPERATURE, BasalBodyTemperatureRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BasalBodyTemperatureData(it.temperature.inCelsius, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readIntermenstrualBleedingData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<IntermenstrualBleedingData> {
        return readFiltered(HealthDataType.INTERMENSTRUAL_BLEEDING, IntermenstrualBleedingRecord::class, startTime, endTime, lastSync) { it.time }
            .map { IntermenstrualBleedingData(it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readOvulationTestData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<OvulationTestData> {
        return readFiltered(HealthDataType.OVULATION_TEST, OvulationTestRecord::class, startTime, endTime, lastSync) { it.time }
            .map { OvulationTestData(ovulationTestResultToString(it.result), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readCervicalMucusData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<CervicalMucusData> {
        return readFiltered(HealthDataType.CERVICAL_MUCUS, CervicalMucusRecord::class, startTime, endTime, lastSync) { it.time }
            .map { CervicalMucusData(cervicalMucusAppearanceToString(it.appearance), cervicalMucusSensationToString(it.sensation), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readSexualActivityData(startTime: Instant, endTime: Instant, lastSync: Instant?): List<SexualActivityData> {
        return readFiltered(HealthDataType.SEXUAL_ACTIVITY, SexualActivityRecord::class, startTime, endTime, lastSync) { it.time }
            .map { SexualActivityData(sexualActivityProtectionToString(it.protectionUsed), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private data class SkinSample(
        val delta: SkinTemperatureRecord.Delta,
        val baseline: androidx.health.connect.client.units.Temperature?,
        val source: String,
        val uuid: String
    )

    private fun ovulationTestResultToString(result: Int): String = when (result) {
        OvulationTestRecord.RESULT_POSITIVE -> "positive"
        OvulationTestRecord.RESULT_HIGH -> "high"
        OvulationTestRecord.RESULT_NEGATIVE -> "negative"
        OvulationTestRecord.RESULT_INCONCLUSIVE -> "inconclusive"
        else -> "unknown"
    }

    private fun cervicalMucusAppearanceToString(appearance: Int): String = when (appearance) {
        CervicalMucusRecord.APPEARANCE_DRY -> "dry"
        CervicalMucusRecord.APPEARANCE_STICKY -> "sticky"
        CervicalMucusRecord.APPEARANCE_CREAMY -> "creamy"
        CervicalMucusRecord.APPEARANCE_WATERY -> "watery"
        CervicalMucusRecord.APPEARANCE_EGG_WHITE -> "egg_white"
        CervicalMucusRecord.APPEARANCE_UNUSUAL -> "unusual"
        else -> "unknown"
    }

    private fun cervicalMucusSensationToString(sensation: Int): String = when (sensation) {
        CervicalMucusRecord.SENSATION_LIGHT -> "light"
        CervicalMucusRecord.SENSATION_MEDIUM -> "medium"
        CervicalMucusRecord.SENSATION_HEAVY -> "heavy"
        else -> "unknown"
    }

    private fun sexualActivityProtectionToString(protection: Int): String = when (protection) {
        SexualActivityRecord.PROTECTION_USED_PROTECTED -> "protected"
        SexualActivityRecord.PROTECTION_USED_UNPROTECTED -> "unprotected"
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
