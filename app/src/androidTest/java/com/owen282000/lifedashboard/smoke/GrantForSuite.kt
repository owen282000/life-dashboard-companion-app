package com.owen282000.lifedashboard.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.harness.HealthPermissionRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test: grants the app its Health Connect permissions and proves a write, before the
 * suite, in an instrumentation of its own. When Health Connect refuses that write after a
 * first grant, scripts/instrumented.sh revokes one permission from the shell, which kills
 * only this run, and starts it again; a revoke inside the suite would kill the suite with it.
 * The fixture app does the same in its own GrantForSuite. Excluded from the suite itself.
 */
@RunWith(AndroidJUnit4::class)
class GrantForSuite {

    @Test
    fun grantAndProveAWrite() {
        HealthPermissionRule.ensureGranted()
    }
}
