package com.owen282000.lifedashboard

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import com.owen282000.lifedashboard.NutritionSupport.toNutritionData
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectManager(
    private val context: Context,
    /**
     * Where the client comes from. Always the real one in the app; the instrumented suite wraps
     * it to count calls or to make one hang, which no shell command can do to Health Connect.
     */
    private val clientFactory: (Context) -> HealthConnectClient = { HealthConnectClient.getOrCreate(it) }
) {

    private val healthConnectClient by lazy {
        try {
            clientFactory(context)
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
    private val watermarks = mutableMapOf<HealthDataType, Watermark>()

    // Types whose eligible records exceeded the per-sync cap in the current read; the sync
    // loop uses this to keep draining the backlog instead of waiting for the next scheduled run.
    private val cappedTypes = mutableSetOf<HealthDataType>()

    // Types that came back empty because they could not be read, see HealthData.unreadTypes.
    private val unreadTypes = mutableSetOf<HealthDataType>()

    /**
     * Reads all enabled types. The default window per type reaches [LookbackWindow.LOOKBACK]
     * back from [coveredUntil], the last read that took all of it (see [LookbackWindow]);
     * backfill passes an explicit historical window (with empty lastSyncTimestamps so nothing
     * is filtered against watermarks).
     */
    suspend fun readHealthData(
        enabledTypes: Set<HealthDataType>,
        lastSyncTimestamps: Map<HealthDataType, Watermark?>,
        windowStart: Instant? = null,
        windowEnd: Instant? = null,
        coveredUntil: Map<HealthDataType, Instant?> = emptyMap()
    ): Result<HealthData> {
        return try {
            diagnostics.clear()
            watermarks.clear()
            cappedTypes.clear()
            unreadTypes.clear()
            readStartedAt = System.currentTimeMillis()
            val grantedPermissions = bounded("the granted permissions") { getGrantedPermissions() }
            val endTime = windowEnd ?: Instant.now()
            val windows = if (windowStart != null) emptyMap() else
                enabledTypes.associateWith { LookbackWindow.of(endTime, coveredUntil[it]) }
            fun startOf(type: HealthDataType): Instant = windowStart ?: windows.getValue(type).start

            val stepsData = if (HealthDataType.STEPS in enabledTypes)
                readType(HealthDataType.STEPS) { readStepsData(startOf(HealthDataType.STEPS), endTime, lastSyncTimestamps[HealthDataType.STEPS]) } else emptyList()
            val sleepData = if (HealthDataType.SLEEP in enabledTypes)
                readType(HealthDataType.SLEEP) { readSleepData(startOf(HealthDataType.SLEEP), endTime, lastSyncTimestamps[HealthDataType.SLEEP]) } else emptyList()
            val heartRateData = if (HealthDataType.HEART_RATE in enabledTypes)
                readType(HealthDataType.HEART_RATE) { readHeartRateData(startOf(HealthDataType.HEART_RATE), endTime, lastSyncTimestamps[HealthDataType.HEART_RATE]) } else emptyList()
            val distanceData = if (HealthDataType.DISTANCE in enabledTypes)
                readType(HealthDataType.DISTANCE) { readDistanceData(startOf(HealthDataType.DISTANCE), endTime, lastSyncTimestamps[HealthDataType.DISTANCE]) } else emptyList()
            val activeCaloriesData = if (HealthDataType.ACTIVE_CALORIES in enabledTypes)
                readType(HealthDataType.ACTIVE_CALORIES) { readActiveCaloriesData(startOf(HealthDataType.ACTIVE_CALORIES), endTime, lastSyncTimestamps[HealthDataType.ACTIVE_CALORIES]) } else emptyList()
            val totalCaloriesData = if (HealthDataType.TOTAL_CALORIES in enabledTypes)
                readType(HealthDataType.TOTAL_CALORIES) { readTotalCaloriesData(startOf(HealthDataType.TOTAL_CALORIES), endTime, lastSyncTimestamps[HealthDataType.TOTAL_CALORIES]) } else emptyList()
            val weightData = if (HealthDataType.WEIGHT in enabledTypes)
                readType(HealthDataType.WEIGHT) { readWeightData(startOf(HealthDataType.WEIGHT), endTime, lastSyncTimestamps[HealthDataType.WEIGHT]) } else emptyList()
            val heightData = if (HealthDataType.HEIGHT in enabledTypes)
                readType(HealthDataType.HEIGHT) { readHeightData(startOf(HealthDataType.HEIGHT), endTime, lastSyncTimestamps[HealthDataType.HEIGHT]) } else emptyList()
            val bloodPressureData = if (HealthDataType.BLOOD_PRESSURE in enabledTypes)
                readType(HealthDataType.BLOOD_PRESSURE) { readBloodPressureData(startOf(HealthDataType.BLOOD_PRESSURE), endTime, lastSyncTimestamps[HealthDataType.BLOOD_PRESSURE]) } else emptyList()
            val bloodGlucoseData = if (HealthDataType.BLOOD_GLUCOSE in enabledTypes)
                readType(HealthDataType.BLOOD_GLUCOSE) { readBloodGlucoseData(startOf(HealthDataType.BLOOD_GLUCOSE), endTime, lastSyncTimestamps[HealthDataType.BLOOD_GLUCOSE]) } else emptyList()
            val oxygenSaturationData = if (HealthDataType.OXYGEN_SATURATION in enabledTypes)
                readType(HealthDataType.OXYGEN_SATURATION) { readOxygenSaturationData(startOf(HealthDataType.OXYGEN_SATURATION), endTime, lastSyncTimestamps[HealthDataType.OXYGEN_SATURATION]) } else emptyList()
            val bodyTemperatureData = if (HealthDataType.BODY_TEMPERATURE in enabledTypes)
                readType(HealthDataType.BODY_TEMPERATURE) { readBodyTemperatureData(startOf(HealthDataType.BODY_TEMPERATURE), endTime, lastSyncTimestamps[HealthDataType.BODY_TEMPERATURE]) } else emptyList()
            val respiratoryRateData = if (HealthDataType.RESPIRATORY_RATE in enabledTypes)
                readType(HealthDataType.RESPIRATORY_RATE) { readRespiratoryRateData(startOf(HealthDataType.RESPIRATORY_RATE), endTime, lastSyncTimestamps[HealthDataType.RESPIRATORY_RATE]) } else emptyList()
            val restingHeartRateData = if (HealthDataType.RESTING_HEART_RATE in enabledTypes)
                readType(HealthDataType.RESTING_HEART_RATE) { readRestingHeartRateData(startOf(HealthDataType.RESTING_HEART_RATE), endTime, lastSyncTimestamps[HealthDataType.RESTING_HEART_RATE]) } else emptyList()
            val exerciseData = if (HealthDataType.EXERCISE in enabledTypes)
                readType(HealthDataType.EXERCISE) { readExerciseData(startOf(HealthDataType.EXERCISE), endTime, lastSyncTimestamps[HealthDataType.EXERCISE]) } else emptyList()
            val hydrationData = if (HealthDataType.HYDRATION in enabledTypes)
                readType(HealthDataType.HYDRATION) { readHydrationData(startOf(HealthDataType.HYDRATION), endTime, lastSyncTimestamps[HealthDataType.HYDRATION]) } else emptyList()
            val nutritionData = if (HealthDataType.NUTRITION in enabledTypes)
                readType(HealthDataType.NUTRITION) { readNutritionData(startOf(HealthDataType.NUTRITION), endTime, lastSyncTimestamps[HealthDataType.NUTRITION]) } else emptyList()
            val mindfulnessData = if (HealthDataType.MINDFULNESS in enabledTypes)
                readType(HealthDataType.MINDFULNESS) { readMindfulnessData(startOf(HealthDataType.MINDFULNESS), endTime, lastSyncTimestamps[HealthDataType.MINDFULNESS]) } else emptyList()
            val bodyFatData = if (HealthDataType.BODY_FAT in enabledTypes)
                readType(HealthDataType.BODY_FAT) { readBodyFatData(startOf(HealthDataType.BODY_FAT), endTime, lastSyncTimestamps[HealthDataType.BODY_FAT]) } else emptyList()
            val leanBodyMassData = if (HealthDataType.LEAN_BODY_MASS in enabledTypes)
                readType(HealthDataType.LEAN_BODY_MASS) { readLeanBodyMassData(startOf(HealthDataType.LEAN_BODY_MASS), endTime, lastSyncTimestamps[HealthDataType.LEAN_BODY_MASS]) } else emptyList()
            val boneMassData = if (HealthDataType.BONE_MASS in enabledTypes)
                readType(HealthDataType.BONE_MASS) { readBoneMassData(startOf(HealthDataType.BONE_MASS), endTime, lastSyncTimestamps[HealthDataType.BONE_MASS]) } else emptyList()
            val bodyWaterMassData = if (HealthDataType.BODY_WATER_MASS in enabledTypes)
                readType(HealthDataType.BODY_WATER_MASS) { readBodyWaterMassData(startOf(HealthDataType.BODY_WATER_MASS), endTime, lastSyncTimestamps[HealthDataType.BODY_WATER_MASS]) } else emptyList()
            val hrvData = if (HealthDataType.HEART_RATE_VARIABILITY in enabledTypes)
                readType(HealthDataType.HEART_RATE_VARIABILITY) { readHrvData(startOf(HealthDataType.HEART_RATE_VARIABILITY), endTime, lastSyncTimestamps[HealthDataType.HEART_RATE_VARIABILITY]) } else emptyList()
            val menstruationPeriodData = if (HealthDataType.MENSTRUATION_PERIOD in enabledTypes)
                readType(HealthDataType.MENSTRUATION_PERIOD) { readMenstruationPeriodData(startOf(HealthDataType.MENSTRUATION_PERIOD), endTime, lastSyncTimestamps[HealthDataType.MENSTRUATION_PERIOD]) } else emptyList()
            val menstruationFlowData = if (HealthDataType.MENSTRUATION_FLOW in enabledTypes)
                readType(HealthDataType.MENSTRUATION_FLOW) { readMenstruationFlowData(startOf(HealthDataType.MENSTRUATION_FLOW), endTime, lastSyncTimestamps[HealthDataType.MENSTRUATION_FLOW]) } else emptyList()
            val basalMetabolicRateData = if (HealthDataType.BASAL_METABOLIC_RATE in enabledTypes)
                readType(HealthDataType.BASAL_METABOLIC_RATE) { readBasalMetabolicRateData(startOf(HealthDataType.BASAL_METABOLIC_RATE), endTime, lastSyncTimestamps[HealthDataType.BASAL_METABOLIC_RATE]) } else emptyList()
            val vo2MaxData = if (HealthDataType.VO2_MAX in enabledTypes)
                readType(HealthDataType.VO2_MAX) { readVo2MaxData(startOf(HealthDataType.VO2_MAX), endTime, lastSyncTimestamps[HealthDataType.VO2_MAX]) } else emptyList()
            val skinTemperatureData = if (HealthDataType.SKIN_TEMPERATURE in enabledTypes)
                readType(HealthDataType.SKIN_TEMPERATURE) { readSkinTemperatureData(startOf(HealthDataType.SKIN_TEMPERATURE), endTime, lastSyncTimestamps[HealthDataType.SKIN_TEMPERATURE]) } else emptyList()
            val basalBodyTemperatureData = if (HealthDataType.BASAL_BODY_TEMPERATURE in enabledTypes)
                readType(HealthDataType.BASAL_BODY_TEMPERATURE) { readBasalBodyTemperatureData(startOf(HealthDataType.BASAL_BODY_TEMPERATURE), endTime, lastSyncTimestamps[HealthDataType.BASAL_BODY_TEMPERATURE]) } else emptyList()
            val intermenstrualBleedingData = if (HealthDataType.INTERMENSTRUAL_BLEEDING in enabledTypes)
                readType(HealthDataType.INTERMENSTRUAL_BLEEDING) { readIntermenstrualBleedingData(startOf(HealthDataType.INTERMENSTRUAL_BLEEDING), endTime, lastSyncTimestamps[HealthDataType.INTERMENSTRUAL_BLEEDING]) } else emptyList()
            val ovulationTestData = if (HealthDataType.OVULATION_TEST in enabledTypes)
                readType(HealthDataType.OVULATION_TEST) { readOvulationTestData(startOf(HealthDataType.OVULATION_TEST), endTime, lastSyncTimestamps[HealthDataType.OVULATION_TEST]) } else emptyList()
            val cervicalMucusData = if (HealthDataType.CERVICAL_MUCUS in enabledTypes)
                readType(HealthDataType.CERVICAL_MUCUS) { readCervicalMucusData(startOf(HealthDataType.CERVICAL_MUCUS), endTime, lastSyncTimestamps[HealthDataType.CERVICAL_MUCUS]) } else emptyList()
            val sexualActivityData = if (HealthDataType.SEXUAL_ACTIVITY in enabledTypes)
                readType(HealthDataType.SEXUAL_ACTIVITY) { readSexualActivityData(startOf(HealthDataType.SEXUAL_ACTIVITY), endTime, lastSyncTimestamps[HealthDataType.SEXUAL_ACTIVITY]) } else emptyList()

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
                    lastSync = lastSyncTimestamps[type]?.time,
                    readFrom = startOf(type),
                    lookbackGapFrom = windows[type]?.gapFrom
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
                watermarks = watermarks.toMap(),
                cappedTypes = cappedTypes.toSet(),
                unreadTypes = unreadTypes.toSet(),
                // A backfill reads history and moves nothing of the sync's, this included.
                coveredUntil = if (windowStart != null) emptyMap() else
                    LookbackWindow.covered(enabledTypes, cappedTypes, unreadTypes, endTime, coveredUntil)
            ))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One type's read inside [readHealthData]. A type that fails does not fail the others: its
     * error is in the diagnostics and it contributes nothing this time. A cancellation is not
     * a failure of the type; it goes through, so a stopped worker stops here and not after
     * reading every other type too.
     */
    /** When the current [readHealthData] started, for [READ_BUDGET_MS]. */
    private var readStartedAt = 0L

    /**
     * One type's read. A type that errors, a Health Connect call inside it that does not answer
     * within [CALL_TIMEOUT_MS], or a type that starts after the whole read step has used
     * [READ_BUDGET_MS], comes back empty with the reason in its `_diagnostics.error`. It sets no
     * watermark, so the next sync reads the same records again: nothing is skipped, only late.
     */
    private suspend fun <T> readType(type: HealthDataType, read: suspend () -> List<T>): List<T> {
        if (System.currentTimeMillis() - readStartedAt >= READ_BUDGET_MS) {
            recordDiag(type = type, error = "skipped: the read step used its budget of ${READ_BUDGET_MS / 1000} s")
            unreadTypes += type
            return emptyList()
        }
        return try {
            read()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (diagnostics[type]?.error == null) recordDiag(type = type, error = e.message ?: e.javaClass.simpleName)
            unreadTypes += type
            emptyList()
        }
    }

    /**
     * A Health Connect call that gives up after [CALL_TIMEOUT_MS]. A scheduled run that starts
     * while the phone dozes can find Health Connect cold and a call that never returns; a hang
     * is not an exception, so without a bound it would hold the sync until Android stopped the
     * worker, the way the deletion step did in 1.18.0. The limit is per call rather than per
     * type: a large backlog that keeps returning pages is slow but fine, one page that does not
     * come back is not. Never [kotlinx.coroutines.withTimeout], whose exception is a
     * cancellation and would stop the sync instead of skipping one type.
     */
    private suspend fun <R : Any> bounded(what: String, call: suspend () -> R): R =
        withTimeoutOrNull(CALL_TIMEOUT_MS) { call() } ?: throw HealthConnectTimeoutException(what, CALL_TIMEOUT_MS)

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
            val response = bounded("a page of ${recordType.simpleName}") { healthConnectClient.readRecords(request) }
            records.addAll(response.records)
            pageCount++
            // Health Connect can signal completion with an empty token as well as null.
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
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
        lastSync: Watermark?,
        timeOf: (T) -> Instant
    ): List<T> {
        try {
            val paged = readAllRecordsResilient(recordType, startTime, endTime)
            val fresh = paged.records.filter {
                lastSync == null || lastSync.admits(it.metadata.lastModifiedTime, it.metadata.id)
            }
            // What this app wrote itself (Receive) came from Home Assistant and does not go
            // back to it; see ownRecordsPartition for the watermark rule.
            val (own, filtered) = ownRecordsPartition(fresh)
            val limited = ResilientReadLogic.capOldestFirst(
                filtered,
                type.maxRecordsPerSync,
                timeOf = { it.metadata.lastModifiedTime },
                idOf = { it.metadata.id }
            )
            if (limited.size < filtered.size) cappedTypes += type
            watermarkFor(limited, own, capped = limited.size < filtered.size)?.let { watermarks[type] = it }
            val times = limited.map(timeOf)
            val rawTimes = paged.records.map(timeOf)
            recordDiag(
                type = type,
                pageCount = paged.pageCount,
                rawRecordCount = paged.records.size,
                filteredRecordCount = limited.size,
                minTime = times.minOrNull(),
                maxTime = times.maxOrNull(),
                error = skippedWindowsNote(paged.skippedWindows),
                rawMinTime = rawTimes.minOrNull(),
                rawMaxTime = rawTimes.maxOrNull(),
                rawLatestModifiedTime = paged.records.maxOfOrNull { it.metadata.lastModifiedTime },
                ownRecordsSkipped = own.size
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
        error: String? = null,
        rawMinTime: Instant? = null,
        rawMaxTime: Instant? = null,
        rawLatestModifiedTime: Instant? = null,
        ownRecordsSkipped: Int = 0
    ) {
        diagnostics[type] = TypeDiagnostics(
            permissionGranted = false, // filled in later in readHealthData()
            pageCount = pageCount,
            rawRecordCount = rawRecordCount,
            filteredRecordCount = filteredRecordCount,
            minTime = minTime,
            maxTime = maxTime,
            lastSync = null, // filled in later in readHealthData()
            error = error,
            rawMinTime = rawMinTime,
            rawMaxTime = rawMaxTime,
            rawLatestModifiedTime = rawLatestModifiedTime,
            ownRecordsSkipped = ownRecordsSkipped
        )
    }

    /**
     * The own-record rules of Receive (issue #62), see [ResilientReadLogic.partitionOwn] and
     * [ResilientReadLogic.watermarkAfter]. Health Connect sets dataOrigin to the writing
     * package and it cannot be forged; together with the client record id that Receive
     * always sets it identifies what Receive wrote (see [ResilientReadLogic.isReceiveWrite]).
     * There is no setting, because nobody wants their own measurements returned to them.
     */
    private fun <T : Record> ownRecordsPartition(records: List<T>): Pair<List<T>, List<T>> {
        val own = context.packageName
        return ResilientReadLogic.partitionOwn(records) {
            ResilientReadLogic.isReceiveWrite(it.metadata.dataOrigin.packageName, own, it.metadata.clientRecordId)
        }
    }

    private fun <T : Record> watermarkFor(delivered: List<T>, own: List<T>, capped: Boolean): Watermark? =
        ResilientReadLogic.watermarkAfter(delivered, own, capped, timeOf = { it.metadata.lastModifiedTime }, idOf = { it.metadata.id })

    /**
     * Deduplicated per-day totals for the last [days] full days plus today, computed with the
     * aggregate API: Health Connect merges overlapping records from multiple sources (phone
     * plus watch), so these totals never double count the way raw record sums can. Only
     * metrics whose type is enabled are requested, to stay within granted permissions.
     */
    suspend fun readDailyTotals(days: Int, enabledTypes: Set<HealthDataType>): List<DailyTotals> {
        val today = java.time.LocalDate.now()
        return readDailyTotalsBetween(today.minusDays(days.toLong()).atStartOfDay(), java.time.LocalDateTime.now(), enabledTypes)
    }

    /**
     * The same totals for every local day between [start] and [end], one entry per day that
     * has any of the enabled metrics. A backfill asks for the days its window touches, so a
     * receiver gets each historical day's real total rather than the raw records' sum.
     */
    suspend fun readDailyTotalsBetween(
        start: java.time.LocalDateTime,
        end: java.time.LocalDateTime,
        enabledTypes: Set<HealthDataType>
    ): List<DailyTotals> {
        val metrics = buildSet {
            if (HealthDataType.STEPS in enabledTypes) add(StepsRecord.COUNT_TOTAL)
            if (HealthDataType.DISTANCE in enabledTypes) add(DistanceRecord.DISTANCE_TOTAL)
            if (HealthDataType.ACTIVE_CALORIES in enabledTypes) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthDataType.TOTAL_CALORIES in enabledTypes) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (metrics.isEmpty() || !start.isBefore(end)) return emptyList()

        return try {
            // Bounded like every read: daily totals that do not come back are left out of this
            // payload rather than holding the records behind them.
            val response = bounded("the daily totals") {
                healthConnectClient.aggregateGroupByPeriod(
                    androidx.health.connect.client.request.AggregateGroupByPeriodRequest(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        timeRangeSlicer = java.time.Period.ofDays(1)
                    )
                )
            }
            response.map { bucket ->
                DailyTotals(
                    date = bucket.startTime.toLocalDate().toString(),
                    steps = bucket.result[StepsRecord.COUNT_TOTAL],
                    distanceMeters = bucket.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                    activeCalories = bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                    totalCalories = bucket.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                )
            }.filter { it.steps != null || it.distanceMeters != null || it.activeCalories != null || it.totalCalories != null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun readStepsData(
        startTime: Instant,
        endTime: Instant,
        lastSync: Watermark?
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
        lastSync: Watermark?
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

    private suspend fun readHeartRateData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<HeartRateData> {
        try {
            val paged = readAllRecordsResilient(HeartRateRecord::class, startTime, endTime)
            val rawSamples = paged.records.sumOf { it.samples.size }
            // Sample-carrying records are filtered and capped at RECORD granularity on their
            // modification time and id: a record is either fully delivered or fully deferred,
            // and the watermark resumes right after the last one taken.
            val (own, newRecords) = ownRecordsPartition(
                paged.records.filter { lastSync == null || lastSync.admits(it.metadata.lastModifiedTime, it.metadata.id) }
            )
            val includedRecords = ResilientReadLogic.capRecordsBySamples(
                newRecords,
                HealthDataType.HEART_RATE.maxRecordsPerSync,
                samplesOf = { it.samples.size },
                timeOf = { it.metadata.lastModifiedTime },
                idOf = { it.metadata.id }
            )
            val capped = includedRecords.size < newRecords.size
            if (capped) cappedTypes += HealthDataType.HEART_RATE
            watermarkFor(includedRecords, own, capped)?.let { watermarks[HealthDataType.HEART_RATE] = it }
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
                error = skippedWindowsNote(paged.skippedWindows),
                ownRecordsSkipped = own.size
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

    private suspend fun readDistanceData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<DistanceData> {
        return readFiltered(HealthDataType.DISTANCE, DistanceRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { DistanceData(it.distance.inMeters, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readActiveCaloriesData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<ActiveCaloriesData> {
        return readFiltered(HealthDataType.ACTIVE_CALORIES, ActiveCaloriesBurnedRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { ActiveCaloriesData(it.energy.inKilocalories, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readTotalCaloriesData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<TotalCaloriesData> {
        return readFiltered(HealthDataType.TOTAL_CALORIES, TotalCaloriesBurnedRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { TotalCaloriesData(it.energy.inKilocalories, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readWeightData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<WeightData> {
        return readFiltered(HealthDataType.WEIGHT, WeightRecord::class, startTime, endTime, lastSync) { it.time }
            .map { WeightData(it.weight.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readHeightData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<HeightData> {
        return readFiltered(HealthDataType.HEIGHT, HeightRecord::class, startTime, endTime, lastSync) { it.time }
            .map { HeightData(it.height.inMeters, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBloodPressureData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BloodPressureData> {
        return readFiltered(HealthDataType.BLOOD_PRESSURE, BloodPressureRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BloodPressureData(it.systolic.inMillimetersOfMercury, it.diastolic.inMillimetersOfMercury, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBloodGlucoseData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BloodGlucoseData> {
        return readFiltered(HealthDataType.BLOOD_GLUCOSE, BloodGlucoseRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BloodGlucoseData(it.level.inMillimolesPerLiter, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readOxygenSaturationData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<OxygenSaturationData> {
        return readFiltered(HealthDataType.OXYGEN_SATURATION, OxygenSaturationRecord::class, startTime, endTime, lastSync) { it.time }
            .map { OxygenSaturationData(it.percentage.value, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBodyTemperatureData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BodyTemperatureData> {
        return readFiltered(HealthDataType.BODY_TEMPERATURE, BodyTemperatureRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BodyTemperatureData(it.temperature.inCelsius, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readRespiratoryRateData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<RespiratoryRateData> {
        return readFiltered(HealthDataType.RESPIRATORY_RATE, RespiratoryRateRecord::class, startTime, endTime, lastSync) { it.time }
            .map { RespiratoryRateData(it.rate, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readRestingHeartRateData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<RestingHeartRateData> {
        return readFiltered(HealthDataType.RESTING_HEART_RATE, RestingHeartRateRecord::class, startTime, endTime, lastSync) { it.time }
            .map { RestingHeartRateData(it.beatsPerMinute, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readExerciseData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<ExerciseData> {
        return readFiltered(HealthDataType.EXERCISE, ExerciseSessionRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { ExerciseData(it.exerciseType.toString(), it.startTime, it.endTime, Duration.between(it.startTime, it.endTime), it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readHydrationData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<HydrationData> {
        return readFiltered(HealthDataType.HYDRATION, HydrationRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { HydrationData(it.volume.inLiters, it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readNutritionData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<NutritionData> {
        return readFiltered(HealthDataType.NUTRITION, NutritionRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { it.toNutritionData() }
    }

    private suspend fun readMindfulnessData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<MindfulnessData> {
        return try {
            val availabilityStatus = healthConnectClient.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION
            )
            if (availabilityStatus != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                return emptyList()
            }

            readFiltered(HealthDataType.MINDFULNESS, MindfulnessSessionRecord::class, startTime, endTime, lastSync) { it.endTime }
                .map { MindfulnessData(it.title, it.startTime, it.endTime, Duration.between(it.startTime, it.endTime), it.metadata.dataOrigin.packageName, it.metadata.id) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun readBodyFatData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BodyFatData> {
        return readFiltered(HealthDataType.BODY_FAT, BodyFatRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BodyFatData(it.percentage.value, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readLeanBodyMassData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<LeanBodyMassData> {
        return readFiltered(HealthDataType.LEAN_BODY_MASS, LeanBodyMassRecord::class, startTime, endTime, lastSync) { it.time }
            .map { LeanBodyMassData(it.mass.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBoneMassData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BoneMassData> {
        return readFiltered(HealthDataType.BONE_MASS, BoneMassRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BoneMassData(it.mass.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readBodyWaterMassData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BodyWaterMassData> {
        return readFiltered(HealthDataType.BODY_WATER_MASS, BodyWaterMassRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BodyWaterMassData(it.mass.inKilograms, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readHrvData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<HrvData> {
        return try {
            readFiltered(HealthDataType.HEART_RATE_VARIABILITY, HeartRateVariabilityRmssdRecord::class, startTime, endTime, lastSync) { it.time }
                .map { HrvData(it.heartRateVariabilityMillis, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

    /**
     * Reads the deletions Health Connect recorded for [type] since the token the app stored last
     * time, and returns the token to store for next time (issue #61).
     *
     * A deletion leaves no record behind, so a read can never see it; only the changes API
     * reports one, by the id of the record that is gone. Upsertions are not sent from here: the
     * normal read carries them. Only the ones timestamped before [readFrom], where that read's
     * range starts, are counted into [ChangesResult.outsideWindow], because the read never sees
     * them (see [OutsideWindow]).
     *
     * The first call for a type has no token, so it registers one and returns nothing. That is
     * correct rather than unfortunate: Health Connect starts tracking from the moment a token is
     * issued, and deletions from before that were never observable.
     *
     * A token Health Connect refuses (expired after about 30 days without a sync) yields
     * [ChangesResult.expired] and a fresh token, so the caller can say in the payload that this
     * type may have missed a deletion.
     *
     * [ownRecordIds] are the metadata ids of records this app wrote itself (Receive, issue
     * #62). A deletion change names only the id, not the package, so this is the only way to
     * tell that a deleted record came from Home Assistant in the first place; those are left
     * out, because Home Assistant withdrew them itself and does not need to hear it back.
     */
    suspend fun readDeletions(
        type: HealthDataType,
        storedToken: String?,
        ownRecordIds: Set<String> = emptySet(),
        readFrom: Instant? = null
    ): ChangesResult {
        if (!isHealthConnectAvailable()) {
            return ChangesResult(error = "Health Connect is not available")
        }
        val request = ChangesTokenRequest(recordTypes = setOf(type.recordClass))
        return try {
            if (storedToken == null) {
                return ChangesResult(nextToken = healthConnectClient.getChangesToken(request))
            }

            val deleted = mutableListOf<DeletedRecord>()
            var outside: OutsideWindow? = null
            var token: String = storedToken
            var expired = false
            // A changes feed is paged; hasMore says another page is waiting behind this token.
            // The cap bounds the loop, because a feed that answered hasMore with a token it had
            // already handed out would spin this coroutine forever and hang the sync.
            var unread = false
            for (page in 1..MAX_CHANGES_PAGES) {
                val response = healthConnectClient.getChanges(token)
                if (response.changesTokenExpired) {
                    expired = true
                    token = healthConnectClient.getChangesToken(request)
                    break
                }
                response.changes.filterIsInstance<DeletionChange>().forEach { change ->
                    if (change.recordId !in ownRecordIds) {
                        deleted += DeletedRecord(DeletionTracking.payloadKey(type), change.recordId)
                    }
                }
                if (readFrom != null) {
                    val times = response.changes.filterIsInstance<UpsertionChange>()
                        .map { it.record }
                        .filterNot { it.metadata.id in ownRecordIds || isOwnWrite(it) }
                        .mapNotNull { recordTime(it) }
                    LookbackWindow.outside(times, readFrom)?.let { outside = outside?.plus(it) ?: it }
                }
                token = response.nextChangesToken
                if (!response.hasMore) break
                // Reading advanced the token past the pages already taken, so what is left
                // cannot be read again from the old position. Stopping here silently would
                // hand the receiver a deletion list that looks complete and is not; saying
                // the type is unreconcilable is the honest answer (issue #61).
                if (page == MAX_CHANGES_PAGES) unread = true
            }
            ChangesResult(
                deleted = deleted,
                nextToken = token,
                // Both mean the same thing to a receiver: deletions exist for this type that
                // the app cannot name, so reconcile it against a backfill window instead.
                expired = expired || unread,
                outsideWindow = outside
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A timeout or a stopped worker is not a failed read; it has to unwind, or the
            // caller's loop would go on to the next type inside a cancelled scope.
            throw e
        } catch (e: Exception) {
            // A type whose changes cannot be read must not fail the sync: the records themselves
            // were read successfully, and a missing deletion is a smaller problem than no payload.
            ChangesResult(error = e.message ?: e::class.java.simpleName)
        }
    }

    /** Whether Receive wrote [record], as [ownRecordsPartition] decides it. */
    private fun isOwnWrite(record: Record): Boolean = ResilientReadLogic.isReceiveWrite(
        record.metadata.dataOrigin.packageName,
        context.packageName,
        record.metadata.clientRecordId
    )

    /**
     * The time a read's range is matched on: an interval record's start, an instant record's
     * time. Per class, because the library keeps the two interfaces internal.
     */
    private fun recordTime(record: Record): Instant? = when (record) {
        is StepsRecord -> record.startTime
        is SleepSessionRecord -> record.startTime
        is HeartRateRecord -> record.startTime
        is DistanceRecord -> record.startTime
        is ActiveCaloriesBurnedRecord -> record.startTime
        is TotalCaloriesBurnedRecord -> record.startTime
        is ExerciseSessionRecord -> record.startTime
        is HydrationRecord -> record.startTime
        is NutritionRecord -> record.startTime
        is MindfulnessSessionRecord -> record.startTime
        is MenstruationPeriodRecord -> record.startTime
        is SkinTemperatureRecord -> record.startTime
        is WeightRecord -> record.time
        is HeightRecord -> record.time
        is BloodPressureRecord -> record.time
        is BloodGlucoseRecord -> record.time
        is OxygenSaturationRecord -> record.time
        is BodyTemperatureRecord -> record.time
        is RespiratoryRateRecord -> record.time
        is RestingHeartRateRecord -> record.time
        is BodyFatRecord -> record.time
        is LeanBodyMassRecord -> record.time
        is BoneMassRecord -> record.time
        is BodyWaterMassRecord -> record.time
        is HeartRateVariabilityRmssdRecord -> record.time
        is MenstruationFlowRecord -> record.time
        is BasalMetabolicRateRecord -> record.time
        is Vo2MaxRecord -> record.time
        is BasalBodyTemperatureRecord -> record.time
        is IntermenstrualBleedingRecord -> record.time
        is OvulationTestRecord -> record.time
        is CervicalMucusRecord -> record.time
        is SexualActivityRecord -> record.time
        else -> null
    }

    // ==================== Write side (Receive, issue #62) ====================

    /** The writable types whose WRITE permission is granted right now; the user can revoke one at any time. */
    suspend fun grantedWriteTypes(): Set<WriteBackType> {
        val granted = getGrantedPermissions()
        return WriteBackType.entries.filter { it.writePermission in granted }.toSet()
    }

    /**
     * The record for one validated reading. Throws IllegalArgumentException when Health
     * Connect's own bounds refuse the value (the unit classes and the record constructors
     * check them), which the caller reports as out_of_range for that one reading before
     * anything is inserted.
     */
    fun recordFor(reading: PendingReading): Record {
        val metadata = metadataFor(reading)
        val zone = reading.zoneOffset ?: ZoneId.systemDefault().rules.getOffset(reading.time)
        return when (reading.type) {
            WriteBackType.WEIGHT -> WeightRecord(reading.time, zone, Mass.kilograms(reading.value), metadata)
            WriteBackType.HEIGHT -> HeightRecord(reading.time, zone, Length.meters(reading.value), metadata)
            WriteBackType.BODY_FAT -> BodyFatRecord(reading.time, zone, Percentage(reading.value), metadata)
            WriteBackType.LEAN_BODY_MASS -> LeanBodyMassRecord(reading.time, zone, Mass.kilograms(reading.value), metadata)
            WriteBackType.BONE_MASS -> BoneMassRecord(reading.time, zone, Mass.kilograms(reading.value), metadata)
            WriteBackType.BODY_WATER_MASS -> BodyWaterMassRecord(reading.time, zone, Mass.kilograms(reading.value), metadata)
            WriteBackType.BLOOD_PRESSURE -> BloodPressureRecord(
                time = reading.time,
                zoneOffset = zone,
                metadata = metadata,
                systolic = Pressure.millimetersOfMercury(reading.value),
                diastolic = Pressure.millimetersOfMercury(
                    reading.diastolic ?: throw IllegalArgumentException("blood pressure needs a diastolic value")
                ),
                bodyPosition = bodyPositionFor(reading.bodyPosition),
                measurementLocation = measurementLocationFor(reading.measurementLocation)
            )
        }
    }

    /**
     * Metadata that makes a repeat of the same reading an upsert instead of a duplicate: the
     * integration's id is the clientRecordId and its version the clientRecordVersion, so a
     * resend is ignored and a correction with a higher version overwrites. The device travels
     * along when the reading was recorded by one; a value someone typed into Home Assistant
     * is a manual entry.
     */
    private fun metadataFor(reading: PendingReading): Metadata = when (reading.recordingMethod) {
        RecordingMethod.AUTO -> Metadata.autoRecorded(deviceFor(reading.device), reading.id, reading.version)
        RecordingMethod.ACTIVE -> Metadata.activelyRecorded(deviceFor(reading.device), reading.id, reading.version)
        RecordingMethod.MANUAL -> Metadata.manualEntry(reading.id, reading.version)
    }

    private fun deviceFor(device: ReadingDevice?): Device = Device(
        manufacturer = device?.manufacturer,
        model = device?.model,
        type = when (device?.type) {
            "watch" -> Device.TYPE_WATCH
            "phone" -> Device.TYPE_PHONE
            "scale" -> Device.TYPE_SCALE
            "ring" -> Device.TYPE_RING
            "head_mounted" -> Device.TYPE_HEAD_MOUNTED
            "fitness_band" -> Device.TYPE_FITNESS_BAND
            "chest_strap" -> Device.TYPE_CHEST_STRAP
            "smart_display" -> Device.TYPE_SMART_DISPLAY
            else -> Device.TYPE_UNKNOWN
        }
    )

    private fun bodyPositionFor(key: String): Int = when (key) {
        "standing_up" -> BloodPressureRecord.BODY_POSITION_STANDING_UP
        "sitting_down" -> BloodPressureRecord.BODY_POSITION_SITTING_DOWN
        "lying_down" -> BloodPressureRecord.BODY_POSITION_LYING_DOWN
        "reclining" -> BloodPressureRecord.BODY_POSITION_RECLINING
        else -> BloodPressureRecord.BODY_POSITION_UNKNOWN
    }

    private fun measurementLocationFor(key: String): Int = when (key) {
        "left_wrist" -> BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST
        "right_wrist" -> BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST
        "left_upper_arm" -> BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM
        "right_upper_arm" -> BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM
        else -> BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN
    }

    /**
     * Inserts records in chunks of at most [MAX_RECORDS_PER_INSERT] and returns the metadata
     * ids Health Connect assigned, in the order of [records]. Exceptions are the client's own
     * (SecurityException for a missing permission, RemoteException for a quota or a service
     * that does not answer) and are left to the caller to classify; each chunk is
     * transactional, so a chunk that throws inserted nothing.
     */
    suspend fun insertRecords(records: List<Record>): List<String> {
        if (records.isEmpty()) return emptyList()
        val ids = mutableListOf<String>()
        for (chunk in records.chunked(MAX_RECORDS_PER_INSERT)) {
            ids += healthConnectClient.insertRecords(chunk).recordIdsList
        }
        return ids
    }

    /**
     * Deletes records this app wrote, by their clientRecordId. Health Connect scopes client
     * ids to the writing app, so this can never touch another app's records. An id Health
     * Connect no longer holds makes the whole call throw; the caller treats that as done.
     */
    suspend fun deleteOwnRecords(type: WriteBackType, clientRecordIds: List<String>) {
        if (clientRecordIds.isEmpty()) return
        healthConnectClient.deleteRecords(type.dataType.recordClass, recordIdsList = emptyList(), clientRecordIdsList = clientRecordIds)
    }

    /**
     * The other apps that wrote [type] to Health Connect in the last [days] days, by package
     * name. Shown when a type is switched on under Receive: a scale app that already writes
     * weight itself, plus the same weight from Home Assistant, is two readings a day. Empty
     * when nothing else wrote, or when the read fails: a warning is never worth a failure.
     */
    suspend fun otherSourcesWriting(type: WriteBackType, days: Long = 7): List<String> = try {
        val now = Instant.now()
        val own = context.packageName
        readAllRecords(type.dataType.recordClass, now.minus(Duration.ofDays(days)), now).records
            .map { it.metadata.dataOrigin.packageName }
            .filter { it != own }
            .distinct()
            .sorted()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun requestPermissions(permissions: Set<String>): android.content.Intent {
        if (!isHealthConnectAvailable()) {
            throw IllegalStateException("Health Connect is not available on this device")
        }
        val contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        return contract.createIntent(context, permissions.toTypedArray())
    }

    private suspend fun readMenstruationPeriodData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<MenstruationPeriodData> {
        return readFiltered(HealthDataType.MENSTRUATION_PERIOD, MenstruationPeriodRecord::class, startTime, endTime, lastSync) { it.endTime }
            .map { MenstruationPeriodData(it.startTime, it.endTime, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readMenstruationFlowData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<MenstruationFlowData> {
        return readFiltered(HealthDataType.MENSTRUATION_FLOW, MenstruationFlowRecord::class, startTime, endTime, lastSync) { it.time }
            .map { MenstruationFlowData(menstruationFlowToString(it.flow), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private fun menstruationFlowToString(flow: Int): String = when (flow) {
        MenstruationFlowRecord.FLOW_LIGHT -> "light"
        MenstruationFlowRecord.FLOW_MEDIUM -> "medium"
        MenstruationFlowRecord.FLOW_HEAVY -> "heavy"
        else -> "unknown"
    }

    private suspend fun readBasalMetabolicRateData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BasalMetabolicRateData> {
        return readFiltered(HealthDataType.BASAL_METABOLIC_RATE, BasalMetabolicRateRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BasalMetabolicRateData(it.basalMetabolicRate.inKilocaloriesPerDay, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readVo2MaxData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<Vo2MaxData> {
        return readFiltered(HealthDataType.VO2_MAX, Vo2MaxRecord::class, startTime, endTime, lastSync) { it.time }
            .map { Vo2MaxData(it.vo2MillilitersPerMinuteKilogram, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    /**
     * Skin temperature records hold many delta samples (e.g. one per few minutes overnight), so
     * like heart rate this filters and caps at RECORD granularity on modification time; samples
     * inherit the parent record's baseline and data origin.
     */
    private suspend fun readSkinTemperatureData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<SkinTemperatureData> {
        try {
            val paged = readAllRecordsResilient(SkinTemperatureRecord::class, startTime, endTime)
            val rawSamples = paged.records.sumOf { it.deltas.size }
            val (own, newRecords) = ownRecordsPartition(
                paged.records.filter { lastSync == null || lastSync.admits(it.metadata.lastModifiedTime, it.metadata.id) }
            )
            val includedRecords = ResilientReadLogic.capRecordsBySamples(
                newRecords,
                HealthDataType.SKIN_TEMPERATURE.maxRecordsPerSync,
                samplesOf = { it.deltas.size },
                timeOf = { it.metadata.lastModifiedTime },
                idOf = { it.metadata.id }
            )
            val capped = includedRecords.size < newRecords.size
            if (capped) cappedTypes += HealthDataType.SKIN_TEMPERATURE
            watermarkFor(includedRecords, own, capped)?.let { watermarks[HealthDataType.SKIN_TEMPERATURE] = it }
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
                error = skippedWindowsNote(paged.skippedWindows),
                ownRecordsSkipped = own.size
            )
            return limited.map { s ->
                SkinTemperatureData(s.delta.delta.inCelsius, s.baseline?.inCelsius, s.delta.time, s.source, s.uuid)
            }
        } catch (e: Exception) {
            recordDiag(type = HealthDataType.SKIN_TEMPERATURE, error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private suspend fun readBasalBodyTemperatureData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<BasalBodyTemperatureData> {
        return readFiltered(HealthDataType.BASAL_BODY_TEMPERATURE, BasalBodyTemperatureRecord::class, startTime, endTime, lastSync) { it.time }
            .map { BasalBodyTemperatureData(it.temperature.inCelsius, it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readIntermenstrualBleedingData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<IntermenstrualBleedingData> {
        return readFiltered(HealthDataType.INTERMENSTRUAL_BLEEDING, IntermenstrualBleedingRecord::class, startTime, endTime, lastSync) { it.time }
            .map { IntermenstrualBleedingData(it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readOvulationTestData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<OvulationTestData> {
        return readFiltered(HealthDataType.OVULATION_TEST, OvulationTestRecord::class, startTime, endTime, lastSync) { it.time }
            .map { OvulationTestData(ovulationTestResultToString(it.result), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readCervicalMucusData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<CervicalMucusData> {
        return readFiltered(HealthDataType.CERVICAL_MUCUS, CervicalMucusRecord::class, startTime, endTime, lastSync) { it.time }
            .map { CervicalMucusData(cervicalMucusAppearanceToString(it.appearance), cervicalMucusSensationToString(it.sensation), it.time, it.metadata.dataOrigin.packageName, it.metadata.id) }
    }

    private suspend fun readSexualActivityData(startTime: Instant, endTime: Instant, lastSync: Watermark?): List<SexualActivityData> {
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
        /** How long one Health Connect call may take before the read gives it up; see [bounded]. */
        const val CALL_TIMEOUT_MS = 10_000L

        /**
         * The whole read step of one pass. Generous, a backstop rather than a ration: types
         * that have not started when it runs out are skipped this sync and read the next.
         */
        const val READ_BUDGET_MS = 120_000L

        /**
         * Pages of one type's changes feed read per sync. Generous: a page holds many changes,
         * and a month of deletions for one type fits well inside this. It exists to bound the
         * loop, not to ration it.
         */
        private const val MAX_CHANGES_PAGES = 100

        /** Health Connect's documented limit for one insertRecords call. */
        const val MAX_RECORDS_PER_INSERT = 1000
        private fun skippedWindowsNote(skippedWindows: Int): String? =
            if (skippedWindows > 0) {
                "Skipped $skippedWindows unreadable window(s) of max " +
                    "${ResilientReadLogic.MIN_BISECT_WINDOW.toMinutes()} min " +
                    "containing malformed records from the source app"
            } else {
                null
            }

        const val BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        /**
         * Lets reads reach further back than the default 30 days before the first permission
         * grant; without it a 90 or 365 day backfill silently returns only recent data (#39).
         */
        const val HISTORY_PERMISSION = "android.permission.health.READ_HEALTH_DATA_HISTORY"

        // Derived from the enum so newly added data types can never be missing from the
        // permission request (a hand-maintained list had drifted to 23 of 33 types).
        val ALL_PERMISSIONS: Set<String> =
            HealthDataType.entries.map { HealthPermission.getReadPermission(it.recordClass) }.toSet() +
                setOf(BACKGROUND_PERMISSION, HISTORY_PERMISSION)
    }
}

/** A Health Connect call that did not answer in time; see HealthConnectManager.bounded. */
class HealthConnectTimeoutException(what: String, timeoutMs: Long) :
    java.io.IOException("Health Connect did not return $what within ${timeoutMs / 1000} s")
