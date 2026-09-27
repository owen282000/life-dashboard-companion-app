package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPermissionRequestsTest {

    private val background = HealthConnectManager.BACKGROUND_PERMISSION
    private val history = HealthConnectManager.HISTORY_PERMISSION

    @Test
    fun `grant asks for the reads of the enabled types and nothing else they did not pick`() {
        val requested = HealthPermissionRequests.forGrant(OnboardingSupport.ESSENTIAL_TYPES)
        assertEquals(HealthPermissionRequests.readPermissions(OnboardingSupport.ESSENTIAL_TYPES) + background, requested)
        assertEquals(OnboardingSupport.ESSENTIAL_TYPES.size + 1, requested.size)
        assertFalse("android.permission.health.READ_SEXUAL_ACTIVITY" in requested)
        assertFalse("history is the backfill's to ask for", history in requested)
    }

    @Test
    fun `background reading always goes along because the tile and broadcast sync from a worker too`() {
        assertEquals(
            setOf("android.permission.health.READ_STEPS", background),
            HealthPermissionRequests.forGrant(setOf(HealthDataType.STEPS))
        )
    }

    @Test
    fun `no enabled type offers every read so the grant becomes the selection`() {
        val requested = HealthPermissionRequests.forGrant(emptySet())
        assertEquals(HealthConnectManager.ALL_PERMISSIONS - history, requested)
    }

    @Test
    fun `history goes with the enabled reads and never with background`() {
        val requested = HealthPermissionRequests.forHistory(setOf(HealthDataType.HEART_RATE))
        assertEquals(setOf("android.permission.health.READ_HEART_RATE", history), requested)
    }

    @Test
    fun `no request ever asks to write`() {
        val all = HealthPermissionRequests.forGrant(emptySet()) +
            HealthPermissionRequests.forHistory(HealthDataType.entries.toSet())
        assertTrue(all.none { it.startsWith("android.permission.health.WRITE_") })
    }
}
