package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import com.owen282000.lifedashboard.NutritionSupport.putNutrition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * samples is ~52 batches of 1000, and of 1-second samples ~260. The cursor always moves past
 * what a pass sent, so this only bounds the time; a window that needs more stops the backfill
 * with an error instead of being cut short in silence.
 */
internal const val MAX_PASSES_PER_BACKFILL_WINDOW = 400

/** Serialises [HealthSyncManager.performSync] and [HealthSyncManager.runBackfill] across every caller in the process. */
private val SYNC_LOCK = Mutex()

/** dataType of a backfill chunk; logged only when its delivery failed, per URL. */
const val BACKFILL_DATA_TYPE = "health_connect_backfill"

/** dataType of the one row a backfill run writes in the Logs tab, as in the iOS app. */
const val BACKFILL_RUN_DATA_TYPE = "health_connect_backfill_run"

// The status of that row, also in its payload.
private const val STATUS_DONE = "done"
private const val STATUS_PAUSED = "paused"
private const val STATUS_STOPPED = "stopped"
private const val STATUS_FAILED = "failed"

class HealthSyncManager(
    private val context: Context,
    /** The app's own by default; the instrumented suite passes one with a wrapped client. */
    private val healthConnectManager: HealthConnectManager = HealthConnectManager(context)
) {

    private val preferencesManager = PreferencesManager(context)

    suspend fun previewData(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
            if (enabledTypes.isEmpty()) {
                return@withContext Result.failure(Exception("No data types enabled"))
            }

            val lastSyncTimestamps = enabledTypes.associateWith { type -> preferencesManager.getHealthWatermark(type) }

            val healthDataResult = healthConnectManager.readHealthData(
                enabledTypes,
                lastSyncTimestamps,
                coveredUntil = enabledTypes.associateWith { preferencesManager.getHealthCoveredUntil(it) },
                wholeWindows = ResolutionApplier.wholeRequests(preferencesManager.getSeriesResolutions(), preferencesManager.getBucketCarry())
            )
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One sync at a time per process. A manual sync, the tile, the broadcast and the worker
     * can all start one, and two running together would read and write the watermarks, the
     * Receive ledger and the pending acks over each other; the second simply waits its turn.
     * A backfill holds the same lock, so a sync started during one waits for it to end.
     */
    suspend fun performSync(): Result<HealthSyncResult> = withContext(Dispatchers.IO) {
        SYNC_LOCK.withLock { performSyncLocked() }
    }

    private suspend fun performSyncLocked(): Result<HealthSyncResult> {
        try {
            // Deliver any queued payloads from earlier failed syncs first, preserving order.
            PendingDrainer.drain(context)

            val webhookUrls = preferencesManager.getHealthWebhookUrls()
            val mqttSettings = preferencesManager.resolvedMqttSettings(MqttSection.HEALTH)
            val publishToMqtt = mqttSettings.enabled && mqttSettings.host.isNotBlank()

            // MQTT alone is a valid destination since 1.13.0; Screen Time already allowed it.
            if (webhookUrls.isEmpty() && !publishToMqtt) {
                return Result.failure(Exception("No webhook URLs configured"))
            }

            // Receive (issue #62): one applier per sync. It puts the writeback block into the
            // request for the source URL and reads that URL's answer; every other URL is
            // untouched. Inactive unless the switch is on, the source URL is still in the
            // section and there is a secret to verify the answer with.
            val writeBack = WriteBackApplier(context, preferencesManager, healthConnectManager)

            // With Receive on, a phone that reads nothing still has a reason to sync: the
            // heartbeat below is what fetches the measurements and carries the acks.
            val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
            if (enabledTypes.isEmpty() && !writeBack.active) {
                return Result.failure(Exception("No data types enabled"))
            }
            // Whether a request with the writeback block reached the source URL this sync.
            // When none did, whatever the reason (nothing read, everything absorbed into open
            // buckets, no read types at all), the heartbeat at the end makes the round trip.
            var postedToSource = false

            // Dense types (heart rate) can hold a backlog many times the per-sync cap. A single
            // capped batch per run lets the backlog grow faster than it drains (issue #38), so
            // this loops read+deliver until no type was capped, bounded to keep worker runs short.
            val syncCounts = mutableMapOf<HealthDataType, Int>()
            // Webhooks that missed a payload another webhook took, and how many there are.
            val missedUrls = mutableSetOf<String>()
            var webhookCount = 0
            var lastDelivered: HealthData? = null
            var queuedRecords: Int? = null
            // Records an MQTT-only setup read this sync, reported once the publish has run.
            var mqttOnlyRecords = 0
            // A failed post that could not be queued either, see PendingSyncStore.writeAhead.
            var notQueued: Throwable? = null
            var anyData = false
            // Samples of bucketed windows still open: carried from the last sync, then from
            // pass to pass, and stored again at the end so a window goes out once, complete.
            var carried = preferencesManager.getBucketCarry()
            val resolutions = preferencesManager.getSeriesResolutions()
            // What the reads of this sync held whole per bucketed type, the newest read of a type
            // winning: a closed window is built from it, complete, rather than from only the
            // samples that changed (P2-16). A later pass reads only the types still draining, so
            // the others keep what the pass that read them held.
            var whole = emptyMap<HealthDataType, WholeContent>()

            // Deletions are read once per sync, not per pass: the changes feed is consumed by
            // reading it, so a second pass would find it empty and the first pass's deletions
            // would be the only ones ever sent. They ride along on the first payload that goes
            // out; passes after that carry none.
            //
            // What this sync reads is joined with anything an earlier sync read but could not
            // deliver, and the whole lot stays in storage until a payload that holds it is on
            // disk, written ahead of its post. A sync that finds no records, or whose records
            // all land in open buckets, ends without building a payload at all, and the feed
            // cannot be read twice, so clearing them any earlier would lose them for good
            // (issue #61).
            // Set when the first read found nothing because Health Connect answered for none of
            // the enabled types; the sync then reports a failure instead of "no new data".
            var healthConnectSilent = false

            // Read the feed first and storage after: reading the feed prunes stored deletions of
            // records written again since, and storage read before it would put them back.
            // Storage then holds what every type that moved its token found, so only the types
            // whose feed could not be read are added from the result; adding the rest again
            // would count records_outside_window twice, since those counts add up.
            val freshDeletions = readDeletions(enabledTypes, preferencesManager.getWriteBackLedger().ownRecordIds)
            var pendingDeletions = preferencesManager.getPendingDeletions()
                .merge(DeletionSummary(expiredTypes = freshDeletions.expiredTypes))
            preferencesManager.setPendingDeletions(pendingDeletions)
            // The id of every record Health Connect returned this sync, per payload key, before any
            // filter: those exist, so none of them may go out as deleted (issues #71, #72). A
            // deletion that is not among them goes out with the first payload, also for a type
            // still working through a backlog: nothing can bring such a record back.
            val readIds = mutableMapOf<String, Set<String>>()
            // The types the last pass left capped, which are all a later pass reads: the others
            // were read in full already, and every page costs a call of Health Connect's read
            // quota (issue #73). Records written while the sync runs go out with the next one.
            var draining: Set<HealthDataType>? = null
            // Health Connect refused a read for its quota: the pass delivers what it read, and
            // the sync stops reading there (issue #73).
            var quotaHit = false
            // Asked for once per sync and put on every payload of it; the totals do not change
            // between passes a few seconds apart, and each ask costs a call of the quota.
            var dailyTotalsOnce: List<DailyTotals>? = null

            for (pass in 1..MAX_SYNC_PASSES) {
                if (quotaHit) break
                val typesToRead = draining ?: enabledTypes
                // Re-read watermarks each pass; the previous pass advanced them. The range each
                // type is read over is stored alongside, see LookbackWindow.
                val lastSyncTimestamps = enabledTypes.associateWith { type -> preferencesManager.getHealthWatermark(type) }

                val healthDataResult = healthConnectManager.readHealthData(
                    typesToRead,
                    lastSyncTimestamps,
                    coveredUntil = enabledTypes.associateWith { preferencesManager.getHealthCoveredUntil(it) },
                    wholeWindows = ResolutionApplier.wholeRequests(resolutions, carried)
                )
                if (healthDataResult.isFailure) {
                    if (anyData) break
                    return Result.failure(
                        healthDataResult.exceptionOrNull() ?: Exception("Failed to read health data")
                    )
                }
                val healthData = healthDataResult.getOrThrow()
                whole = whole + ResolutionApplier.wholeContent(healthData)
                draining = healthData.cappedTypes
                if (healthData.quotaExhausted) quotaHit = true
                healthData.readIds.forEach { (type, ids) ->
                    val key = DeletionTracking.payloadKey(type)
                    readIds[key] = readIds[key].orEmpty() + ids
                }
                if (isHealthDataEmpty(healthData)) {
                    healthConnectSilent = !anyData && enabledTypes.isNotEmpty() && healthData.unreadTypes.containsAll(enabledTypes)
                    // An empty batch can still carry watermarks: when the only new records were
                    // the app's own (Receive), the read left them out but moved the watermark
                    // past them, and without storing it here they would be read and counted
                    // again on every sync for the whole lookback window.
                    updateSyncTimestamps(healthData, mutableMapOf(), holdGapAnchors = webhookUrls.isNotEmpty())
                    break
                }
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
                    // Whether the records went anywhere is known once the publish below ran.
                    mqttOnlyRecords += totalRecords
                    val passCounts = mutableMapOf<HealthDataType, Int>()
                    updateSyncTimestamps(healthData, passCounts)
                    passCounts.forEach { (type, count) -> syncCounts.merge(type, count, Int::plus) }
                    if (healthData.cappedTypes.isEmpty()) break
                    continue
                }

                // Build JSON payload, with deduplicated daily totals when enabled
                val dailyTotals = dailyTotalsOnce ?: (
                    if (preferencesManager.includeDailyTotals() && !quotaHit)
                        healthConnectManager.readDailyTotals(days = 2, enabledTypes = enabledTypes) else emptyList()
                    ).also { dailyTotalsOnce = it }
                // Bucketed series go out once per sync, in its last pass; earlier passes only
                // collect. The collection is stored together with the watermarks, once the pass
                // is done or its payload is on disk: the samples it holds were read above the
                // stored watermark, so storing it any earlier would let an interrupted pass count
                // them twice, once from the carry and once from the next read (F4 of P2-4).
                val isLastPass = healthData.cappedTypes.isEmpty() || pass == MAX_SYNC_PASSES || quotaHit
                val resolved = ResolutionApplier.from(
                    healthData,
                    resolutions,
                    carriedIn = carried,
                    emit = isLastPass,
                    whole = whole
                )
                carried = resolved.carriedOut

                // A collecting pass whose every record went into a bucket has nothing to post.
                val recordsToSend = totalRecords - resolved.absorbedRecords
                val bucketsToSend = resolved.series.values.sumOf { it.size }
                if (recordsToSend == 0 && bucketsToSend == 0) {
                    val passCounts = mutableMapOf<HealthDataType, Int>()
                    updateSyncTimestamps(healthData, passCounts, holdGapAnchors = true)
                    preferencesManager.setBucketCarry(carried)
                    passCounts.forEach { (type, count) -> syncCounts.merge(type, count, Int::plus) }
                    if (isLastPass) break
                    continue
                }

                // A deletion of a record Health Connect returned this sync is dropped: the record was
                // read after the deletion was, so it exists again under the same id (issues #71,
                // #72). The rest goes out now.
                val deletionsNow = pendingDeletions.without(readIds)
                val jsonPayload = buildJsonPayload(
                    healthData,
                    dailyTotals = dailyTotals,
                    resolved = resolved,
                    deletions = deletionsNow,
                    sequence = preferencesManager.nextHealthSyncSequence()
                )
                // Held by this payload now, so a later pass of this same sync must not repeat
                // them. Storage is only cleared once the payload is on disk, in the commit
                // below: a throw before that would otherwise leave them in neither the webhook,
                // the outbox, nor the feed they came from, which cannot be read twice.
                pendingDeletions = DeletionSummary.EMPTY

                val sourcePost = writeBack.sourcePost(jsonPayload)
                val webhookManager = WebhookManager(
                    webhookUrls = webhookUrls,
                    context = context,
                    dataType = "health_connect",
                    recordCount = totalRecords,
                    logType = LogType.HEALTH_CONNECT,
                    customHeaders = preferencesManager.getHealthWebhookHeaders(),
                    urlsWithoutHeaders = preferencesManager.getHealthUrlsWithoutHeaders(),
                    signingSecret = preferencesManager.getHealthWebhookSecret(),
                    source = sourcePost
                )
                // Write-ahead (PendingSyncStore.writeAhead): the payload is on disk, out of the
                // drain's sight, before the commit stores anything that read it, and the post
                // comes last. Delivered, the copy goes; not delivered, also when the worker is
                // stopped mid-post, it joins the outbox; a process that dies in between leaves
                // it for the next drain. A crash can no longer store the watermarks of a payload
                // that never reached the outbox.
                //
                // Watermarks advance regardless of delivery outcome: the outbox guarantees a
                // later delivery, so re-reading (and potentially double-sending) the same records
                // is unnecessary. The bucket carry is stored at the same moment, see above. Both
                // are stored before the post and so before Receive: its follow-ups can take a
                // minute, and a worker stopped in there would otherwise send this pass's closed
                // windows again next time, which a receiver that adds up sample counts would
                // count twice.
                val passCounts = mutableMapOf<HealthDataType, Int>()
                val carriedOut = carried
                val deletionsLeft = pendingDeletions
                var committed = false
                val postResult = PendingSyncStore.writeAhead(
                    context = context,
                    payload = jsonPayload,
                    dataType = "health_connect",
                    logType = LogType.HEALTH_CONNECT.name,
                    recordCount = totalRecords,
                    // Written through to disk before the post (durable, see PreferencesManager.save).
                    commit = {
                        updateSyncTimestamps(healthData, passCounts, durable = true)
                        preferencesManager.setBucketCarry(carriedOut, durable = true)
                        preferencesManager.setPendingDeletions(deletionsLeft, durable = true)
                        committed = true
                    },
                    post = { webhookManager.postData(jsonPayload) }
                )
                passCounts.forEach { (type, count) -> syncCounts.merge(type, count, Int::plus) }
                SyncFailureNotifier.recordDelivery(context, LogType.HEALTH_CONNECT, postResult)
                postResult.getOrNull()?.let { missedUrls += it.missedUrls; webhookCount = it.urlCount }
                SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) totalRecords else 0, LogType.HEALTH_CONNECT)

                // What the integration sent back rides on this same round trip; the outbox
                // only ever holds the plain payload, since a drained payload's answer is never
                // read and the acks it carried stay stored until one is.
                if (sourcePost != null) {
                    postedToSource = true
                    receive(writeBack, sourcePost, postResult)
                }

                if (postResult.isFailure && !committed) {
                    // The outbox could not be written, so nothing moved: the next sync reads
                    // these records again, and this one reports the failure as it is.
                    notQueued = postResult.exceptionOrNull()
                    break
                }
                if (postResult.isFailure) {
                    // In the outbox since the post returned, so a later drain delivers it.
                    queuedRecords = totalRecords
                    break
                }

                if (healthData.cappedTypes.isEmpty()) break
            }

            // A deletion is often the only thing that changed: removing a meal without adding
            // one leaves nothing new to read, so the loop above ends without building a payload
            // and the deletions would sit in storage until some later sync happens to carry
            // records. That is the reported case in issue #61, so they get a payload of their
            // own, carrying no records. So do records the read could not see (OutsideWindow),
            // which may be all that a watch uploaded after a long time away.
            var deletionsDelivered = false
            val deletionsNow = pendingDeletions.without(readIds)
            if (deletionsNow.isEmpty && !pendingDeletions.isEmpty && webhookUrls.isNotEmpty()) {
                // Every stored deletion named a record Health Connect still holds: all stale.
                preferencesManager.setPendingDeletions(DeletionSummary.EMPTY)
                pendingDeletions = DeletionSummary.EMPTY
            }
            if (!deletionsNow.isEmpty && webhookUrls.isNotEmpty()) {
                val deletionPayload = buildJsonPayload(
                    EMPTY_HEALTH_DATA,
                    deletions = deletionsNow,
                    sequence = preferencesManager.nextHealthSyncSequence()
                )
                pendingDeletions = DeletionSummary.EMPTY

                val sourcePost = writeBack.sourcePost(deletionPayload)
                val webhookManager = WebhookManager(
                    webhookUrls = webhookUrls,
                    context = context,
                    dataType = "health_connect",
                    recordCount = 0,
                    logType = LogType.HEALTH_CONNECT,
                    customHeaders = preferencesManager.getHealthWebhookHeaders(),
                    urlsWithoutHeaders = preferencesManager.getHealthUrlsWithoutHeaders(),
                    signingSecret = preferencesManager.getHealthWebhookSecret(),
                    source = sourcePost
                )
                // Write-ahead like the payloads above: the deletions leave storage once the
                // payload that holds them is on disk, before the post, and a failed or stopped
                // post leaves that payload in the outbox. Without a writable outbox they stay
                // stored until a post delivers them.
                var committed = false
                val postResult = PendingSyncStore.writeAhead(
                    context = context,
                    payload = deletionPayload,
                    dataType = "health_connect",
                    logType = LogType.HEALTH_CONNECT.name,
                    recordCount = 0,
                    commit = {
                        preferencesManager.setPendingDeletions(DeletionSummary.EMPTY, durable = true)
                        committed = true
                    },
                    post = { webhookManager.postData(deletionPayload) }
                )
                if (postResult.isFailure && !committed) notQueued = postResult.exceptionOrNull()
                // Reported like any other delivery: a webhook that is down for a run of
                // deletion-only syncs would otherwise never trip the failure notifier, and the
                // dashboard would show a last sync that never moved while payloads went out.
                SyncFailureNotifier.recordDelivery(context, LogType.HEALTH_CONNECT, postResult)
                postResult.getOrNull()?.let { missedUrls += it.missedUrls; webhookCount = it.urlCount }
                SyncStatusStore.record(context, postResult.isSuccess, 0, LogType.HEALTH_CONNECT)
                if (sourcePost != null) {
                    postedToSource = true
                    receive(writeBack, sourcePost, postResult)
                }
                // A failed one is in the outbox now, and queuedRecords stays as it was: it counts
                // records waiting in the outbox, and this payload has none, so setting it would
                // tell the user "0 records queued for retry". The outbox entry and the failure
                // notifier above already record that the delivery failed.
                deletionsDelivered = true
            }

            // No request with the writeback block went out this sync, yet Receive is on: the
            // heartbeat (protocol section 3.2) makes the round trip that carries the acks and
            // fetches what is waiting. That covers a sync with nothing to send, one whose
            // records all went into open buckets, and one with no read types. It is never queued
            // in the outbox, because there is nothing in it worth keeping; its outcome feeds
            // the webhook streak like a deletion-only payload does, so an unreachable Home
            // Assistant is one outage with one notification, not two.
            if (writeBack.active && !postedToSource) {
                val post = writeBack.sourcePost(heartbeatPayload())
                if (post != null) {
                    val heartbeat = sourceOnlyManager(post, "heartbeat").postData(post.payload)
                    SyncFailureNotifier.recordResult(
                        context, LogType.HEALTH_CONNECT, heartbeat.isSuccess, FailureReason.of(heartbeat.exceptionOrNull())
                    )
                    receive(writeBack, post, heartbeat)
                }
            }

            // A sync that only withdrew records did do something, so it must not report "no new
            // data": the user asked for a sync and one went out.
            if (!anyData && !deletionsDelivered) {
                if (quotaHit) {
                    // Not an outage: the quota refills within minutes and the next sync reads on
                    // from the stored watermarks, so it does not count towards the failure streak.
                    return Result.failure(Exception("Health Connect's read quota is used up for now; the next sync continues where this one stopped"))
                }
                if (healthConnectSilent) {
                    // Nothing was read because nothing could be; a run of these is an outage
                    // like an unreachable webhook, and the failure notifier treats it as one.
                    SyncFailureNotifier.recordResult(
                        context, LogType.HEALTH_CONNECT, false, context.getString(R.string.sync_failing_health_connect_silent)
                    )
                    return Result.failure(Exception("Health Connect did not answer for any data type; the next sync tries again"))
                }
                return Result.success(
                    if (writeBack.writtenTotal > 0) HealthSyncResult.Success(emptyMap(), writeBack.writtenTotal)
                    else HealthSyncResult.NoData
                )
            }

            // Publish the newest values to the user's MQTT broker (Home Assistant Discovery)
            // once per run, after draining: the last batch is the newest thanks to the
            // oldest-first cap. Failures never block the webhook sync; the outcome is stored
            // and shown in the MQTT settings section. Without a webhook the publish is the
            // delivery, and its outcome is the sync's (MqttSupport.syncFailure).
            val mqttResult = lastDelivered?.let { data ->
                val totalsForMqtt = if (publishToMqtt && !quotaHit) {
                    try {
                        // Yesterday and today, from the set this sync already asked for when it has one.
                        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
                        dailyTotalsOnce?.takeIf { preferencesManager.includeDailyTotals() }?.filter { it.date >= yesterday }
                            ?: healthConnectManager.readDailyTotals(days = 1, enabledTypes = enabledTypes)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else emptyList()
                MqttPublisher(context).publishHealthData(data, totalsForMqtt)
            }
            if (webhookUrls.isEmpty()) {
                val failure = MqttSupport.syncFailure(hasWebhooks = false, publish = mqttResult) { context.getString(R.string.mqtt_sync_failed, it) }
                SyncFailureNotifier.recordResult(context, LogType.HEALTH_CONNECT, failure == null, FailureReason.of(failure))
                SyncStatusStore.record(context, failure == null, if (failure == null) mqttOnlyRecords else 0, LogType.HEALTH_CONNECT)
                // The records stay read: the sensor values are cached, and the next publish
                // that reaches the broker carries them.
                if (failure != null) return Result.failure(failure)
                LifetimeStats.recordDelivery(context, mqttOnlyRecords, 0, LogType.HEALTH_CONNECT)
            }

            notQueued?.let { return Result.failure(it) }
            queuedRecords?.let {
                return Result.success(HealthSyncResult.Queued(it))
            }
            return Result.success(HealthSyncResult.Success(syncCounts, writeBack.writtenTotal, missedUrls, webhookCount))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Handles the source URL's answer to [sent] and, while the integration says more is
     * waiting, asks again in heartbeat form: at most [WriteBackPayload.MAX_FOLLOW_UPS] times
     * and only while the sync has time left, so a large backlog is drained over a few syncs
     * rather than in one long one.
     */
    private suspend fun receive(writeBack: WriteBackApplier, sent: SourcePost, outcome: Result<WebhookOutcome>) {
        var round = writeBack.handle(outcome, sent)
        var followUps = 0
        while (round.more && followUps < WriteBackPayload.MAX_FOLLOW_UPS && writeBack.hasTimeForFollowUp()) {
            followUps++
            val post = writeBack.sourcePost(heartbeatPayload()) ?: break
            round = writeBack.handle(sourceOnlyManager(post, "heartbeat").postData(post.payload), post)
        }
    }

    /** The heartbeat body (protocol section 3.2): timestamp, version and source, nothing else; the block is added per URL. */
    private fun heartbeatPayload(): String = buildJsonPayload(EMPTY_HEALTH_DATA)

    /**
     * A manager that talks to the source URL alone, for the heartbeat and the follow-up
     * requests. Those carry no records, so a successful one leaves no row in the log and no
     * mark in the statistics; what they fetched is the Receive row, and a failed one is logged.
     */
    private fun sourceOnlyManager(post: SourcePost, dataType: String) = WebhookManager(
        webhookUrls = listOf(post.url),
        context = context,
        dataType = dataType,
        recordCount = 0,
        logType = LogType.HEALTH_CONNECT,
        customHeaders = preferencesManager.getHealthWebhookHeaders(),
        urlsWithoutHeaders = preferencesManager.getHealthUrlsWithoutHeaders(),
        signingSecret = preferencesManager.getHealthWebhookSecret(),
        source = post,
        logSuccess = false
    )

    /**
     * Runs a backfill [job]: history sent to the configured webhooks, oldest window first, from
     * where the job stands. Runs in 3-day windows, each drained in capped chunks until
     * exhausted, so payloads stay bounded without silently dropping dense data past the
     * per-type cap. Deliberately independent of the sync watermarks: it never advances them,
     * and regular incremental syncs continue unaffected. Payloads carry "backfill": true plus
     * the window bounds so receivers can distinguish them; records still carry uuids, so
     * re-received overlaps deduplicate server-side. [onProgress] reports (completedWindows,
     * totalWindows) after every window.
     *
     * Every payload that went through is saved to [BackfillJobStore] before the next is read,
     * with the chunk cursor inside its window, and a job that finished is cleared there (P2-14):
     * a run that stops, for whatever reason, leaves the job right after its last delivered
     * payload, and the next run continues there, also halfway through a window too dense for one
     * run. It stops at the first failed delivery and at a window that could not be read in
     * full, and pauses when Health Connect's read quota is used up. Runs that deliver nothing
     * are counted, and after [BackfillJob.MAX_IDLE_RUNS] in a row the job fails instead of being
     * run again without end. When the enabled data types changed since the job started, it
     * starts over at the first window for the new ones and says so through [onRestarted].
     *
     * One row in the Logs tab per run, not one per chunk: the chunks that went through are not
     * logged, a chunk that failed is, per URL, and the run's row says how far it got.
     *
     * Holds the sync lock for its whole run. It draws the same sequence numbers a sync does,
     * and a sync beside it would interleave its payloads and counters with the backfill's; so a
     * backfill waits for a running sync, and a sync waits for the backfill. [onWaiting] says
     * true when the backfill has to wait, and false once it may start.
     */
    suspend fun runBackfill(
        job: BackfillJob,
        onWaiting: (Boolean) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onRestarted: () -> Unit = {}
    ): BackfillRun = withContext(Dispatchers.IO) {
        SYNC_LOCK.withLockReportingWait(onWaiting) {
            val progress = BackfillProgress(job)
            try {
                runBackfillLocked(progress, onProgress, onRestarted).also { run ->
                    logBackfillRun(progress, run.status, (run as? BackfillRun.Failed)?.failure)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped: by Android, which runs the work again, or by Stop, which cleared the
                // job so nothing is saved. Not a failure, but a stop before anything went
                // through counts towards giving up, and what was sent still gets its row.
                if (progress.chunks == 0) {
                    BackfillJobStore.saveIfCurrent(context, progress.job.afterIdleRun(System.currentTimeMillis()))
                }
                logBackfillRun(progress, STATUS_STOPPED, null)
                throw e
            }
        }
    }

    /** Where a run is: the job as last saved, and what this run delivered, for its row. */
    private class BackfillProgress(var job: BackfillJob, var sent: Int = 0, var chunks: Int = 0, var restarted: Boolean = false)

    private val BackfillRun.status: String
        get() = when (this) {
            is BackfillRun.Done -> STATUS_DONE
            is BackfillRun.QuotaUsedUp -> STATUS_PAUSED
            is BackfillRun.Failed -> STATUS_FAILED
        }

    private suspend fun runBackfillLocked(
        progress: BackfillProgress,
        onProgress: (Int, Int) -> Unit,
        onRestarted: () -> Unit
    ): BackfillRun {
        fun failed(failure: BackfillFailure) = BackfillRun.Failed(progress.job, progress.sent, failure)
        fun save(job: BackfillJob) {
            progress.job = job
            BackfillJobStore.saveIfCurrent(context, job)
        }

        val webhookUrls = preferencesManager.getHealthWebhookUrls()
        if (webhookUrls.isEmpty()) return failed(BackfillFailure.NoWebhook)
        val enabledTypes = preferencesManager.getHealthEnabledDataTypes()
        if (enabledTypes.isEmpty()) return failed(BackfillFailure.NoTypes)
        val totalWindows = progress.job.windowCount
        // Runs that sent nothing, such as stops before the first payload, counted on their way out.
        if (progress.job.givesUp) return failed(BackfillFailure.NoProgress(progress.job.nextWindow, totalWindows))

        // Types switched on or off since the job started: a window sent so far lacks a new type,
        // or carries one the receiver no longer gets, so the job starts over for the types now.
        val typeNames = BackfillJob.typeNames(enabledTypes)
        if (progress.job.types != typeNames) {
            save(progress.job.restartedFor(typeNames, System.currentTimeMillis()))
            progress.restarted = true
            onRestarted()
        }
        val typesByName = enabledTypes.associateBy { it.name }
        fun typesOf(names: Collection<String>) = names.mapNotNull { typesByName[it] }.toSet()
        fun namesOf(types: Collection<HealthDataType>) = types.map { it.name }.toSet()

        while (!progress.job.isFinished) {
            val (windowStart, windowEnd) = progress.job.window()
            val completed = progress.job.nextWindow
            val firstPass = progress.job.chunksSent + 1
            if (firstPass > MAX_PASSES_PER_BACKFILL_WINDOW) return failed(BackfillFailure.TooManyChunks(completed, totalWindows))

            // Exhaust the window before advancing (issue #39): one capped read per window
            // silently dropped everything past the cap for dense types. The cursor is a per-type
            // Watermark, so repeated reads walk the window chunk by chunk, also through
            // thousands of records that share one modification time. It is saved with the job
            // after every chunk, so a run that stops halfway continues with the next chunk.
            var cursor: Map<HealthDataType, Watermark?> =
                enabledTypes.associateWith { progress.job.chunkCursor[it.name]?.toWatermark() }
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
            // Later chunks read only the types still draining, as a sync's passes do (issue #73);
            // a type that could not be read in any chunk keeps the window from being complete.
            var draining: Set<HealthDataType>? = progress.job.draining?.let { typesOf(it) }
            val windowUnread = typesOf(progress.job.windowUnread).toMutableSet()
            var lastChunkRecords = 0
            // A bucketed type is read from bucket bound to bucket bound, so every window lies in
            // one chunk of days and is built whole there; reading it over the days' own bounds
            // split the window at each bound in two halves, the first of which was dropped
            // (P2-16). Its raw records are not sent, so its range can differ from the others'.
            val resolutions = preferencesManager.getSeriesResolutions()
            val bucketed = ResolutionApplier.wholeRequests(resolutions).keys
            val alignedWindows = bucketed.associateWith { type ->
                val resolution = resolutions.getValue(type)
                SeriesBucketing.alignDown(windowStart, resolution) to SeriesBucketing.alignDown(windowEnd, resolution)
            }.filterValues { (from, to) -> from.isBefore(to) }
            val wholeWindows = alignedWindows.mapValues { (type, range) -> WholeWindowRequest(resolutions.getValue(type), keepFrom = range.first) }
            for (pass in firstPass..MAX_PASSES_PER_BACKFILL_WINDOW) {
                val readResult = healthConnectManager.readHealthData(
                    draining ?: enabledTypes,
                    lastSyncTimestamps = cursor,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    wholeWindows = wholeWindows,
                    windowFor = alignedWindows
                )
                val healthData = readResult.getOrElse {
                    return failed(BackfillFailure.Read(it.message ?: it.javaClass.simpleName))
                }
                // Health Connect refused a read for its quota, and refuses every further one
                // for minutes (issue #73). What this chunk read is read again with it, so
                // nothing is sent: the job pauses after its last payload and WorkManager runs it
                // again after a backoff, until too many runs in a row sent nothing.
                if (healthData.quotaExhausted) {
                    val now = System.currentTimeMillis()
                    save(if (progress.chunks == 0) progress.job.afterIdleRun(now) else progress.job.copy(updatedAt = now))
                    return if (progress.job.givesUp) {
                        failed(BackfillFailure.NoProgress(completed, totalWindows))
                    } else {
                        BackfillRun.QuotaUsedUp(progress.job, progress.sent)
                    }
                }
                draining = healthData.cappedTypes
                windowUnread += healthData.unreadTypes
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
                //
                // A type that could not be read this pass (an error, a call that did not answer,
                // the read budget) came back empty, which says nothing about what the window
                // holds for it, so the window cannot be complete either: a receiver would drop
                // that type's records in the range. Unread types of an earlier run of the same
                // window come back with the job.
                val drained = healthData.cappedTypes.isEmpty() && windowUnread.isEmpty()
                val isLastChunk = healthData.cappedTypes.isEmpty() || pass == MAX_PASSES_PER_BACKFILL_WINDOW
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
                    // Every window of a bucketed type goes out whole in the first chunk, which reads
                    // the type's whole aligned range; later chunks only drain raw records.
                    resolved = ResolutionApplier.forBackfill(healthData, resolutions, emit = pass == 1),
                    sequence = preferencesManager.nextHealthSyncSequence()
                )
                val webhookManager = WebhookManager(
                    webhookUrls = webhookUrls,
                    context = context,
                    dataType = BACKFILL_DATA_TYPE,
                    recordCount = recordCount,
                    logType = LogType.HEALTH_CONNECT,
                    customHeaders = preferencesManager.getHealthWebhookHeaders(),
                    urlsWithoutHeaders = preferencesManager.getHealthUrlsWithoutHeaders(),
                    signingSecret = preferencesManager.getHealthWebhookSecret(),
                    // The run's row stands for the chunks that went through; a failed one keeps its own.
                    logSuccess = false,
                    countSuccess = true
                )
                val postResult = webhookManager.postData(payload)
                // A delivered backfill window counts as a sync on the dashboard: "today" and
                // "last sync" would otherwise say nothing while thousands of records went out.
                SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) recordCount else 0, LogType.HEALTH_CONNECT)
                if (postResult.isFailure) return failed(BackfillFailure.Delivery(completed, totalWindows))
                progress.sent += recordCount
                progress.chunks++

                // Sent, but not the whole window: stop here, the way a failed delivery does, so
                // the user learns it and a rerun sends the window again from its start (uuids
                // deduplicate), when the types that were missing may be read. The window is not
                // complete, so the job does not move past it (1.21.0).
                if (isLastChunk && !drained) {
                    save(progress.job.windowFromStart(recordCount, System.currentTimeMillis()))
                    return failed(
                        if (windowUnread.isNotEmpty()) {
                            BackfillFailure.NotReturned(completed, totalWindows, windowUnread.map { DeletionTracking.payloadKey(it) }.sorted())
                        } else {
                            BackfillFailure.TooManyChunks(completed, totalWindows)
                        }
                    )
                }
                if (isLastChunk) {
                    lastChunkRecords = recordCount
                    break
                }
                cursor = cursor + healthData.watermarks
                save(
                    progress.job.afterChunk(
                        records = recordCount,
                        cursor = cursor.mapNotNull { (type, mark) -> mark?.let { type.name to ChunkMark.of(it) } }.toMap(),
                        draining = namesOf(healthData.cappedTypes),
                        unread = namesOf(windowUnread),
                        now = System.currentTimeMillis()
                    )
                )
            }

            save(progress.job.afterWindow(lastChunkRecords, System.currentTimeMillis()))
            onProgress(progress.job.nextWindow, totalWindows)
        }
        BackfillJobStore.clear(context, progress.job.id)
        return BackfillRun.Done(progress.job, progress.sent)
    }

    /**
     * The one row of a backfill run in the Logs tab, in the user's language. A run that sent
     * nothing and neither finished, failed nor started over (stopped or paused right at its
     * start) leaves none, so a job that WorkManager runs again a few times does not fill the
     * tab with empty rows.
     */
    private fun logBackfillRun(progress: BackfillProgress, status: String, failure: BackfillFailure?) {
        val job = progress.job
        val sent = progress.sent
        if (sent == 0 && status != STATUS_DONE && status != STATUS_FAILED && !progress.restarted) return
        val res = context.resources
        val at = job.nextWindow + 1
        val note = listOfNotNull(
            if (progress.restarted) res.getString(R.string.health_backfill_log_restarted) else null,
            when (status) {
                STATUS_DONE -> res.getQuantityString(R.plurals.health_backfill_log_done, job.recordsSent, job.days, job.windowCount, job.recordsSent)
                STATUS_PAUSED -> res.getString(R.string.health_backfill_log_paused, at, job.windowCount)
                else -> res.getString(R.string.health_backfill_log_stopped, at, job.windowCount)
            }
        ).joinToString(" ")
        val summary = buildJsonObject {
            put("backfill", true)
            put("status", status)
            put("days", job.days)
            put("windows_done", job.nextWindow)
            put("window_count", job.windowCount)
            put("records_sent", sent)
            put("records_sent_by_job", job.recordsSent)
            put("restarted", progress.restarted)
            failure?.let { put("failure", it.code) }
        }.toString()
        preferencesManager.addWebhookLog(
            WebhookLog(
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                url = preferencesManager.getHealthWebhookUrls().firstOrNull() ?: BACKFILL_DATA_TYPE,
                statusCode = null,
                success = status != STATUS_FAILED,
                errorMessage = failure?.let { BackfillTexts.failure(res, it) },
                dataType = BACKFILL_RUN_DATA_TYPE,
                recordCount = sent,
                rawPayload = summary,
                logType = LogType.HEALTH_CONNECT.name,
                note = note
            )
        )
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
     * that reading already consumed, so the old one is worth nothing. Each type's deletions go
     * into storage just before its token does, and the caller keeps them there until a payload
     * has taken them, because this read cannot be repeated. The caller merges the returned
     * summary with storage too; the merge drops what is already there.
     */
    private suspend fun readDeletions(enabledTypes: Set<HealthDataType>, ownRecordIds: Set<String>): DeletionSummary {
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
            // Where this sync's read of the type starts, so the feed can name what changed
            // before it: the read below never sees those records (see OutsideWindow).
            val readFrom = LookbackWindow.of(Instant.ofEpochMilli(now), preferencesManager.getHealthCoveredUntil(type)).start
            val result = if (timeoutMs == 0L) {
                ChangesResult(error = "skipped: deletion budget spent")
            } else {
                withTimeoutOrNull(timeoutMs) { healthConnectManager.readDeletions(type, usable, ownRecordIds, readFrom) }
                    ?: ChangesResult(error = "timed out after $timeoutMs ms")
            }
            // An expired token is reported even when the app decided that itself, as long as
            // there was a token to expire; a first-ever token is not a gap, it is a start.
            val expiredHere = result.expired || (stored != null && usable == null)
            results[type] = result.copy(expired = expiredHere)
            // A type that errored keeps its token: the feed was not consumed, so the next sync
            // can read the same position again. A type that moves its token first puts what it
            // read into storage: the feed behind the old token is gone once the new one is
            // stored, so a worker stopped at a later type would otherwise lose these deletions
            // for good, without naming the type in deletions_unavailable either.
            //
            // A deletion stored by an earlier sync is dropped when the feed now reports the same
            // id written: the source wrote the record again under its old id, as Fitbit does
            // when it revises a night or a day of calories, and it exists (issues #71, #72).
            if (result.nextToken != null) {
                val part = mapOf(type to results.getValue(type))
                val found = DeletionTracking.summary(part)
                val stored = preferencesManager.getPendingDeletions()
                val next = stored.without(mapOf(DeletionTracking.payloadKey(type) to result.upserted)).merge(found)
                if (next != stored) preferencesManager.setPendingDeletions(next)
                preferencesManager.setHealthChangesToken(type, result.nextToken, now)
            }
        }

        return DeletionTracking.summary(results)
    }

    /**
     * Stores what [data] moved: the watermarks and the lookback anchors. [holdGapAnchors] is for
     * a pass that sends no payload to a webhook: a type whose range named a lookback gap keeps
     * its anchor then, so the gap is named again in the next payload instead of never (see
     * LookbackWindow.keepingGapsOpen). An MQTT-only setup has no payload to name it in and
     * publishes the newest values only, which a gap in older records does not change.
     */
    private fun updateSyncTimestamps(
        data: HealthData,
        syncCounts: MutableMap<HealthDataType, Int>,
        holdGapAnchors: Boolean = false,
        durable: Boolean = false
    ) {
        // Watermarks are the max metadata.lastModifiedTime of each delivered batch, so late
        // backfills and edits (old record timestamps, recent modification) are caught by the
        // next sync instead of being skipped forever.
        data.watermarks.forEach { (type, watermark) ->
            preferencesManager.setHealthWatermark(type, watermark, durable)
        }
        // After the watermarks: a stop in between leaves the older anchor, which only reads a
        // wider range than needed, never a narrower one.
        val anchors = if (holdGapAnchors) LookbackWindow.keepingGapsOpen(data.coveredUntil, data.diagnostics) else data.coveredUntil
        anchors.forEach { (type, until) ->
            preferencesManager.setHealthCoveredUntil(type, until, durable)
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
                            put("own_records_skipped", diag.ownRecordsSkipped)
                            put("read_from", diag.readFrom?.toString())
                            put("lookback_gap_from", diag.lookbackGapFrom?.toString())
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
