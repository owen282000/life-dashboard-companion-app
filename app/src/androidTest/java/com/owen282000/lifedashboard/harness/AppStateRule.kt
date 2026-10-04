package com.owen282000.lifedashboard.harness

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.WriteBackType
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Puts the app back in a fresh state before every test, so no test depends on the order the
 * suite runs in. Also grants the Health Connect permissions (see [HealthPermissionRule]).
 *
 * Preferences are emptied with `clear().commit()` and never deleted as files: Android keeps
 * SharedPreferences in memory per process, and the application's PreferencesManager holds
 * them, the encrypted ones included (their keysets survive a clear). Files the app keeps (the
 * outbox, stored log payloads) are emptied; the test WorkManager is cancelled and pruned; the
 * app's own Health Connect records are deleted, which never touches another app's data.
 *
 * Afterwards it logs how many Health Connect calls the test made, see [HcCalls].
 */
class AppStateRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            HealthPermissionRule.ensureGranted()
            reset()
            HcCalls.reset()
            try {
                base.evaluate()
            } finally {
                Log.i(TAG, "${description.displayName}: ${HcCalls.total()} Health Connect calls ${HcCalls.snapshot()}")
            }
        }
    }

    companion object {
        private const val TAG = "LdSuite"
        private val PREFS = listOf("life_dashboard_prefs", "life_dashboard_logs", "life_dashboard_writeback", "hc_small_pages")
        private val FILE_DIRS = listOf("pending_sync", "webhook_payloads")

        @Volatile
        private var firstReset = true

        fun reset() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            WorkGate.open = false
            WorkManager.getInstance(context).apply {
                cancelAllWork().result.get()
                pruneWork().result.get()
            }
            deleteOwnHealthRecords(context)
            PREFS.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            PreferencesManager(context).clearAllSecrets()
            FILE_DIRS.forEach { dir -> File(context.filesDir, dir).listFiles()?.forEach { it.deleteRecursively() } }
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }

        /**
         * The first reset of a run deletes the app's records of every writable type, in case an
         * earlier run was killed halfway; later resets only the types a test wrote, since a
         * delete counts against Health Connect's write quota like an insert does.
         */
        private fun deleteOwnHealthRecords(context: Context) {
            val client = CountingHealthConnectClient(HealthConnectClient.getOrCreate(context))
            val types: Set<KClass<out Record>> = if (firstReset) {
                val granted = runBlocking { client.permissionController.getGrantedPermissions() }
                HcFixture.writableTypes(granted).toSet()
            } else {
                buildSet {
                    addAll(HcFixture.written)
                    // Receive wrote something when its ledger is not empty.
                    if (PreferencesManager(context).getWriteBackLedger().entries.isNotEmpty()) {
                        WriteBackType.entries.forEach { add(it.dataType.recordClass) }
                    }
                }
            }
            val everything = TimeRangeFilter.after(Instant.EPOCH)
            runBlocking { types.forEach { client.deleteRecords(it, everything) } }
            HcFixture.written.clear()
            firstReset = false
        }
    }
}
