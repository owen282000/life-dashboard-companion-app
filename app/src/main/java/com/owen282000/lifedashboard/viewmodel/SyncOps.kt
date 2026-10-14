package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import com.owen282000.lifedashboard.AppChoice
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStart
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.BackfillWork
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.ScreenTimeManager
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.ScreenTimeSyncResult
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.TestPing
import com.owen282000.lifedashboard.WriteBackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/*
 * The side effects the tabs trigger (sync, preview, backfill, test ping, permission checks),
 * behind interfaces so the view models can be tested without Health Connect or a network.
 */

interface HealthOps {
    suspend fun availability(): HcAvailability
    suspend fun grantedPermissions(): Set<String>
    suspend fun sync(): Result<HealthSyncResult>

    /**
     * The payload the next sync would send for [types] at [resolutions], the Data Types and Data
     * Resolution on screen; nothing is stored.
     */
    suspend fun preview(types: Set<HealthDataType>, resolutions: Map<HealthDataType, SeriesResolution>): Result<String>

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

    /**
     * The apps the app filter's picker offers: the ones used over the last month, most used
     * first, then the ones in [listed] that were not used, by name, so a chosen app stays
     * visible and can be taken off the list again.
     */
    suspend fun appChoices(listed: Set<String>): List<AppChoice>
}

/** The Health Connect app on Android 9 to 13; from Android 14 it is part of the system. */
private const val HEALTH_CONNECT_PROVIDER = "com.google.android.apps.healthdata"

/**
 * What the Health tab's card says for an SDK status from HealthConnectClient.getSdkStatus.
 *
 * On Android 9 to 13 the SDK answers SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED both when the
 * Health Connect app is missing and when it is too old, so [providerInstalled] tells the two
 * apart: Install for the one, Update for the other. SDK_UNAVAILABLE is a phone that cannot
 * have Health Connect at all (Android 8, or a work profile on Android 14 and newer), where
 * neither would help.
 */
internal fun hcAvailability(sdkStatus: Int, providerInstalled: () -> Boolean): HcAvailability = when (sdkStatus) {
    HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
        if (providerInstalled()) HcAvailability.NEEDS_UPDATE else HcAvailability.NOT_INSTALLED
    else -> HcAvailability.UNAVAILABLE
}

class RealHealthOps(private val context: Context) : HealthOps {
    override suspend fun availability(): HcAvailability = try {
        hcAvailability(HealthConnectClient.getSdkStatus(context), ::providerInstalled)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        HcAvailability.UNAVAILABLE
    }

    /** Installed at all, old or disabled included; the manifest's queries make it visible. */
    private fun providerInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(HEALTH_CONNECT_PROVIDER, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(HEALTH_CONNECT_PROVIDER, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override suspend fun grantedPermissions(): Set<String> = try {
        HealthConnectManager(context).getGrantedPermissions()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptySet()
    }

    override suspend fun sync() = HealthSyncManager(context).performSync()
    override suspend fun preview(types: Set<HealthDataType>, resolutions: Map<HealthDataType, SeriesResolution>) =
        HealthSyncManager(context).previewData(types, resolutions)
    override suspend fun otherSourcesWriting(type: WriteBackType) = HealthConnectManager(context).otherSourcesWriting(type)
    override fun backfillStatus() = BackfillWork.status(context)
    override suspend fun startBackfill(days: Int) = BackfillWork.start(context, days)
    override fun cancelBackfill() = BackfillWork.cancel(context)
    override fun stoppedBackfill() = BackfillWork.stopped(context)

    override suspend fun testPing(webhook: WebhookDraft): Result<Unit> =
        TestPing.send(context, webhook.urls, webhook.secret, LogType.HEALTH_CONNECT, webhook.headers, webhook.urlsWithoutHeaders)
}

class RealScreenTimeOps(private val context: Context, private val prefs: PreferencesManager) : ScreenTimeOps {
    override fun hasUsageAccess() = ScreenTimeManager(context, prefs).hasPermission()
    override suspend fun sync() = ScreenTimeSyncManager(context).performSync()
    override suspend fun preview() = ScreenTimeSyncManager(context).previewData()

    override suspend fun testPing(webhook: WebhookDraft): Result<Unit> =
        TestPing.send(context, webhook.urls, webhook.secret, LogType.SCREEN_TIME, webhook.headers, webhook.urlsWithoutHeaders)

    override suspend fun appChoices(listed: Set<String>): List<AppChoice> = withContext(Dispatchers.IO) {
        val manager = ScreenTimeManager(context, prefs)
        val used = manager.recentApps()
        val known = used.map { it.packageName }.toSet()
        used + (listed - known).map { AppChoice(it, manager.appName(it), 0) }.sortedBy { it.name.lowercase() }
    }
}
