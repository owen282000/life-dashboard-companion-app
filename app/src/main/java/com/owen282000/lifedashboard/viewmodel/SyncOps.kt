package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStart
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.BackfillWork
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.ScreenTimeManager
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.ScreenTimeSyncResult
import com.owen282000.lifedashboard.WebhookManager
import com.owen282000.lifedashboard.WriteBackType
import kotlinx.coroutines.flow.Flow

/*
 * The side effects the tabs trigger (sync, preview, backfill, test ping, permission checks),
 * behind interfaces so the view models can be tested without Health Connect or a network.
 */

interface HealthOps {
    suspend fun availability(): HcAvailability
    suspend fun grantedPermissions(): Set<String>
    suspend fun sync(): Result<HealthSyncResult>
    suspend fun preview(): Result<String>

    /** The backfill as its WorkManager job reports it; it runs on when the screen is left (P2-14). */
    fun backfillStatus(): Flow<BackfillStatus>

    /** Starts a backfill of [days], or continues the stopped one of that length; nothing while one runs. */
    suspend fun startBackfill(days: Int): BackfillStart

    /** Stops the backfill and forgets where it was. */
    fun cancelBackfill()

    /** The stopped backfill that starting one of its length would continue, or null. */
    fun stoppedBackfill(): BackfillJob?
    suspend fun testPing(webhook: WebhookDraft): Result<Unit>

    /** Package names of other apps that wrote [type] to Health Connect in the last week (Receive, issue #62). */
    suspend fun otherSourcesWriting(type: WriteBackType): List<String>
}

interface ScreenTimeOps {
    fun hasUsageAccess(): Boolean
    suspend fun sync(): Result<ScreenTimeSyncResult>
    suspend fun preview(): Result<String>
    suspend fun testPing(webhook: WebhookDraft): Result<Unit>
}

private fun testPingPayload(source: String) =
    """{"test":true,"message":"Test ping from Life Dashboard Companion","timestamp":"${java.time.Instant.now()}","source":"$source"}"""

class RealHealthOps(private val context: Context) : HealthOps {
    override suspend fun availability(): HcAvailability = try {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE -> HcAvailability.NOT_INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.NEEDS_UPDATE
            else -> HcAvailability.UNAVAILABLE
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        HcAvailability.UNAVAILABLE
    }

    override suspend fun grantedPermissions(): Set<String> = try {
        HealthConnectManager(context).getGrantedPermissions()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptySet()
    }

    override suspend fun sync() = HealthSyncManager(context).performSync()
    override suspend fun preview() = HealthSyncManager(context).previewData()
    override suspend fun otherSourcesWriting(type: WriteBackType) = HealthConnectManager(context).otherSourcesWriting(type)
    override fun backfillStatus() = BackfillWork.status(context)
    override suspend fun startBackfill(days: Int) = BackfillWork.start(context, days)
    override fun cancelBackfill() = BackfillWork.cancel(context)
    override fun stoppedBackfill() = BackfillWork.stopped(context)

    override suspend fun testPing(webhook: WebhookDraft): Result<Unit> = try {
        WebhookManager(
            webhookUrls = webhook.urls,
            context = context,
            dataType = "test",
            recordCount = 0,
            logType = LogType.HEALTH_CONNECT,
            customHeaders = webhook.headers,
            urlsWithoutHeaders = webhook.urlsWithoutHeaders,
            signingSecret = webhook.secret.trim().ifBlank { null }
        ).postData(testPingPayload("health_connect")).map { }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

class RealScreenTimeOps(private val context: Context, private val prefs: PreferencesManager) : ScreenTimeOps {
    override fun hasUsageAccess() = ScreenTimeManager(context, prefs).hasPermission()
    override suspend fun sync() = ScreenTimeSyncManager(context).performSync()
    override suspend fun preview() = ScreenTimeSyncManager(context).previewData()

    override suspend fun testPing(webhook: WebhookDraft): Result<Unit> = try {
        WebhookManager(
            webhookUrls = webhook.urls,
            context = context,
            dataType = "test",
            recordCount = 0,
            logType = LogType.SCREEN_TIME,
            customHeaders = webhook.headers,
            urlsWithoutHeaders = webhook.urlsWithoutHeaders,
            signingSecret = webhook.secret.trim().ifBlank { null }
        ).postData(testPingPayload("screen_time")).map { }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
