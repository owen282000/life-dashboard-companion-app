package com.owen282000.lifedashboard

import androidx.health.connect.client.permission.HealthPermission
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against adding a HealthDataType without declaring its read permission in the
 * manifest: the permission dialog would silently omit the type and reads would fail.
 */
class ManifestPermissionsTest {

    @Test
    fun everyDataTypeHasItsReadPermissionDeclaredInTheManifest() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val missing = HealthDataType.entries.mapNotNull { type ->
            val permission = HealthPermission.getReadPermission(type.recordClass)
            if (permission !in manifest) "${type.name}: $permission" else null
        }
        assertTrue("Missing manifest permissions:\n${missing.joinToString("\n")}", missing.isEmpty())
    }
}
