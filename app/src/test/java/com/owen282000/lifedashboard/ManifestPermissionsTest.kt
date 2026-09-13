package com.owen282000.lifedashboard

import androidx.health.connect.client.permission.HealthPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val manifestText: String = File("src/main/AndroidManifest.xml").readText()

    private val declaredPermissions: Set<String> =
        Regex("""android:name="([^"]+)"""")
            .findAll(manifestText)
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

    /**
     * Play's permitted uses for broad package visibility (device search, antivirus, file
     * managers, browsers) do not cover usage tracking, so declaring QUERY_ALL_PACKAGES would
     * put the app at risk of removal. Screen time resolves labels only for packages that
     * UsageStatsManager already returned, which needs no extra visibility.
     */
    @Test
    fun queryAllPackagesIsNotDeclared() {
        assertFalse(
            "QUERY_ALL_PACKAGES must never be declared: Play does not permit it for usage " +
                "tracking. Resolve labels only for packages UsageStatsManager returned.",
            "android.permission.QUERY_ALL_PACKAGES" in declaredPermissions
        )
    }

    /**
     * The <queries> element must stay narrowly scoped to the Health Connect package. A broad
     * <intent> or <provider> query is functionally equivalent to QUERY_ALL_PACKAGES for review
     * purposes.
     */
    @Test
    fun queriesBlockOnlyTargetsHealthConnect() {
        val queriesBlock = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifestText)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        val packages = Regex("""<package\s+android:name="([^"]+)"""")
            .findAll(queriesBlock)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "<queries> must list only the Health Connect package",
            setOf("com.google.android.apps.healthdata"),
            packages
        )
        assertFalse(
            "<queries> must not use broad <intent> matching",
            queriesBlock.contains("<intent")
        )
        assertFalse(
            "<queries> must not use <provider> matching",
            queriesBlock.contains("<provider")
        )
    }

    /**
     * Secrets (webhook auth headers, HMAC signing secrets, MQTT credentials) and raw webhook
     * payloads (full health data, including cycle tracking) must never leave the device through
     * cloud backup or device transfer.
     */
    @Test
    fun backupRulesExcludeSecretsAndPayloads() {
        val excluded = listOf(
            "life_dashboard_secure_prefs.xml",
            "life_dashboard_logs.xml"
        )
        listOf(
            "src/main/res/xml/backup_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml"
        ).forEach { path ->
            val text = File(path).readText()
            // One <exclude> per include scope: full-backup has one, data-extraction has two
            // (cloud-backup and device-transfer).
            val includeCount = Regex("""<include\s+domain="sharedpref"""").findAll(text).count()
            excluded.forEach { file ->
                val excludeCount = Regex("""<exclude\s+domain="sharedpref"\s+path="$file"""")
                    .findAll(text)
                    .count()
                assertEquals(
                    "$path must exclude $file from every sharedpref include scope",
                    includeCount,
                    excludeCount
                )
            }
        }
    }
}
