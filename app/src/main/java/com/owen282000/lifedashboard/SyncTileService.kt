package com.owen282000.lifedashboard

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/** Quick Settings tile that triggers an immediate sync of both categories. */
class SyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Sync Life Dashboard"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        SyncTrigger.enqueueImmediateSync(this)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }
}

/** Shared entry point for externally triggered syncs (tile, broadcast). */
object SyncTrigger {
    fun enqueueImmediateSync(context: android.content.Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
        workManager.enqueue(OneTimeWorkRequestBuilder<ScreenTimeSyncWorker>().build())
    }
}
