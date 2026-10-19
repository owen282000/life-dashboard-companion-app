package com.owen282000.lifedashboard

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

/** One reading as the Logs tab shows it. Values are only kept when the user keeps full payloads. */
@Serializable
data class ReceiveLogLine(
    val entity: String?,
    val type: String?,
    val value: Double? = null,
    val diastolic: Double? = null,
    val unit: String? = null,
    val time: String? = null,
    /** "written", "skipped" or "failed". */
    val outcome: String,
    /** A failure code, or why a reading was skipped. */
    val reason: String? = null
) {
    companion object {
        const val WRITTEN = "written"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(lines: List<ReceiveLogLine>): String = json.encodeToString(ListSerializer(serializer()), lines)

        fun decode(text: String?): List<ReceiveLogLine>? =
            text?.let { runCatching { json.decodeFromString(ListSerializer(serializer()), it) }.getOrNull() }
    }
}

/** What one round of Receive did: how much it wrote, and whether the integration has more waiting. */
data class WriteBackRound(val written: Int = 0, val more: Boolean = false)

/**
 * Runs the receiving side of one sync (issue #62, section 8 of the protocol): puts the
 * `writeback` block into the request for the source URL, checks the response, writes what
 * passes into Health Connect under a time budget, and keeps the ledger, the acks and the
 * logs. The pure rules live in [WriteBackPayload] and [WriteBackLedger]; the Health Connect
 * calls in [HealthConnectManager]. This class only orders them and stores the outcome.
 *
 * One instance per sync: it remembers when it started, so the follow-up requests that drain
 * a backlog stop when the sync has spent its share of time.
 */
