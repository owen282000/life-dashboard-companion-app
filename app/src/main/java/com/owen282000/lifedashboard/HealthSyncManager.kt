package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

class HealthSyncManager(private val context: Context) {

    private val preferencesManager = PreferencesManager(context)
    private val healthConnectManager = HealthConnectManager(context)

    companion object {
        private const val CHUNK_SIZE = 500  // Records per chunk to keep payloads ~1-2MB
    }

    suspend fun previewData(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
            if (enabledTypes.isEmpty()) {
                return@withContext Result.failure(Exception("No data types enabled"))
            }

            val lastSyncTimestamps = enabledTypes.associateWith { type ->
                preferencesManager.getHealthLastSyncTimestamp(type)?.let { Instant.ofEpochMilli(it) }
            }

            val healthDataResult = healthConnectManager.readHealthData(enabledTypes, lastSyncTimestamps)
            if (healthDataResult.isFailure) {
                return@withContext Result.failure(healthDataResult.exceptionOrNull() ?: Exception("Failed to read health data"))
            }

            val healthData = healthDataResult.getOrThrow()
            if (isHealthDataEmpty(healthData)) {
                return@withContext Result.failure(Exception("No new data to preview"))
            }

            val json = Json { prettyPrint = true }
            val payload = buildJsonPayload(healthData, 0, 1)
            val prettyPayload = json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                Json.parseToJsonElement(payload)
            )
            Result.success(prettyPayload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun performSync(): Result<HealthSyncResult> = withContext(Dispatchers.IO) {
        try {
            val webhookUrls = preferencesManager.getHealthWebhookUrls()

            if (webhookUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No webhook URLs configured"))
            }

            val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
            if (enabledTypes.isEmpty()) {
                return@withContext Result.failure(Exception("No data types enabled"))
            }

            // Get last sync timestamps for all enabled types
            val lastSyncTimestamps = enabledTypes.associateWith { type ->
                preferencesManager.getHealthLastSyncTimestamp(type)?.let { Instant.ofEpochMilli(it) }
            }

            // Read health data
            val healthDataResult = healthConnectManager.readHealthData(enabledTypes, lastSyncTimestamps)
            if (healthDataResult.isFailure) {
                return@withContext Result.failure(healthDataResult.exceptionOrNull() ?: Exception("Failed to read health data"))
            }

            val healthData = healthDataResult.getOrThrow()

            // Check if there's any new data
            if (isHealthDataEmpty(healthData)) {
                return@withContext Result.success(HealthSyncResult.NoData)
            }

            // Calculate total record count
            val totalRecords = healthData.steps.size + healthData.sleep.size + healthData.heartRate.size +
                    healthData.distance.size + healthData.activeCalories.size + healthData.totalCalories.size +
                    healthData.weight.size + healthData.height.size + healthData.bloodPressure.size +
                    healthData.bloodGlucose.size + healthData.oxygenSaturation.size + healthData.bodyTemperature.size +
                    healthData.respiratoryRate.size + healthData.restingHeartRate.size + healthData.exercise.size +
                    healthData.hydration.size + healthData.nutrition.size + healthData.mindfulness.size +
                    healthData.bodyFat.size + healthData.leanBodyMass.size + healthData.boneMass.size +
                    healthData.bodyWaterMass.size + healthData.hrv.size

            val webhookManager = WebhookManager(
                webhookUrls = webhookUrls,
                context = context,
                dataType = "health_connect",
                recordCount = totalRecords,
                logType = LogType.HEALTH_CONNECT,
                customHeaders = preferencesManager.getHealthWebhookHeaders()
            )

            // Chunk data into smaller payloads to avoid OOM/timeout
            val chunks = chunkHealthData(healthData)

            for ((index, chunk) in chunks.withIndex()) {
                val jsonPayload = buildJsonPayload(chunk, index, chunks.size)
                val postResult = webhookManager.postData(jsonPayload)
                if (postResult.isFailure) {
                    return@withContext Result.failure(postResult.exceptionOrNull() ?: Exception("Failed to post chunk ${index + 1} to webhooks"))
                }
            }

            // Update last sync timestamps
            val syncCounts = mutableMapOf<HealthDataType, Int>()
            updateSyncTimestamps(healthData, syncCounts)

            Result.success(HealthSyncResult.Success(syncCounts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isHealthDataEmpty(data: HealthData): Boolean {
        return data.steps.isEmpty() && data.sleep.isEmpty() && data.heartRate.isEmpty() &&
                data.distance.isEmpty() && data.activeCalories.isEmpty() && data.totalCalories.isEmpty() &&
                data.weight.isEmpty() && data.height.isEmpty() && data.bloodPressure.isEmpty() &&
                data.bloodGlucose.isEmpty() && data.oxygenSaturation.isEmpty() && data.bodyTemperature.isEmpty() &&
                data.respiratoryRate.isEmpty() && data.restingHeartRate.isEmpty() && data.exercise.isEmpty() &&
                data.hydration.isEmpty() && data.nutrition.isEmpty() && data.mindfulness.isEmpty() &&
                data.bodyFat.isEmpty() && data.leanBodyMass.isEmpty() && data.boneMass.isEmpty() &&
                data.bodyWaterMass.isEmpty() && data.hrv.isEmpty()
    }

    private fun updateSyncTimestamps(data: HealthData, syncCounts: MutableMap<HealthDataType, Int>) {
        if (data.steps.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.STEPS, data.steps.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.STEPS] = data.steps.size
        }
        if (data.sleep.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.SLEEP, data.sleep.maxOf { it.sessionEndTime }.toEpochMilli())
            syncCounts[HealthDataType.SLEEP] = data.sleep.size
        }
        if (data.heartRate.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.HEART_RATE, data.heartRate.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.HEART_RATE] = data.heartRate.size
        }
        if (data.distance.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.DISTANCE, data.distance.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.DISTANCE] = data.distance.size
        }
        if (data.activeCalories.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.ACTIVE_CALORIES, data.activeCalories.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.ACTIVE_CALORIES] = data.activeCalories.size
        }
        if (data.totalCalories.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.TOTAL_CALORIES, data.totalCalories.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.TOTAL_CALORIES] = data.totalCalories.size
        }
        if (data.weight.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.WEIGHT, data.weight.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.WEIGHT] = data.weight.size
        }
        if (data.height.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.HEIGHT, data.height.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.HEIGHT] = data.height.size
        }
        if (data.bloodPressure.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.BLOOD_PRESSURE, data.bloodPressure.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.BLOOD_PRESSURE] = data.bloodPressure.size
        }
        if (data.bloodGlucose.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.BLOOD_GLUCOSE, data.bloodGlucose.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.BLOOD_GLUCOSE] = data.bloodGlucose.size
        }
        if (data.oxygenSaturation.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.OXYGEN_SATURATION, data.oxygenSaturation.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.OXYGEN_SATURATION] = data.oxygenSaturation.size
        }
        if (data.bodyTemperature.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.BODY_TEMPERATURE, data.bodyTemperature.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.BODY_TEMPERATURE] = data.bodyTemperature.size
        }
        if (data.respiratoryRate.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.RESPIRATORY_RATE, data.respiratoryRate.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.RESPIRATORY_RATE] = data.respiratoryRate.size
        }
        if (data.restingHeartRate.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.RESTING_HEART_RATE, data.restingHeartRate.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.RESTING_HEART_RATE] = data.restingHeartRate.size
        }
        if (data.exercise.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.EXERCISE, data.exercise.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.EXERCISE] = data.exercise.size
        }
        if (data.hydration.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.HYDRATION, data.hydration.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.HYDRATION] = data.hydration.size
        }
        if (data.nutrition.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.NUTRITION, data.nutrition.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.NUTRITION] = data.nutrition.size
        }
        if (data.mindfulness.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.MINDFULNESS, data.mindfulness.maxOf { it.endTime }.toEpochMilli())
            syncCounts[HealthDataType.MINDFULNESS] = data.mindfulness.size
        }
        if (data.bodyFat.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.BODY_FAT, data.bodyFat.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.BODY_FAT] = data.bodyFat.size
        }
        if (data.leanBodyMass.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.LEAN_BODY_MASS, data.leanBodyMass.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.LEAN_BODY_MASS] = data.leanBodyMass.size
        }
        if (data.boneMass.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.BONE_MASS, data.boneMass.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.BONE_MASS] = data.boneMass.size
        }
        if (data.bodyWaterMass.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.BODY_WATER_MASS, data.bodyWaterMass.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.BODY_WATER_MASS] = data.bodyWaterMass.size
        }
        if (data.hrv.isNotEmpty()) {
            preferencesManager.setHealthLastSyncTimestamp(HealthDataType.HEART_RATE_VARIABILITY, data.hrv.maxOf { it.time }.toEpochMilli())
            syncCounts[HealthDataType.HEART_RATE_VARIABILITY] = data.hrv.size
        }
    }

    private fun chunkHealthData(data: HealthData): List<HealthData> {
        val totalRecords = data.steps.size + data.sleep.size + data.heartRate.size +
                data.distance.size + data.activeCalories.size + data.totalCalories.size +
                data.weight.size + data.height.size + data.bloodPressure.size +
                data.bloodGlucose.size + data.oxygenSaturation.size + data.bodyTemperature.size +
                data.respiratoryRate.size + data.restingHeartRate.size + data.exercise.size +
                data.hydration.size + data.nutrition.size + data.mindfulness.size +
                data.bodyFat.size + data.leanBodyMass.size + data.boneMass.size +
                data.bodyWaterMass.size + data.hrv.size

        if (totalRecords <= CHUNK_SIZE) {
            return listOf(data)
        }

        val stepsPerType = CHUNK_SIZE / 23
        val chunks = mutableListOf<HealthData>()

        var si = 0
        var sli = 0
        var hi = 0
        var di = 0
        var aci = 0
        var tci = 0
        var wi = 0
        var hei = 0
        var bpi = 0
        var bgi = 0
        var osi = 0
        var bti = 0
        var rri = 0
        var rhi = 0
        var ei = 0
        var hyi = 0
        var ni = 0
        var mi = 0
        var bfi = 0
        var lbmi = 0
        var bmi = 0
        var bwmi = 0
        var hrvi = 0

        while (si < data.steps.size || sli < data.sleep.size || hi < data.heartRate.size ||
                di < data.distance.size || aci < data.activeCalories.size || tci < data.totalCalories.size ||
                wi < data.weight.size || hei < data.height.size || bpi < data.bloodPressure.size ||
                bgi < data.bloodGlucose.size || osi < data.oxygenSaturation.size || bti < data.bodyTemperature.size ||
                rri < data.respiratoryRate.size || rhi < data.restingHeartRate.size || ei < data.exercise.size ||
                hyi < data.hydration.size || ni < data.nutrition.size || mi < data.mindfulness.size ||
                bfi < data.bodyFat.size || lbmi < data.leanBodyMass.size || bmi < data.boneMass.size ||
                bwmi < data.bodyWaterMass.size || hrvi < data.hrv.size) {

            val chunkSteps = data.steps.drop(si).take(stepsPerType)
            val chunkSleep = data.sleep.drop(sli).take(stepsPerType)
            val chunkHR = data.heartRate.drop(hi).take(stepsPerType)
            val chunkDistance = data.distance.drop(di).take(stepsPerType)
            val chunkActiveCalories = data.activeCalories.drop(aci).take(stepsPerType)
            val chunkTotalCalories = data.totalCalories.drop(tci).take(stepsPerType)
            val chunkWeight = data.weight.drop(wi).take(stepsPerType)
            val chunkHeight = data.height.drop(hei).take(stepsPerType)
            val chunkBP = data.bloodPressure.drop(bpi).take(stepsPerType)
            val chunkBG = data.bloodGlucose.drop(bgi).take(stepsPerType)
            val chunkOS = data.oxygenSaturation.drop(osi).take(stepsPerType)
            val chunkBT = data.bodyTemperature.drop(bti).take(stepsPerType)
            val chunkRR = data.respiratoryRate.drop(rri).take(stepsPerType)
            val chunkRHR = data.restingHeartRate.drop(rhi).take(stepsPerType)
            val chunkExercise = data.exercise.drop(ei).take(stepsPerType)
            val chunkHydration = data.hydration.drop(hyi).take(stepsPerType)
            val chunkNutrition = data.nutrition.drop(ni).take(stepsPerType)
            val chunkMindfulness = data.mindfulness.drop(mi).take(stepsPerType)
            val chunkBF = data.bodyFat.drop(bfi).take(stepsPerType)
            val chunkLBM = data.leanBodyMass.drop(lbmi).take(stepsPerType)
            val chunkBM = data.boneMass.drop(bmi).take(stepsPerType)
            val chunkBWM = data.bodyWaterMass.drop(bwmi).take(stepsPerType)
            val chunkHRV = data.hrv.drop(hrvi).take(stepsPerType)

            chunks.add(HealthData(
                chunkSteps, chunkSleep, chunkHR, chunkDistance, chunkActiveCalories, chunkTotalCalories,
                chunkWeight, chunkHeight, chunkBP, chunkBG, chunkOS, chunkBT, chunkRR, chunkRHR,
                chunkExercise, chunkHydration, chunkNutrition, chunkMindfulness, chunkBF, chunkLBM,
                chunkBM, chunkBWM, chunkHRV, data.diagnostics
            ))

            si += stepsPerType
            sli += stepsPerType
            hi += stepsPerType
            di += stepsPerType
            aci += stepsPerType
            tci += stepsPerType
            wi += stepsPerType
            hei += stepsPerType
            bpi += stepsPerType
            bgi += stepsPerType
            osi += stepsPerType
            bti += stepsPerType
            rri += stepsPerType
            rhi += stepsPerType
            ei += stepsPerType
            hyi += stepsPerType
            ni += stepsPerType
            mi += stepsPerType
            bfi += stepsPerType
            lbmi += stepsPerType
            bmi += stepsPerType
            bwmi += stepsPerType
            hrvi += stepsPerType
        }

        return chunks
    }

    private fun buildJsonPayload(healthData: HealthData, chunkIndex: Int = 0, totalChunks: Int = 1): String {
        val json = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("app_version", getAppVersion())
            put("source", "health_connect")
            if (totalChunks > 1) {
                put("chunk", chunkIndex + 1)
                put("total_chunks", totalChunks)
            }

            if (healthData.steps.isNotEmpty()) {
                putJsonArray("steps") {
                    healthData.steps.forEach { step ->
                        add(buildJsonObject {
                            put("count", step.count)
                            put("start_time", step.startTime.toString())
                            put("end_time", step.endTime.toString())
                        })
                    }
                }
            }

            if (healthData.sleep.isNotEmpty()) {
                putJsonArray("sleep") {
                    healthData.sleep.forEach { sleep ->
                        add(buildJsonObject {
                            put("session_end_time", sleep.sessionEndTime.toString())
                            put("duration_seconds", sleep.duration.seconds)
                            putJsonArray("stages") {
                                sleep.stages.forEach { stage ->
                                    add(buildJsonObject {
                                        put("stage", stage.stage)
                                        put("start_time", stage.startTime.toString())
                                        put("end_time", stage.endTime.toString())
                                        put("duration_seconds", stage.duration.seconds)
                                    })
                                }
                            }
                        })
                    }
                }
            }

            if (healthData.heartRate.isNotEmpty()) {
                putJsonArray("heart_rate") {
                    healthData.heartRate.forEach { add(buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.distance.isNotEmpty()) {
                putJsonArray("distance") {
                    healthData.distance.forEach { add(buildJsonObject {
                        put("meters", it.meters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }) }
                }
            }

            if (healthData.activeCalories.isNotEmpty()) {
                putJsonArray("active_calories") {
                    healthData.activeCalories.forEach { add(buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }) }
                }
            }

            if (healthData.totalCalories.isNotEmpty()) {
                putJsonArray("total_calories") {
                    healthData.totalCalories.forEach { add(buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }) }
                }
            }

            if (healthData.weight.isNotEmpty()) {
                putJsonArray("weight") {
                    healthData.weight.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.height.isNotEmpty()) {
                putJsonArray("height") {
                    healthData.height.forEach { add(buildJsonObject {
                        put("meters", it.meters)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.bloodPressure.isNotEmpty()) {
                putJsonArray("blood_pressure") {
                    healthData.bloodPressure.forEach { add(buildJsonObject {
                        put("systolic", it.systolic)
                        put("diastolic", it.diastolic)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.bloodGlucose.isNotEmpty()) {
                putJsonArray("blood_glucose") {
                    healthData.bloodGlucose.forEach { add(buildJsonObject {
                        put("mmol_per_liter", it.mmolPerLiter)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.oxygenSaturation.isNotEmpty()) {
                putJsonArray("oxygen_saturation") {
                    healthData.oxygenSaturation.forEach { add(buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.bodyTemperature.isNotEmpty()) {
                putJsonArray("body_temperature") {
                    healthData.bodyTemperature.forEach { add(buildJsonObject {
                        put("celsius", it.celsius)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.respiratoryRate.isNotEmpty()) {
                putJsonArray("respiratory_rate") {
                    healthData.respiratoryRate.forEach { add(buildJsonObject {
                        put("rate", it.rate)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.restingHeartRate.isNotEmpty()) {
                putJsonArray("resting_heart_rate") {
                    healthData.restingHeartRate.forEach { add(buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.exercise.isNotEmpty()) {
                putJsonArray("exercise") {
                    healthData.exercise.forEach { add(buildJsonObject {
                        put("type", it.type)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        put("duration_seconds", it.duration.seconds)
                    }) }
                }
            }

            if (healthData.hydration.isNotEmpty()) {
                putJsonArray("hydration") {
                    healthData.hydration.forEach { add(buildJsonObject {
                        put("liters", it.liters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }) }
                }
            }

            if (healthData.nutrition.isNotEmpty()) {
                putJsonArray("nutrition") {
                    healthData.nutrition.forEach { add(buildJsonObject {
                        it.calories?.let { cal -> put("calories", cal) }
                        it.protein?.let { prot -> put("protein_grams", prot) }
                        it.carbs?.let { carb -> put("carbs_grams", carb) }
                        it.fat?.let { f -> put("fat_grams", f) }
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                    }) }
                }
            }

            if (healthData.mindfulness.isNotEmpty()) {
                putJsonArray("mindfulness") {
                    healthData.mindfulness.forEach { add(buildJsonObject {
                        it.title?.let { t -> put("title", t) }
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        put("duration_seconds", it.duration.seconds)
                    }) }
                }
            }

            if (healthData.bodyFat.isNotEmpty()) {
                putJsonArray("body_fat") {
                    healthData.bodyFat.forEach { add(buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.leanBodyMass.isNotEmpty()) {
                putJsonArray("lean_body_mass") {
                    healthData.leanBodyMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.boneMass.isNotEmpty()) {
                putJsonArray("bone_mass") {
                    healthData.boneMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.bodyWaterMass.isNotEmpty()) {
                putJsonArray("body_water_mass") {
                    healthData.bodyWaterMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                    }) }
                }
            }

            if (healthData.hrv.isNotEmpty()) {
                putJsonArray("heart_rate_variability") {
                    healthData.hrv.forEach { add(buildJsonObject {
                        put("heart_rate_variability_millis", it.heartRateVariabilityMillis)
                        put("time", it.time.toString())
                    }) }
                }
            }

            // Per-data-type read diagnostics, so the receiving server can see exactly what
            // Health Connect returned for each type (permission, pages read, record counts,
            // min/max timestamps, lastSync, errors). Helps diagnose stale/missing data.
            if (healthData.diagnostics.isNotEmpty()) {
                putJsonObject("_diagnostics") {
                    healthData.diagnostics.forEach { (type, diag) ->
                        putJsonObject(type.name.lowercase()) {
                            put("permission_granted", diag.permissionGranted)
                            put("page_count", diag.pageCount)
                            put("raw_record_count", diag.rawRecordCount)
                            put("filtered_record_count", diag.filteredRecordCount)
                            put("min_time", diag.minTime?.toString())
                            put("max_time", diag.maxTime?.toString())
                            put("last_sync", diag.lastSync?.toString())
                            put("error", diag.error)
                        }
                    }
                }
            }
        }

        return json.toString()
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
