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
            val payload = buildJsonPayload(healthData)
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
            // Deliver any queued payloads from earlier failed syncs first, preserving order.
            PendingDrainer.drain(context)

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

            // Publish latest values to the user's MQTT broker (Home Assistant Discovery) when
            // configured. Failures never block the webhook sync; the outcome is stored and
            // shown in the MQTT settings section.
            MqttPublisher(context).publishHealthData(healthData)

            // Calculate total record count
            val totalRecords = healthData.steps.size + healthData.sleep.size + healthData.heartRate.size +
                    healthData.distance.size + healthData.activeCalories.size + healthData.totalCalories.size +
                    healthData.weight.size + healthData.height.size + healthData.bloodPressure.size +
                    healthData.bloodGlucose.size + healthData.oxygenSaturation.size + healthData.bodyTemperature.size +
                    healthData.respiratoryRate.size + healthData.restingHeartRate.size + healthData.exercise.size +
                    healthData.hydration.size + healthData.nutrition.size + healthData.mindfulness.size +
                    healthData.bodyFat.size + healthData.leanBodyMass.size + healthData.boneMass.size +
                    healthData.bodyWaterMass.size + healthData.hrv.size +
                    healthData.menstruationPeriod.size + healthData.menstruationFlow.size +
                    healthData.basalMetabolicRate.size + healthData.vo2Max.size +
                    healthData.skinTemperature.size + healthData.basalBodyTemperature.size +
                    healthData.intermenstrualBleeding.size + healthData.ovulationTest.size +
                    healthData.cervicalMucus.size + healthData.sexualActivity.size

            val webhookManager = WebhookManager(
                webhookUrls = webhookUrls,
                context = context,
                dataType = "health_connect",
                recordCount = totalRecords,
                logType = LogType.HEALTH_CONNECT,
                customHeaders = preferencesManager.getHealthWebhookHeaders(),
                signingSecret = preferencesManager.getHealthWebhookSecret()
            )

            // Build JSON payload
            val jsonPayload = buildJsonPayload(healthData)

            // Post to webhook
            val postResult = webhookManager.postData(jsonPayload)
            SyncFailureNotifier.recordResult(context, LogType.HEALTH_CONNECT, postResult.isSuccess)
            SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) totalRecords else 0)

            // Watermarks advance regardless of delivery outcome: a failed payload goes to the
            // outbox and is guaranteed to be delivered by a later drain, so re-reading (and
            // potentially double-sending) the same records is unnecessary.
            val syncCounts = mutableMapOf<HealthDataType, Int>()
            updateSyncTimestamps(healthData, syncCounts)

            if (postResult.isFailure) {
                PendingSyncStore.forContext(context).enqueue(
                    payload = jsonPayload,
                    dataType = "health_connect",
                    logType = LogType.HEALTH_CONNECT.name,
                    recordCount = totalRecords,
                    nowMillis = System.currentTimeMillis()
                )
                return@withContext Result.success(HealthSyncResult.Queued(totalRecords))
            }

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
                data.bodyWaterMass.isEmpty() && data.hrv.isEmpty() &&
                data.menstruationPeriod.isEmpty() && data.menstruationFlow.isEmpty() &&
                data.basalMetabolicRate.isEmpty() && data.vo2Max.isEmpty() &&
                data.skinTemperature.isEmpty() && data.basalBodyTemperature.isEmpty() &&
                data.intermenstrualBleeding.isEmpty() && data.ovulationTest.isEmpty() &&
                data.cervicalMucus.isEmpty() && data.sexualActivity.isEmpty()
    }

    private fun updateSyncTimestamps(data: HealthData, syncCounts: MutableMap<HealthDataType, Int>) {
        // Watermarks are the max metadata.lastModifiedTime of each delivered batch, so late
        // backfills and edits (old record timestamps, recent modification) are caught by the
        // next sync instead of being skipped forever.
        data.watermarks.forEach { (type, watermark) ->
            preferencesManager.setHealthLastSyncTimestamp(type, watermark.toEpochMilli())
        }

        if (data.steps.isNotEmpty()) {
            syncCounts[HealthDataType.STEPS] = data.steps.size
        }
        if (data.sleep.isNotEmpty()) {
            syncCounts[HealthDataType.SLEEP] = data.sleep.size
        }
        if (data.heartRate.isNotEmpty()) {
            syncCounts[HealthDataType.HEART_RATE] = data.heartRate.size
        }
        if (data.distance.isNotEmpty()) {
            syncCounts[HealthDataType.DISTANCE] = data.distance.size
        }
        if (data.activeCalories.isNotEmpty()) {
            syncCounts[HealthDataType.ACTIVE_CALORIES] = data.activeCalories.size
        }
        if (data.totalCalories.isNotEmpty()) {
            syncCounts[HealthDataType.TOTAL_CALORIES] = data.totalCalories.size
        }
        if (data.weight.isNotEmpty()) {
            syncCounts[HealthDataType.WEIGHT] = data.weight.size
        }
        if (data.height.isNotEmpty()) {
            syncCounts[HealthDataType.HEIGHT] = data.height.size
        }
        if (data.bloodPressure.isNotEmpty()) {
            syncCounts[HealthDataType.BLOOD_PRESSURE] = data.bloodPressure.size
        }
        if (data.bloodGlucose.isNotEmpty()) {
            syncCounts[HealthDataType.BLOOD_GLUCOSE] = data.bloodGlucose.size
        }
        if (data.oxygenSaturation.isNotEmpty()) {
            syncCounts[HealthDataType.OXYGEN_SATURATION] = data.oxygenSaturation.size
        }
        if (data.bodyTemperature.isNotEmpty()) {
            syncCounts[HealthDataType.BODY_TEMPERATURE] = data.bodyTemperature.size
        }
        if (data.respiratoryRate.isNotEmpty()) {
            syncCounts[HealthDataType.RESPIRATORY_RATE] = data.respiratoryRate.size
        }
        if (data.restingHeartRate.isNotEmpty()) {
            syncCounts[HealthDataType.RESTING_HEART_RATE] = data.restingHeartRate.size
        }
        if (data.exercise.isNotEmpty()) {
            syncCounts[HealthDataType.EXERCISE] = data.exercise.size
        }
        if (data.hydration.isNotEmpty()) {
            syncCounts[HealthDataType.HYDRATION] = data.hydration.size
        }
        if (data.nutrition.isNotEmpty()) {
            syncCounts[HealthDataType.NUTRITION] = data.nutrition.size
        }
        if (data.mindfulness.isNotEmpty()) {
            syncCounts[HealthDataType.MINDFULNESS] = data.mindfulness.size
        }
        if (data.bodyFat.isNotEmpty()) {
            syncCounts[HealthDataType.BODY_FAT] = data.bodyFat.size
        }
        if (data.leanBodyMass.isNotEmpty()) {
            syncCounts[HealthDataType.LEAN_BODY_MASS] = data.leanBodyMass.size
        }
        if (data.boneMass.isNotEmpty()) {
            syncCounts[HealthDataType.BONE_MASS] = data.boneMass.size
        }
        if (data.bodyWaterMass.isNotEmpty()) {
            syncCounts[HealthDataType.BODY_WATER_MASS] = data.bodyWaterMass.size
        }
        if (data.hrv.isNotEmpty()) {
            syncCounts[HealthDataType.HEART_RATE_VARIABILITY] = data.hrv.size
        }
        if (data.menstruationPeriod.isNotEmpty()) {
            syncCounts[HealthDataType.MENSTRUATION_PERIOD] = data.menstruationPeriod.size
        }
        if (data.menstruationFlow.isNotEmpty()) {
            syncCounts[HealthDataType.MENSTRUATION_FLOW] = data.menstruationFlow.size
        }
        if (data.basalMetabolicRate.isNotEmpty()) {
            syncCounts[HealthDataType.BASAL_METABOLIC_RATE] = data.basalMetabolicRate.size
        }
        if (data.vo2Max.isNotEmpty()) {
            syncCounts[HealthDataType.VO2_MAX] = data.vo2Max.size
        }
        if (data.skinTemperature.isNotEmpty()) {
            syncCounts[HealthDataType.SKIN_TEMPERATURE] = data.skinTemperature.size
        }
        if (data.basalBodyTemperature.isNotEmpty()) {
            syncCounts[HealthDataType.BASAL_BODY_TEMPERATURE] = data.basalBodyTemperature.size
        }
        if (data.intermenstrualBleeding.isNotEmpty()) {
            syncCounts[HealthDataType.INTERMENSTRUAL_BLEEDING] = data.intermenstrualBleeding.size
        }
        if (data.ovulationTest.isNotEmpty()) {
            syncCounts[HealthDataType.OVULATION_TEST] = data.ovulationTest.size
        }
        if (data.cervicalMucus.isNotEmpty()) {
            syncCounts[HealthDataType.CERVICAL_MUCUS] = data.cervicalMucus.size
        }
        if (data.sexualActivity.isNotEmpty()) {
            syncCounts[HealthDataType.SEXUAL_ACTIVITY] = data.sexualActivity.size
        }
    }

    private fun buildJsonPayload(healthData: HealthData): String {
        val json = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("app_version", getAppVersion())
            put("source", "health_connect")

            if (healthData.steps.isNotEmpty()) {
                putJsonArray("steps") {
                    healthData.steps.forEach { step ->
                        add(buildJsonObject {
                            put("count", step.count)
                            put("start_time", step.startTime.toString())
                            put("end_time", step.endTime.toString())
                            step.uuid?.let { u -> put("uuid", u) }
                            step.source?.let { s -> put("source", s) }
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
                            sleep.uuid?.let { u -> put("uuid", u) }
                            sleep.source?.let { s -> put("source", s) }
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
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.distance.isNotEmpty()) {
                putJsonArray("distance") {
                    healthData.distance.forEach { add(buildJsonObject {
                        put("meters", it.meters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.activeCalories.isNotEmpty()) {
                putJsonArray("active_calories") {
                    healthData.activeCalories.forEach { add(buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.totalCalories.isNotEmpty()) {
                putJsonArray("total_calories") {
                    healthData.totalCalories.forEach { add(buildJsonObject {
                        put("calories", it.calories)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.weight.isNotEmpty()) {
                putJsonArray("weight") {
                    healthData.weight.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.height.isNotEmpty()) {
                putJsonArray("height") {
                    healthData.height.forEach { add(buildJsonObject {
                        put("meters", it.meters)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.bloodPressure.isNotEmpty()) {
                putJsonArray("blood_pressure") {
                    healthData.bloodPressure.forEach { add(buildJsonObject {
                        put("systolic", it.systolic)
                        put("diastolic", it.diastolic)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.bloodGlucose.isNotEmpty()) {
                putJsonArray("blood_glucose") {
                    healthData.bloodGlucose.forEach { add(buildJsonObject {
                        put("mmol_per_liter", it.mmolPerLiter)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.oxygenSaturation.isNotEmpty()) {
                putJsonArray("oxygen_saturation") {
                    healthData.oxygenSaturation.forEach { add(buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.bodyTemperature.isNotEmpty()) {
                putJsonArray("body_temperature") {
                    healthData.bodyTemperature.forEach { add(buildJsonObject {
                        put("celsius", it.celsius)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.respiratoryRate.isNotEmpty()) {
                putJsonArray("respiratory_rate") {
                    healthData.respiratoryRate.forEach { add(buildJsonObject {
                        put("rate", it.rate)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.restingHeartRate.isNotEmpty()) {
                putJsonArray("resting_heart_rate") {
                    healthData.restingHeartRate.forEach { add(buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
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
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.hydration.isNotEmpty()) {
                putJsonArray("hydration") {
                    healthData.hydration.forEach { add(buildJsonObject {
                        put("liters", it.liters)
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
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
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
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
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.bodyFat.isNotEmpty()) {
                putJsonArray("body_fat") {
                    healthData.bodyFat.forEach { add(buildJsonObject {
                        put("percentage", it.percentage)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.leanBodyMass.isNotEmpty()) {
                putJsonArray("lean_body_mass") {
                    healthData.leanBodyMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.boneMass.isNotEmpty()) {
                putJsonArray("bone_mass") {
                    healthData.boneMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.bodyWaterMass.isNotEmpty()) {
                putJsonArray("body_water_mass") {
                    healthData.bodyWaterMass.forEach { add(buildJsonObject {
                        put("kilograms", it.kilograms)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.hrv.isNotEmpty()) {
                putJsonArray("heart_rate_variability") {
                    healthData.hrv.forEach { add(buildJsonObject {
                        put("heart_rate_variability_millis", it.heartRateVariabilityMillis)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.menstruationPeriod.isNotEmpty()) {
                putJsonArray("menstruation_period") {
                    healthData.menstruationPeriod.forEach { add(buildJsonObject {
                        put("start_time", it.startTime.toString())
                        put("end_time", it.endTime.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.menstruationFlow.isNotEmpty()) {
                putJsonArray("menstruation_flow") {
                    healthData.menstruationFlow.forEach { add(buildJsonObject {
                        put("flow", it.flow)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.basalMetabolicRate.isNotEmpty()) {
                putJsonArray("basal_metabolic_rate") {
                    healthData.basalMetabolicRate.forEach { add(buildJsonObject {
                        put("kilocalories_per_day", it.kilocaloriesPerDay)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.vo2Max.isNotEmpty()) {
                putJsonArray("vo2_max") {
                    healthData.vo2Max.forEach { add(buildJsonObject {
                        put("vo2_ml_per_min_per_kg", it.vo2MillilitersPerMinuteKilogram)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.skinTemperature.isNotEmpty()) {
                putJsonArray("skin_temperature") {
                    healthData.skinTemperature.forEach { add(buildJsonObject {
                        put("delta_celsius", it.deltaCelsius)
                        it.baselineCelsius?.let { b -> put("baseline_celsius", b) }
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.basalBodyTemperature.isNotEmpty()) {
                putJsonArray("basal_body_temperature") {
                    healthData.basalBodyTemperature.forEach { add(buildJsonObject {
                        put("celsius", it.celsius)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.intermenstrualBleeding.isNotEmpty()) {
                putJsonArray("intermenstrual_bleeding") {
                    healthData.intermenstrualBleeding.forEach { add(buildJsonObject {
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.ovulationTest.isNotEmpty()) {
                putJsonArray("ovulation_test") {
                    healthData.ovulationTest.forEach { add(buildJsonObject {
                        put("result", it.result)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.cervicalMucus.isNotEmpty()) {
                putJsonArray("cervical_mucus") {
                    healthData.cervicalMucus.forEach { add(buildJsonObject {
                        put("appearance", it.appearance)
                        put("sensation", it.sensation)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.sexualActivity.isNotEmpty()) {
                putJsonArray("sexual_activity") {
                    healthData.sexualActivity.forEach { add(buildJsonObject {
                        put("protection_used", it.protectionUsed)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
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
