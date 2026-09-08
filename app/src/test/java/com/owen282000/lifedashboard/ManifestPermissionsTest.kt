package com.owen282000.lifedashboard

import androidx.health.connect.client.permission.HealthPermission
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against adding a HealthDataType without declaring its read permission in the
 * manifest: the permission dialog would silently omit the type and reads would fail.
 *
 * Matching is against the EXACT set of declared android:name values, not a substring of the
 * manifest text: the substring check let the misspelled READ_HEART_RATE_VARIABILITY_RMSSD
 * satisfy the READ_HEART_RATE_VARIABILITY requirement, so HRV was never grantable (issue #40).
 */
class ManifestPermissionsTest {

    private val declaredPermissions: Set<String> =
        Regex("""android:name="([^"]+)"""")
            .findAll(File("src/main/AndroidManifest.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun everyDataTypeHasItsReadPermissionDeclaredInTheManifest() {
        val missing = HealthDataType.entries.mapNotNull { type ->
            val permission = HealthPermission.getReadPermission(type.recordClass)
            if (permission !in declaredPermissions) "${type.name}: $permission" else null
        }
        assertTrue("Missing manifest permissions:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun backgroundAndHistoryPermissionsAreDeclared() {
        assertTrue(HealthConnectManager.BACKGROUND_PERMISSION in declaredPermissions)
        assertTrue(HealthConnectManager.HISTORY_PERMISSION in declaredPermissions)
    }

    @Test
    fun allPermissionsCoversEveryDataType() {
        val missing = HealthDataType.entries.mapNotNull { type ->
            val permission = HealthPermission.getReadPermission(type.recordClass)
            if (permission !in HealthConnectManager.ALL_PERMISSIONS) "${type.name}: $permission" else null
        }
        assertTrue("ALL_PERMISSIONS is missing:\n${missing.joinToString("\n")}", missing.isEmpty())
    }
}
