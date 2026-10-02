package com.owen282000.lifedashboard.harness

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.owen282000.lifedashboard.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.Instant
import java.util.regex.Pattern

/**
 * Grants the app every Health Connect permission its (debug) manifest declares, reads, writes,
 * background and history, before a test runs. Once per process; later tests only pay a lookup.
 *
 * The route is the one Health Connect's own dialog takes: `grantHealthPermission` on the
 * platform's HealthConnectManager, called with the shell's MANAGE_HEALTH_PERMISSIONS. Unlike
 * `pm grant`, that also puts the app in Health Connect's priority list, and only apps in that
 * list count in SUM aggregates, so the daily totals are real. The method is hidden, so the
 * instrumentation runs with `--no-hidden-api-checks` (scripts/instrumented.sh also sets
 * `hidden_api_policy` as a second line).
 *
 * When that route fails on some module version, the fallback opens the dialog and accepts it
 * with UiAutomator, by resource id and switch state, never by text: a per-app locale turns
 * "Allow" into "Toestaan", while the ids stay. `-e hcGrantRoute dialog` forces the fallback,
 * to prove it still works.
 */
class HealthPermissionRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            ensureGranted()
            base.evaluate()
        }
    }

    companion object {
        private const val TAG = "LdSuite"

        @Volatile
        private var granted = false

        /** What the last grant used, for the log and the report: "shell", "dialog" or "already". */
        @Volatile
        var route: String = "none"
            private set

        @Synchronized
        fun ensureGranted() {
            if (granted) return
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val wanted = declaredHealthPermissions(context)
            val missing = wanted - grantedNow(context)
            if (missing.isEmpty()) {
                route = "already"
            } else {
                val forceDialog = InstrumentationRegistry.getArguments().getString("hcGrantRoute") == "dialog"
                val viaShell = !forceDialog && runCatching { grantViaShellIdentity(context, missing) }
                    .onFailure { Log.w(TAG, "grantHealthPermission failed, falling back to the dialog", it) }
                    .isSuccess
                route = if (viaShell && (wanted - grantedNow(context)).isEmpty()) "shell" else {
                    grantViaDialog(context, wanted - grantedNow(context))
                    "dialog"
                }
            }
            val stillMissing = wanted - grantedNow(context)
            check(stillMissing.isEmpty()) { "Health Connect permissions still missing after the $route route: $stillMissing" }
            proveWriteAccess(context)
            Log.i(TAG, "Health Connect permissions granted via $route (${wanted.size} permissions)")
            granted = true
        }

        /**
         * A write, to be sure the grants hold. On a fresh emulator Health Connect can refuse the
         * first writes after the very first grant ("Caller doesn't have WRITE_STEPS"), although
         * every permission reads as granted; revoking one permission and granting it again
         * settles it. That revoke cannot happen here: Android kills an app whose permission is
         * revoked, and the suite runs in the app's process, so the whole run ended as "Process
         * crashed" (PR #82). scripts/instrumented.sh runs [GrantForSuite] first, revokes from the
         * shell when this throws, and runs it again. The probe deletes nothing: it asks for the
         * app's steps in the first millisecond of 1970.
         */
        private fun proveWriteAccess(context: Context) {
            val client = HealthConnectClient.getOrCreate(context)
            val nothing = TimeRangeFilter.between(Instant.EPOCH, Instant.EPOCH.plusMillis(1))
            val refused = runCatching { runBlocking { client.deleteRecords(StepsRecord::class, nothing) } }
                .exceptionOrNull() as? SecurityException ?: return
            throw IllegalStateException(
                "Health Connect refuses writes although the permissions are granted; " +
                    "revoke one from the shell and run GrantForSuite again (scripts/instrumented.sh does)",
                refused
            )
        }

        /** Every android.permission.health.* the installed app requests, so the list follows the manifest. */
        fun declaredHealthPermissions(context: Context): Set<String> {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            return info.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }.toSet()
        }

        private fun grantedNow(context: Context): Set<String> = runBlocking {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }

        private fun grantViaShellIdentity(context: Context, permissions: Set<String>) {
            val manager = context.getSystemService(android.health.connect.HealthConnectManager::class.java)
            val grant = manager.javaClass.getMethod("grantHealthPermission", String::class.java, String::class.java)
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.MANAGE_HEALTH_PERMISSIONS")
            try {
                permissions.forEach { grant.invoke(manager, context.packageName, it) }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }

        private val ALLOW_ALL = Pattern.compile(".*:id/settingslib_main_switch_bar")
        private val SWITCH = Pattern.compile(".*:id/switchWidget")
        private val ALLOW = Pattern.compile(".*:id/primary_button_outline")
        private val ONBOARDING = Pattern.compile(".*:id/onboarding")
        private val GET_STARTED = Pattern.compile(".*:id/primary_button_full")

        private fun grantViaDialog(context: Context, permissions: Set<String>) {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Background and history are "additional" permissions that Health Connect only
                // offers once a data permission is held, so a second request may be needed.
                for (request in 1..3) {
                    val missing = permissions - grantedNow(context)
                    if (missing.isEmpty()) return
                    scenario.onActivity { it.requestPermissions(missing.toTypedArray(), 4713) }
                    for (round in 1..4) {
                        if ((permissions - grantedNow(context)).isEmpty()) return
                        // A device where Health Connect was never opened shows its onboarding first.
                        if (device.wait(Until.findObject(By.res(ONBOARDING)), 3_000) != null) {
                            device.findObject(By.res(GET_STARTED))?.click()
                            device.waitForIdle()
                        }
                        val dump = java.io.ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }
                        Witness.save("hc-dialog-$request-$round.xml", dump.toString())
                        device.wait(Until.findObject(By.res(ALLOW)), 10_000) ?: break
                        // The data screen has an "Allow all" switch; the screen for background and history
                        // access has one switch per permission and nothing to switch them all.
                        val allowAll = device.findObject(By.res(ALLOW_ALL))
                        if (allowAll != null) {
                            val toggle = allowAll.findObject(By.res(SWITCH)) ?: allowAll
                            if (!toggle.isChecked) toggle.click()
                        } else {
                            device.findObjects(By.res(SWITCH)).filter { !it.isChecked }.forEach { it.click() }
                        }
                        // "Allow" stays disabled until something is switched on.
                        device.wait(Until.findObject(By.res(ALLOW).enabled(true)), 5_000)?.click()
                        device.waitForIdle()
                    }
                }
            }
        }
    }
}
