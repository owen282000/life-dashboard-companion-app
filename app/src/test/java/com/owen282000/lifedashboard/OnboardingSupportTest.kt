package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingSupportTest {

    @Test
    fun `ALL preset returns every data type`() {
        assertEquals(
            HealthDataType.entries.toSet(),
            OnboardingSupport.typesFor(OnboardingSupport.TypePreset.ALL)
        )
    }

    @Test
    fun `ESSENTIALS preset returns exactly the essential set`() {
        assertEquals(
            OnboardingSupport.ESSENTIAL_TYPES,
            OnboardingSupport.typesFor(OnboardingSupport.TypePreset.ESSENTIALS)
        )
    }

    @Test
    fun `LATER preset returns nothing`() {
        assertTrue(OnboardingSupport.typesFor(OnboardingSupport.TypePreset.LATER).isEmpty())
    }

    @Test
    fun `essential set is a strict subset of all types`() {
        assertTrue(HealthDataType.entries.toSet().containsAll(OnboardingSupport.ESSENTIAL_TYPES))
        assertTrue(OnboardingSupport.ESSENTIAL_TYPES.size < HealthDataType.entries.size)
    }

    @Test
    fun `essential set covers the everyday basics`() {
        val expected = setOf(
            HealthDataType.STEPS,
            HealthDataType.SLEEP,
            HealthDataType.HEART_RATE,
            HealthDataType.RESTING_HEART_RATE,
            HealthDataType.DISTANCE,
            HealthDataType.ACTIVE_CALORIES,
            HealthDataType.TOTAL_CALORIES,
            HealthDataType.WEIGHT
        )
        assertEquals(expected, OnboardingSupport.ESSENTIAL_TYPES)
    }

    @Test
    fun `steps include the data types screen only with Health Connect`() {
        val withHc = OnboardingSupport.stepsFor(healthConnect = true)
        val withoutHc = OnboardingSupport.stepsFor(healthConnect = false)
        assertEquals(5, withHc.size)
        assertEquals(4, withoutHc.size)
        assertTrue(withHc.contains(OnboardingSupport.Step.TYPES))
        assertTrue(!withoutHc.contains(OnboardingSupport.Step.TYPES))
        assertEquals(OnboardingSupport.Step.WELCOME, withHc.first())
        assertEquals(OnboardingSupport.Step.DONE, withHc.last())
        assertEquals(OnboardingSupport.Step.DONE, withoutHc.last())
    }

    private val paired = PairingLink(
        url = "http://192.168.10.138:8123/api/webhook/abc",
        secret = "s3cret",
        name = "Home Assistant",
        sources = setOf(PairingSource.HEALTH)
    )

    @Test
    fun `a scanned code is not written a second time on finish`() {
        assertFalse(OnboardingSupport.writesWebhook(true, paired.url, paired.secret, paired))
    }

    @Test
    fun `an address changed after the scan is written on finish`() {
        assertTrue(OnboardingSupport.writesWebhook(true, "https://example.org/hook", paired.secret, paired))
    }

    @Test
    fun `a typed address without a scan is written on finish`() {
        assertTrue(OnboardingSupport.writesWebhook(true, "https://example.org/hook", "", null))
    }

    @Test
    fun `nothing is written without the webhook card or an address`() {
        assertFalse(OnboardingSupport.writesWebhook(false, "https://example.org/hook", "", null))
        assertFalse(OnboardingSupport.writesWebhook(true, "", "", null))
    }
}
