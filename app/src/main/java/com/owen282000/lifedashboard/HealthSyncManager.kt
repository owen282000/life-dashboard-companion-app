package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import com.owen282000.lifedashboard.NutritionSupport.putNutrition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** Max read+deliver passes per sync run, bounding worker time while draining a backlog (#38). */
private const val MAX_SYNC_PASSES = 8

/** For a payload that carries deletions and nothing else; see the end of [HealthSyncManager.performSync]. */
private val EMPTY_HEALTH_DATA = HealthData()

/**
 * Max read+deliver passes per backfill window. Generous: a 3-day window of 5-second heart rate
 * samples is ~52 batches of 1000; the bound only guards against a cursor that stops advancing.
 */
private const val MAX_PASSES_PER_BACKFILL_WINDOW = 100

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
            // Preview must show exactly what a sync would send, including daily totals.
            val dailyTotals = if (preferencesManager.includeDailyTotals())
                healthConnectManager.readDailyTotals(days = 2, enabledTypes = enabledTypes) else emptyList()
            // The preview buckets with what the last sync was holding, like a sync would, but
            // stores nothing: looking is not sending.
            val payload = buildJsonPayload(
                healthData,
                dailyTotals = dailyTotals,
                resolved = ResolutionApplier.from(
                    healthData,
                    preferencesManager.getSeriesResolutions(),
                    carriedIn = preferencesManager.getBucketCarry()
                )
            )
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
            val mqttSettings = preferencesManager.resolvedMqttSettings(MqttSection.HEALTH)
            val publishToMqtt = mqttSettings.enabled && mqttSettings.host.isNotBlank()

            // MQTT alone is a valid destination since 1.13.0; Screen Time already allowed it.
            if (webhookUrls.isEmpty() && !publishToMqtt) {
                return@withContext Result.failure(Exception("No webhook URLs configured"))
            }

            val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
            if (enabledTypes.isEmpty()) {
                return@withContext Result.failure(Exception("No data types enabled"))
            }

            // Dense types (heart rate) can hold a backlog many times the per-sync cap. A single
            // capped batch per run lets the backlog grow faster than it drains (issue #38), so
            // this loops read+deliver until no type was capped, bounded to keep worker runs short.
            val syncCounts = mutableMapOf<HealthDataType, Int>()
            var lastDelivered: HealthData? = null
            var queuedRecords: Int? = null
            var anyData = false
            // Samples of bucketed windows still open: carried from the last sync, then from
            // pass to pass, and stored again at the end so a window goes out once, complete.
            var carried = preferencesManager.getBucketCarry()

            // Deletions are read once per sync, not per pass: the changes feed is consumed by
            // reading it, so a second pass would find it empty and the first pass's deletions
            // would be the only ones ever sent. They ride along on the first payload that goes
            // out; passes after that carry none.
            //
            // What this sync reads is joined with anything an earlier sync read but could not
            // deliver, and the whole lot stays in storage until a payload has actually been
            // handed to the webhook. A sync that finds no records, or whose records all land in
            // open buckets, ends without building a payload at all, and the feed cannot be read
            // twice, so clearing them any earlier would lose them for good (issue #61).
            var pendingDeletions = preferencesManager.getPendingDeletions().merge(readDeletions(enabledTypes))
            preferencesManager.setPendingDeletions(pendingDeletions)

            for (pass in 1..MAX_SYNC_PASSES) {
                // Re-read watermarks each pass; the previous pass advanced them.
                val lastSyncTimestamps = enabledTypes.associateWith { type ->
                    preferencesManager.getHealthLastSyncTimestamp(type)?.let { Instant.ofEpochMilli(it) }
                }

                val healthDataResult = healthConnectManager.readHealthData(enabledTypes, lastSyncTimestamps)
                if (healthDataResult.isFailure) {
                    if (anyData) break
                    return@withContext Result.failure(
                        healthDataResult.exceptionOrNull() ?: Exception("Failed to read health data")
                    )
                }
                val healthData = healthDataResult.getOrThrow()
                if (isHealthDataEmpty(healthData)) break
                anyData = true
                lastDelivered = healthData

                val totalRecords = countRecords(healthData)

                // MQTT-only setup: nothing to post, nothing to queue. The newest batch is
                // published after the loop, like it is when webhooks are configured too.
                if (webhookUrls.isEmpty()) {
                    // MQTT publishes sensor states, which hold the latest value rather than a
                    // record list, so there is nothing here for a deletion to withdraw. They
                    // stay in storage rather than being dropped: a user who adds a webhook later
                    // gets them on its first payload, and the feed they came from is gone.
                    SyncFailureNotifier.recordResult(context, LogType.HEALTH_CONNECT, true)
                    SyncStatusStore.record(context, true, totalRecords, LogType.HEALTH_CONNECT)
                    LifetimeStats.recordDelivery(context, totalRecords, 0, LogType.HEALTH_CONNECT)
                    val passCounts = mutableMapOf<HealthDataType, Int>()
                    updateSyncTimestamps(healthData, passCounts)
                    passCounts.forEach { (type, count) -> syncCounts.merge(type, count, Int::plus) }
                    if (healthData.cappedTypes.isEmpty()) break
                    continue
                }

                val webhookManager = WebhookManager(
                    webhookUrls = webhookUrls,
                    context = context,
                    dataType = "health_connect",
                    recordCount = totalRecords,
                    logType = LogType.HEALTH_CONNECT,
                    customHeaders = preferencesManager.getHealthWebhookHeaders(),
                    signingSecret = preferencesManager.getHealthWebhookSecret()
                )

                // Build JSON payload, with deduplicated daily totals when enabled
                val dailyTotals = if (preferencesManager.includeDailyTotals())
                    healthConnectManager.readDailyTotals(days = 2, enabledTypes = enabledTypes) else emptyList()
                // Bucketed series go out once per sync, in its last pass; earlier passes only
                // collect. The collection is stored after every pass so an interrupted sync
                // hands it to the next one instead of losing it.
                val isLastPass = healthData.cappedTypes.isEmpty() || pass == MAX_SYNC_PASSES
                val resolved = ResolutionApplier.from(
                    healthData,
                    preferencesManager.getSeriesResolutions(),
                    carriedIn = carried,
                    emit = isLastPass
                )
                carried = resolved.carriedOut
                preferencesManager.setBucketCarry(carried)

                // A collecting pass whose every record went into a bucket has nothing to post.
                val recordsToSend = totalRecords - resolved.absorbedRecords
                val bucketsToSend = resolved.series.values.sumOf { it.size }
                if (recordsToSend == 0 && bucketsToSend == 0) {
                    val passCounts = mutableMapOf<HealthDataType, Int>()
                    updateSyncTimestamps(healthData, passCounts)
                    passCounts.forEach { (type, count) -> syncCounts.merge(type, count, Int::plus) }
                    if (isLastPass) break
                    continue
                }

                val jsonPayload = buildJsonPayload(
                    healthData,
                    dailyTotals = dailyTotals,
                    resolved = resolved,
                    deletions = pendingDeletions,
                    sequence = preferencesManager.nextHealthSyncSequence()
                )
                // Held by this payload now, so a later pass of this same sync must not repeat
                // them. Storage is only cleared once the payload is somewhere durable, below:
                // a throw from the post would otherwise leave them in neither the webhook, the
                // outbox, nor the feed they came from, which cannot be read twice.
                pendingDeletions = DeletionSummary.EMPTY

                val postResult = webhookManager.postData(jsonPayload)
                SyncFailureNotifier.recordResult(context, LogType.HEALTH_CONNECT, postResult.isSuccess)
                SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) totalRecords else 0, LogType.HEALTH_CONNECT)

                // Watermarks advance regardless of delivery outcome: a failed payload goes to the
                // outbox and is guaranteed to be delivered by a later drain, so re-reading (and
                // potentially double-sending) the same records is unnecessary.
                val passCounts = mutableMapOf<HealthDataType, Int>()
                updateSyncTimestamps(healthData, passCounts)
                passCounts.forEach { (type, count) -> syncCounts.merge(type, count, Int::plus) }

                if (postResult.isFailure) {
                    PendingSyncStore.forContext(context).enqueue(
                        payload = jsonPayload,
                        dataType = "health_connect",
                        logType = LogType.HEALTH_CONNECT.name,
                        recordCount = totalRecords,
                        nowMillis = System.currentTimeMillis()
                    )
                    // On disk in the outbox now, so it will be delivered by a later drain.
                    preferencesManager.setPendingDeletions(DeletionSummary.EMPTY)
                    queuedRecords = totalRecords
                    break
                }
                // Delivered.
                preferencesManager.setPendingDeletions(DeletionSummary.EMPTY)

                if (healthData.cappedTypes.isEmpty()) break
            }

            // A deletion is often the only thing that changed: removing a meal without adding
            // one leaves nothing new to read, so the loop above ends without building a payload
            // and the deletions would sit in storage until some later sync happens to carry
            // records. That is the reported case in issue #61, so they get a payload of their
            // own, carrying no records.
            var deletionsDelivered = false
            if (!pendingDeletions.isEmpty && webhookUrls.isNotEmpty()) {
                val deletionPayload = buildJsonPayload(
                    EMPTY_HEALTH_DATA,
                    deletions = pendingDeletions,
                    sequence = preferencesManager.nextHealthSyncSequence()
                )
                pendingDeletions = DeletionSummary.EMPTY

                val webhookManager = WebhookManager(
                    webhookUrls = webhookUrls,
                    context = context,
                    dataType = "health_connect",
                    recordCount = 0,
                    logType = LogType.HEALTH_CONNECT,
                    customHeaders = preferencesManager.getHealthWebhookHeaders(),
                    signingSecret = preferencesManager.getHealthWebhookSecret()
                )
                val postResult = webhookManager.postData(deletionPayload)
                // Reported like any other delivery: a webhook that is down for a run of
                // deletion-only syncs would otherwise never trip the failure notifier, and the
                // dashboard would show a last sync that never moved while payloads went out.
                SyncFailureNotifier.recordResult(context, LogType.HEALTH_CONNECT, postResult.isSuccess)
                SyncStatusStore.record(context, postResult.isSuccess, 0, LogType.HEALTH_CONNECT)
                if (postResult.isFailure) {
                    PendingSyncStore.forContext(context).enqueue(
                        payload = deletionPayload,
                        dataType = "health_connect",
                        logType = LogType.HEALTH_CONNECT.name,
                        recordCount = 0,
                        nowMillis = System.currentTimeMillis()
                    )
                    // queuedRecords stays as it was: it counts records waiting in the outbox,
                    // and this payload has none, so setting it would tell the user "0 records
                    // queued for retry". The outbox entry and the failure notifier above
                    // already record that the delivery failed.
                }
                // Durable either way now: delivered, or on disk in the outbox.
                preferencesManager.setPendingDeletions(DeletionSummary.EMPTY)
                deletionsDelivered = true
            }

            // A sync that only withdrew records did do something, so it must not report "no new
            // data": the user asked for a sync and one went out.
            if (!anyData && !deletionsDelivered) {
                return@withContext Result.success(HealthSyncResult.NoData)
            }

            // Publish the newest values to the user's MQTT broker (Home Assistant Discovery)
            // once per run, after draining: the last batch is the newest thanks to the
            // oldest-first cap. Failures never block the webhook sync; the outcome is stored
            // and shown in the MQTT settings section.
            lastDelivered?.let { data ->
                val totalsForMqtt = if (publishToMqtt) {
                    runCatching { healthConnectManager.readDailyTotals(days = 1, enabledTypes = enabledTypes) }.getOrDefault(emptyList())
                } else emptyList()
                MqttPublisher(context).publishHealthData(data, totalsForMqtt)
            }

            queuedRecords?.let {
                return@withContext Result.success(HealthSyncResult.Queued(it))
            }
            Result.success(HealthSyncResult.Success(syncCounts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One-time export of [days] of history to the configured webhooks, oldest window first.
     * Runs in 3-day windows, each drained in capped chunks until exhausted, so payloads stay
     * bounded without silently dropping dense data past the per-type cap.
     * Deliberately independent of the sync watermarks: it never advances them, and regular
     * incremental syncs continue unaffected. Payloads carry "backfill": true plus the window
     * bounds so receivers can distinguish them; records still carry uuids, so re-received
     * overlaps deduplicate server-side. Stops at the first failed delivery so a rerun can
     * resume; [onProgress] reports (completedWindows, totalWindows).
     */
    suspend fun performBackfill(
        days: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        val webhookUrls = preferencesManager.getHealthWebhookUrls()
        if (webhookUrls.isEmpty()) {
            return@withContext Result.failure(Exception("No webhook URLs configured"))
        }
        val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
        if (enabledTypes.isEmpty()) {
            return@withContext Result.failure(Exception("No data types enabled"))
        }

        val windowDays = 3L
        val end = Instant.now()
        val start = end.minus(java.time.Duration.ofDays(days.toLong()))
        val totalWindows = ((days + windowDays - 1) / windowDays).toInt()
        var totalRecordsSent = 0

        var windowStart = start
        var completed = 0
        while (windowStart < end) {
            val windowEnd = minOf(windowStart.plus(java.time.Duration.ofDays(windowDays)), end)

            // Exhaust the window before advancing (issue #39): one capped read per window
            // silently dropped everything past the cap for dense types. The cursor is a local
            // per-type lastModifiedTime watermark; the tie-inclusive cap makes its strict '>'
            // filter safe, so repeated reads walk the window chunk by chunk.
            var cursor: Map<HealthDataType, Instant?> = enabledTypes.associateWith { null }
            // The window's days as Health Connect counts them, whole days from local midnight
            // to midnight, so a receiver gets each day's real total and not the sum of the raw
            // records, which double counts a phone and a watch. A day cut by a window bound is
            // asked for in full by both windows and arrives twice with the same figures.
            val dailyTotals = if (preferencesManager.includeDailyTotals()) {
                val zone = java.time.ZoneId.systemDefault()
                val firstDay = windowStart.atZone(zone).toLocalDate().atStartOfDay()
                val dayAfterLast = windowEnd.atZone(zone).toLocalDate().plusDays(1).atStartOfDay()
                healthConnectManager.readDailyTotalsBetween(
                    firstDay,
                    minOf(dayAfterLast, java.time.LocalDateTime.now(zone)),
                    enabledTypes
                )
            } else emptyList()
            for (pass in 1..MAX_PASSES_PER_BACKFILL_WINDOW) {
                val readResult = healthConnectManager.readHealthData(
                    enabledTypes,
                    lastSyncTimestamps = cursor,
                    windowStart = windowStart,
                    windowEnd = windowEnd
                )
                val healthData = readResult.getOrElse {
                    return@withContext Result.failure(it)
                }
                // An empty read ends the window. On a later chunk that is ordinary: the previous
                // chunk drained it and already carried window_complete. On the first chunk it
                // means the window holds nothing, and that empty snapshot is worth sending,
                // because it is what tells a receiver the window is genuinely empty rather than
                // unreported (issue #61).
                if (isHealthDataEmpty(healthData) && pass > 1) break

                val recordCount = countRecords(healthData)
                // Drained means every record the window holds has now been read. That is what
                // window_complete claims, and a receiver acts on it by dropping ids it holds in
                // the range that the window did not carry (issue #61), so it must never be
                // claimed on a window that was merely abandoned: running out of passes leaves
                // records unsent, and saying "complete" there would delete them on the receiver.
                val drained = healthData.cappedTypes.isEmpty()
                val isLastChunk = drained || pass == MAX_PASSES_PER_BACKFILL_WINDOW
                val payload = buildJsonPayload(
                    healthData,
                    // In every chunk of the window, not only the first: a receiver cannot
                    // tell a later chunk from a window without totals, and the same
                    // figures twice cost a few bytes where a missing set costs a warning.
                    dailyTotals = dailyTotals,
                    extraFields = mapOf(
                        "backfill" to JsonPrimitive(true),
                        "window_start" to JsonPrimitive(windowStart.toString()),
                        "window_end" to JsonPrimitive(windowEnd.toString()),
                        // False on every chunk but the one that drained the window. A receiver
                        // that only ever sees false for a window knows the snapshot was cut
                        // short and must not treat missing ids as deleted.
                        "window_complete" to JsonPrimitive(drained)
                    ),
                    // A backfill window lies wholly in the past, so every bucket in it is
                    // closed; passing the window end as "now" says so without consulting the clock.
                    resolved = ResolutionApplier.from(
                        healthData,
                        preferencesManager.getSeriesResolutions(),
                        now = windowEnd
                    ),
                    sequence = preferencesManager.nextHealthSyncSequence()
                )
                val webhookManager = WebhookManager(
                    webhookUrls = webhookUrls,
                    context = context,
                    dataType = "health_connect_backfill",
                    recordCount = recordCount,
                    logType = LogType.HEALTH_CONNECT,
                    customHeaders = preferencesManager.getHealthWebhookHeaders(),
                    signingSecret = preferencesManager.getHealthWebhookSecret()
                )
                val postResult = webhookManager.postData(payload)
                // A delivered backfill window counts as a sync on the dashboard: "today" and
                // "last sync" would otherwise say nothing while thousands of records went out.
                SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) recordCount else 0, LogType.HEALTH_CONNECT)
                if (postResult.isFailure) {
                    return@withContext Result.failure(
                        Exception("Delivery failed after $completed of $totalWindows windows; rerun to resume")
                    )
                }
                totalRecordsSent += recordCount

                if (isLastChunk) break
                cursor = cursor + healthData.watermarks
            }

            completed++
            onProgress(completed, totalWindows)
            windowStart = windowEnd
        }
        Result.success(totalRecordsSent)
    }

    private fun countRecords(data: HealthData): Int {
        return data.steps.size + data.sleep.size + data.heartRate.size + data.distance.size +
                data.activeCalories.size + data.totalCalories.size + data.weight.size +
                data.height.size + data.bloodPressure.size + data.bloodGlucose.size +
                data.oxygenSaturation.size + data.bodyTemperature.size + data.respiratoryRate.size +
                data.restingHeartRate.size + data.exercise.size + data.hydration.size +
                data.nutrition.size + data.mindfulness.size + data.bodyFat.size +
                data.leanBodyMass.size + data.boneMass.size + data.bodyWaterMass.size +
                data.hrv.size + data.menstruationPeriod.size + data.menstruationFlow.size +
                data.basalMetabolicRate.size + data.vo2Max.size + data.skinTemperature.size +
                data.basalBodyTemperature.size + data.intermenstrualBleeding.size +
                data.ovulationTest.size + data.cervicalMucus.size + data.sexualActivity.size
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

    /**
     * Collects the deletions Health Connect recorded for every enabled type since the last sync,
     * and stores the token each type hands back (issue #61).
     *
     * Tokens are stored whatever the payload does afterwards: a token is a position in a feed
     * that reading already consumed, so the old one is worth nothing. What the deletions
     * themselves are worth is decided elsewhere; the caller keeps them in storage until a
     * payload has taken them, because this read cannot be repeated.
     */
    private suspend fun readDeletions(enabledTypes: Set<HealthDataType>): DeletionSummary {
        val now = System.currentTimeMillis()
        val results = mutableMapOf<HealthDataType, ChangesResult>()

        for (type in enabledTypes) {
            val stored = preferencesManager.getHealthChangesToken(type)
            val issuedAt = preferencesManager.getHealthChangesTokenIssuedAt(type)
            // A token past its 30 days is refused anyway; treating it as absent registers a new
            // one in the same call instead of spending a round trip to be told so.
            val usable = if (DeletionTracking.isTokenUsable(stored, issuedAt, now)) stored else null

            // Bounded per type and in total, so a Health Connect call that does not return
            // cannot hold the records behind it; see DeletionTracking.PER_TYPE_TIMEOUT_MS. A
            // type that runs out of time or budget errors out here, which keeps its token (the
            // feed was not consumed) and names it in deletions_unavailable for this payload.
            val timeoutMs = DeletionTracking.timeoutFor(System.currentTimeMillis() - now)
            val result = if (timeoutMs == 0L) {
                ChangesResult(error = "skipped: deletion budget spent")
            } else {
                withTimeoutOrNull(timeoutMs) { healthConnectManager.readDeletions(type, usable) }
                    ?: ChangesResult(error = "timed out after $timeoutMs ms")
            }
            // A type that errored keeps its token: the feed was not consumed, so the next sync
            // can read the same position again.
            if (result.nextToken != null) {
                preferencesManager.setHealthChangesToken(type, result.nextToken, now)
            }
            // An expired token is reported even when the app decided that itself, as long as
            // there was a token to expire; a first-ever token is not a gap, it is a start.
            val expiredHere = result.expired || (stored != null && usable == null)
            results[type] = result.copy(expired = expiredHere)
        }

        return DeletionSummary(
            deleted = DeletionTracking.merge(results),
            expiredTypes = DeletionTracking.expiredTypes(results)
        )
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

    private fun buildJsonPayload(
        healthData: HealthData,
        extraFields: Map<String, JsonPrimitive> = emptyMap(),
        dailyTotals: List<DailyTotals> = emptyList(),
        resolved: ResolutionApplier = ResolutionApplier(emptyMap()),
        deletions: DeletionSummary = DeletionSummary.EMPTY,
        /**
         * Taken from the counter by every caller that sends. The preview passes null: taking a
         * number there would leave a gap in the sequence a receiver sees, and looking is not
         * sending.
         */
        sequence: Long? = null
    ): String {
        val json = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("app_version", getAppVersion())
            put("source", "health_connect")
            // Strictly increasing per payload, so a retry that arrives after a newer payload is
            // recognisable as stale instead of overwriting it (issue #61).
            sequence?.let { put("sequence", it) }
            extraFields.forEach { (key, value) -> put(key, value) }

            // deleted_records names what to remove; deletions_unavailable names the types the
            // app could not observe, so a receiver reconciles those from a backfill snapshot
            // instead of trusting an incremental payload that cannot have seen them.
            DeletionTracking.payloadFields(deletions).forEach { (key, value) -> put(key, value) }

            // Series the user chose to bucket replace their raw array under the same key, and
            // _resolutions names the window each one used. Both are absent at raw resolution,
            // so an untouched install sends exactly the payload it always did.
            resolved.series.forEach { (key, buckets) -> put(key, buckets) }
            if (resolved.used.isNotEmpty()) {
                put("_resolutions", ResolutionPayload.resolutionsJson(resolved.used))
            }

            if (dailyTotals.isNotEmpty()) {
                putJsonArray("daily_totals") {
                    dailyTotals.forEach { day -> add(buildJsonObject {
                        put("date", day.date)
                        day.steps?.let { put("steps", it) }
                        day.distanceMeters?.let { put("distance_meters", it) }
                        day.activeCalories?.let { put("active_calories", it) }
                        day.totalCalories?.let { put("total_calories", it) }
                    }) }
                }
            }

            if (healthData.steps.isNotEmpty() && !resolved.isBucketed("steps")) {
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

            if (healthData.heartRate.isNotEmpty() && !resolved.isBucketed("heart_rate")) {
                putJsonArray("heart_rate") {
                    healthData.heartRate.forEach { add(buildJsonObject {
                        put("bpm", it.bpm)
                        put("time", it.time.toString())
                        it.uuid?.let { u -> put("uuid", u) }
                        it.source?.let { s -> put("source", s) }
                    }) }
                }
            }

            if (healthData.distance.isNotEmpty() && !resolved.isBucketed("distance")) {
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

            if (healthData.activeCalories.isNotEmpty() && !resolved.isBucketed("active_calories")) {
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

            if (healthData.totalCalories.isNotEmpty() && !resolved.isBucketed("total_calories")) {
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

            if (healthData.oxygenSaturation.isNotEmpty() && !resolved.isBucketed("oxygen_saturation")) {
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

            if (healthData.respiratoryRate.isNotEmpty() && !resolved.isBucketed("respiratory_rate")) {
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
                    healthData.nutrition.forEach { add(buildJsonObject { putNutrition(it) }) }
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

            if (healthData.hrv.isNotEmpty() && !resolved.isBucketed("heart_rate_variability")) {
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

            if (healthData.skinTemperature.isNotEmpty() && !resolved.isBucketed("skin_temperature")) {
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
                            put("raw_min_time", diag.rawMinTime?.toString())
                            put("raw_max_time", diag.rawMaxTime?.toString())
                            put("raw_latest_modified_time", diag.rawLatestModifiedTime?.toString())
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
