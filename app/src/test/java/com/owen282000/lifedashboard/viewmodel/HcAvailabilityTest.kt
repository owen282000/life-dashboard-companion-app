package com.owen282000.lifedashboard.viewmodel

import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** P1-19: the Health tab's card says Install for a missing Health Connect and Update for an old one. */
class HcAvailabilityTest {

    @Test
    fun `a missing Health Connect app on Android 9 to 13 is not installed`() {
        assertEquals(
            HcAvailability.NOT_INSTALLED,
            hcAvailability(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) { false }
        )
    }

    @Test
    fun `an installed Health Connect app that the SDK cannot use needs an update`() {
        assertEquals(
            HcAvailability.NEEDS_UPDATE,
            hcAvailability(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) { true }
        )
    }

    @Test
    fun `a phone that cannot have Health Connect is unavailable, not missing`() {
        assertEquals(HcAvailability.UNAVAILABLE, hcAvailability(HealthConnectClient.SDK_UNAVAILABLE) { false })
        assertEquals(HcAvailability.UNAVAILABLE, hcAvailability(HealthConnectClient.SDK_UNAVAILABLE) { true })
        assertEquals(HcAvailability.UNAVAILABLE, hcAvailability(99) { true })
    }

    @Test
    fun `an available SDK does not look for the app`() {
        var looked = false
        assertEquals(HcAvailability.AVAILABLE, hcAvailability(HealthConnectClient.SDK_AVAILABLE) { looked = true; true })
        assertFalse(looked)
    }
}
