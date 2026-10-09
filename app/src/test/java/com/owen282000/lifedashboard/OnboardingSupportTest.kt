package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `an address without a host is not written`() {
        assertFalse(OnboardingSupport.writesWebhook(true, "http://", "", null))
        assertFalse(OnboardingSupport.writesWebhook(true, "example.org/hook", "", null))
    }

    @Test
    fun `the destination step waits for what a chosen destination needs`() {
        assertFalse(OnboardingSupport.destinationReady(true, "", false, ""))
        assertFalse(OnboardingSupport.destinationReady(true, "http://", false, ""))
        assertFalse(OnboardingSupport.destinationReady(false, "", true, " "))
        assertFalse(OnboardingSupport.destinationReady(true, "https://example.org/hook", true, ""))
        assertTrue(OnboardingSupport.destinationReady(true, "https://example.org/hook", false, ""))
        assertTrue(OnboardingSupport.destinationReady(false, "", true, "192.168.1.10"))
    }

    @Test
    fun `the destination step goes on with nothing chosen`() {
        assertTrue(OnboardingSupport.destinationReady(false, "", false, ""))
    }

    private val pairedHealthOnly = OnboardingSupport.WizardPairing(paired, setOf(PairingSource.HEALTH), seq = 1)

    @Test
    fun `a scanned address is shown for the sections the dialog wrote, not the wizard's`() {
        assertEquals(
            setOf(PairingSource.HEALTH),
            OnboardingSupport.webhookSections(true, true, paired.url, paired.secret, pairedHealthOnly)
        )
        val both = pairedHealthOnly.copy(written = setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME))
        assertEquals(both.written, OnboardingSupport.webhookSections(true, false, paired.url, paired.secret, both))
    }

    @Test
    fun `an address edited after the scan is shown for the wizard's sources`() {
        assertEquals(
            setOf(PairingSource.HEALTH, PairingSource.SCREEN_TIME),
            OnboardingSupport.webhookSections(true, true, "https://example.org/hook", paired.secret, pairedHealthOnly)
        )
        assertEquals(
            setOf(PairingSource.SCREEN_TIME),
            OnboardingSupport.webhookSections(false, true, "https://example.org/hook", "", null)
        )
    }

    @Test
    fun `pairing the same code again is a new event for the wizard`() {
        assertNotEquals(pairedHealthOnly, pairedHealthOnly.copy(seq = 2))
    }
}
