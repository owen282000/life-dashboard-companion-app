package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Quick Settings tile that triggers an immediate sync of both categories, at most once a
 * minute together with the automation broadcast. A tap within that minute is ignored, and on
 * Android 10 and later the tile's subtitle says so.
 */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_sync_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = null
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val accepted = SyncTrigger.tryEnqueueImmediateSync(this, SystemClock.elapsedRealtime())
        qsTile?.apply {
            if (accepted) state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (accepted) null else getString(R.string.tile_sync_wait)
            }
            updateTile()
        }
    }
}

/** Shared entry point for externally triggered syncs (tile, broadcast). */
object SyncTrigger {
    /** Shared by the tile and the broadcast: a tile tap right after an automation is held back too. */
    private val rateLimit = SyncTriggerRateLimit()

    /**
     * Enqueues a sync of both categories unless a trigger was accepted less than
     * [SyncTriggerRateLimit.MIN_GAP_MILLIS] before [now]. False when this one is ignored.
     */
    fun tryEnqueueImmediateSync(context: Context, now: Long): Boolean {
        if (!rateLimit.tryAccept(now)) return false
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
        workManager.enqueue(OneTimeWorkRequestBuilder<ScreenTimeSyncWorker>().build())
        return true
    }
}