class WriteBackApplier(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val healthConnectManager: HealthConnectManager
) {
    private val settings = preferencesManager.getReceiveSettings()
    private val destinations = preferencesManager.getDestinations(LogType.HEALTH_CONNECT)

    /** The secret of the source destination, which its answers are verified with. */
    private val secret = destinations.firstOrNull { it.url == settings.sourceUrl }?.secret
    private val startedAt = System.currentTimeMillis()

    /** The URL whose response is read, or null when Receive is off or cannot run. */
    val sourceUrl: String? =
        settings.sourceUrl?.takeIf {
            WriteBackPayload.isActive(settings.enabled, it, destinations.map { d -> d.url }, secret)
        }

    val active: Boolean get() = sourceUrl != null

    /** The types the request asks for: switched on and, right now, permitted. Read once per sync. */
    private var requestedTypes: Set<WriteBackType>? = null

    /** How much this sync wrote across every round, for the line under Sync Now. */
    var writtenTotal: Int = 0
        private set

    private suspend fun requested(): Set<WriteBackType> {
        requestedTypes?.let { return it }
        // A type is only ever switched on with its permission in hand, so the switched-on set
        // is the last known answer when Health Connect does not give one now. A type whose
        // permission was revoked since is then asked for once more, and refused at write time
        // by a real answer, which is what the integration needs to hear.
        val granted = grantedNow() ?: settings.types
        return (settings.types intersect granted).also { requestedTypes = it }
    }

    /**
     * The types whose write permission Health Connect confirms right now, or null when it
     * did not answer in time or at all. Null is not "nothing granted": a dozing phone can
     * leave the permission lookup hanging, and reporting permission_denied on that would
     * make the integration drop every reading for good over a hiccup.
     */
    private suspend fun grantedNow(): Set<WriteBackType>? = try {
        withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { healthConnectManager.grantedWriteTypes() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * The payload for the source URL: [payload] with the `writeback` block added. The other
     * URLs of the section get [payload] as it is; the block is for the integration alone.
     */
    suspend fun sourcePost(payload: String): SourcePost? {
        val url = sourceUrl ?: return null
        val root = Json.parseToJsonElement(payload).jsonObject
        val block = WriteBackPayload.requestBlock(
            types = requested(),
            history = settings.olderMeasurements,
            report = preferencesManager.getWriteBackReport()
        )
        val withBlock = JsonObject(root + ("writeback" to block))
        return SourcePost(url, withBlock.toString())
    }

    /**
     * Handles what came back from the source URL. A request the source accepted has delivered
     * the acks it carried, so those are dropped from the stored report whatever the response
     * turns out to be; a request that failed keeps them for the next one.
     */
    suspend fun handle(outcome: Result<WebhookOutcome>, sent: SourcePost): WriteBackRound {
        val response = outcome.getOrNull()?.sourceResponse
        if (response == null) {
            // The source URL did not accept the request: nothing to read, nothing to ack away.
            // The webhook streak already counts that failure; counting it here too would make
            // one unreachable Home Assistant two notifications.
            return WriteBackRound()
        }
        preferencesManager.setWriteBackReport(preferencesManager.getWriteBackReport().without(sentReportOf(sent)))

        val verified = WriteBackPayload.verify(
            body = response.body,
            signatureHeader = response.signature,
            secret = secret ?: return WriteBackRound(),
            requestSignature = response.requestSignature ?: "",
            now = Instant.now(),
            oversized = response.oversized
        )
        return when (verified) {
            is WriteBackResponse.Incompatible -> {
                // An integration from before the protocol. The Receive row says so; a log row
                // on every sync would only repeat it, and it is not a failure of the phone.
                preferencesManager.setReceiveIntegrationOutdated(true)
                WriteBackRound()
            }
            is WriteBackResponse.Rejected -> {
                log(success = false, written = 0, lines = emptyList(), error = "Receive: response rejected (${verified.rejection.reason})")
                SyncFailureNotifier.recordReceiveResult(context, success = false)
                WriteBackRound()
            }
            is WriteBackResponse.Accepted -> {
                preferencesManager.setReceiveIntegrationOutdated(false)
                preferencesManager.setReceiveConfiguredTypes(verified.configured)
                apply(verified)
            }
        }
    }

    /** Whether another request to drain a `more: true` backlog still fits in this sync. */
    fun hasTimeForFollowUp(): Boolean = System.currentTimeMillis() - startedAt < FOLLOW_UP_BUDGET_MS

    /**
     * Writes the readings of an accepted response (section 8.1, step 4). Validation is per
     * reading; inserts are batched per type in one transactional call each, and the whole step
     * runs against one budget of [TOTAL_BUDGET_MS], like the deletion step, so Health Connect
     * not answering cannot hold the sync. A batch that does not fit is reported as
     * hc_unavailable and offered again by the integration next round.
     */
    private suspend fun apply(accepted: WriteBackResponse.Accepted): WriteBackRound {
        val stepStart = System.currentTimeMillis()
        val requested = requested()
        val granted = grantedNow()
        if (granted == null) {
            // No answer from Health Connect is hc_unavailable for everything offered: the
            // integration keeps the readings and offers them again next round. Only a real
            // answer that lacks a type may turn into permission_denied.
            val ids = accepted.readings.mapNotNull { (it["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            if (ids.isNotEmpty()) {
                preferencesManager.setWriteBackReport(
                    preferencesManager.getWriteBackReport()
                        .merge(WriteBackReport(failed = ids.map { FailedReading(it, WriteBackFailure.HC_UNAVAILABLE.code) }))
                )
                log(
                    success = false,
                    written = 0,
                    lines = ids.map {
                        ReceiveLogLine(entity = it.substringBeforeLast('@'), type = null, outcome = ReceiveLogLine.FAILED, reason = WriteBackFailure.HC_UNAVAILABLE.code)
                    },
                    error = "Receive: Health Connect did not answer the permission check"
                )
                SyncFailureNotifier.recordReceiveResult(context, success = false)
            }
            // No answer from Health Connect now means none a moment later either; the next sync asks again.
            return WriteBackRound(written = 0, more = false)
        }
        val now = Instant.now()
        val keepValues = preferencesManager.keepFullPayloads()

        var ledger = preferencesManager.getWriteBackLedger()
        var report = WriteBackReport.EMPTY
        val lines = mutableListOf<ReceiveLogLine>()
        var written = 0
        var roundFailed = false

        fun lineFor(reading: PendingReading, outcome: String, reason: String? = null) = ReceiveLogLine(
            entity = reading.entityId,
            type = reading.type.key,
            value = if (keepValues) reading.value else null,
            diastolic = if (keepValues) reading.diastolic else null,
            unit = reading.type.unit,
            time = reading.time.toString(),
            outcome = outcome,
            reason = reason
        )

        val ready = mutableListOf<PendingReading>()
        for (raw in accepted.readings) {
            when (val outcome = WriteBackPayload.validate(raw, requested, granted, now, settings.olderMeasurements)) {
                is ReadingOutcome.Ready -> ready += outcome.reading
                is ReadingOutcome.Refused -> {
                    outcome.id?.let { report = report.merge(WriteBackReport(failed = listOf(FailedReading(it, outcome.failure.code)))) }
                    if (outcome.failure == WriteBackFailure.PERMISSION_DENIED) roundFailed = true
                    lines += ReceiveLogLine(
                        entity = outcome.id?.substringBeforeLast('@'),
                        type = outcome.typeKey,
                        time = outcome.time?.toString(),
                        outcome = ReceiveLogLine.FAILED,
                        reason = outcome.failure.code
                    )
                }
            }
        }

        for (readings in ready.groupBy { it.type }.values) {
            val toWrite = mutableListOf<PendingReading>()
            for (reading in readings) {
                if (ledger.shouldWrite(reading.id, reading.version)) {
                    toWrite += reading
                } else {
                    // Known at this version or a higher one: acknowledged, not written twice.
                    report = report.merge(WriteBackReport(ack = listOf(reading.id)))
                    lines += lineFor(reading, ReceiveLogLine.SKIPPED, "already written")
                }
            }
            if (toWrite.isEmpty()) continue

            // A record Health Connect's own bounds refuse fails on its own, before the batch.
            val records = toWrite.mapNotNull { reading ->
                try {
                    reading to healthConnectManager.recordFor(reading)
                } catch (e: IllegalArgumentException) {
                    report = report.merge(WriteBackReport(failed = listOf(FailedReading(reading.id, WriteBackFailure.OUT_OF_RANGE.code))))
                    lines += lineFor(reading, ReceiveLogLine.FAILED, WriteBackFailure.OUT_OF_RANGE.code)
                    null
                }
            }
            if (records.isEmpty()) continue

            val remaining = TOTAL_BUDGET_MS - (System.currentTimeMillis() - stepStart)
            val failure: WriteBackFailure? = if (remaining <= 0) {
                WriteBackFailure.HC_UNAVAILABLE
            } else {
                try {
                    val ids = withTimeoutOrNull(remaining) { healthConnectManager.insertRecords(records.map { it.second }) }
                    if (ids == null) {
                        WriteBackFailure.HC_UNAVAILABLE
                    } else {
                        val writtenAt = System.currentTimeMillis()
                        ledger = ledger.recordAll(
                            records.mapIndexed { index, (reading, _) ->
                                WriteBackLedger.LedgerEntry(reading.id, reading.version, ids.getOrElse(index) { "" }, writtenAt)
                            }
                        )
                        report = report.merge(WriteBackReport(ack = records.map { it.first.id }))
                        records.forEach { (reading, _) -> lines += lineFor(reading, ReceiveLogLine.WRITTEN) }
                        written += records.size
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WriteBackPayload.failureFor(e)
                }
            }
            if (failure != null) {
                if (failure.retryable || failure == WriteBackFailure.PERMISSION_DENIED) roundFailed = true
                report = report.merge(WriteBackReport(failed = records.map { FailedReading(it.first.id, failure.code) }))
                records.forEach { (reading, _) -> lines += lineFor(reading, ReceiveLogLine.FAILED, failure.code) }
            }
        }

        preferencesManager.setWriteBackLedger(ledger)
        preferencesManager.setWriteBackReport(preferencesManager.getWriteBackReport().merge(report))
        writtenTotal += written
        if (written > 0) {
            SyncStatusStore.recordWritten(context, written)
            // Measurements that came in are a sync as much as records that went out: the
            // dashboard's "Last sync" moves, its count of records sent does not. A round that
            // wrote nothing leaves it alone, as a sync without new data does.
            SyncStatusStore.record(context, success = true, records = 0, source = LogType.HEALTH_CONNECT)
        }
        // Nothing offered is not a failure and not a success worth a row either.
        if (accepted.readings.isNotEmpty()) {
            log(success = !roundFailed, written = written, lines = lines, error = null)
            SyncFailureNotifier.recordReceiveResult(context, success = !roundFailed)
        }
        return WriteBackRound(written = written, more = WriteBackPayload.followUpAfter(accepted.more, roundFailed))
    }

    private fun log(success: Boolean, written: Int, lines: List<ReceiveLogLine>, error: String?) {
        preferencesManager.addWebhookLog(
            WebhookLog(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                url = sourceUrl ?: "",
                statusCode = null,
                success = success,
                errorMessage = error,
                dataType = "receive",
                recordCount = written,
                rawPayload = if (lines.isEmpty()) null else ReceiveLogLine.encode(lines),
                logType = LogType.HEALTH_CONNECT.name,
                direction = LogDirection.IN.name
            )
        )
    }

    /** The acks and failures that rode on [sent], read back out of the request that carried them. */
    private fun sentReportOf(sent: SourcePost): WriteBackReport = runCatching {
        val block = Json.parseToJsonElement(sent.payload).jsonObject["writeback"]?.jsonObject ?: return WriteBackReport.EMPTY
        Json { ignoreUnknownKeys = true }.decodeFromJsonElement(WriteBackReport.serializer(), block)
    }.getOrDefault(WriteBackReport.EMPTY)

    companion object {
        /** The write step's budget per response, like the deletion step's (DeletionTracking.TOTAL_BUDGET_MS). */
        const val TOTAL_BUDGET_MS = 20_000L

        /** A permission lookup that does not answer within this is no answer at all: hc_unavailable, never permission_denied. */
        const val PERMISSION_TIMEOUT_MS = 5_000L

        /** How long into a sync a follow-up request for a `more: true` backlog may still start. */
        const val FOLLOW_UP_BUDGET_MS = 60_000L
    }
}
