package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Receive row's one-line summary says what is actually true, and names only what arrives. */
class ReceiveSummaryTest {

    private val on = ReceiveSettings(enabled = true, sourceUrl = "http://ha.local:8123/api/webhook/x")
    private val weight = WriteBackType.WEIGHT
    private val pressure = WriteBackType.BLOOD_PRESSURE
    private val offered = ReceiveStatus(configured = listOf(weight.key, pressure.key), answered = true)
    private val everything = setOf(weight.writePermission, pressure.writePermission)

    private fun summary(
        receive: ReceiveSettings = on,
        status: ReceiveStatus = offered,
        granted: Set<String> = emptySet(),
        available: Boolean = true
    ) = ReceiveSummary.of(receive, status, granted, available)

    @Test
    fun `off, or on without a source, reads as off`() {
        assertEquals(ReceiveSummary.Off, summary(receive = ReceiveSettings()))
        assertEquals(ReceiveSummary.Off, summary(receive = on.copy(sourceUrl = null)))
    }

    @Test
    fun `on with the secret or the integration url gone since asks to pair`() {
        val receiving = on.copy(types = setOf(weight))
        assertEquals(ReceiveSummary.NeedsPairing, summary(receive = receiving, granted = everything, available = false))
    }

    @Test
    fun `an integration too old to answer is said before any type is named`() {
        val receiving = on.copy(types = setOf(weight))
        val outdated = offered.copy(integrationOutdated = true)
        assertEquals(ReceiveSummary.IntegrationOutdated, summary(receive = receiving, status = outdated, granted = everything))
    }

    @Test
    fun `a signature lost on the way from an older integration is said before any type is named`() {
        val receiving = on.copy(types = setOf(weight))
        val lost = offered.copy(signatureLost = true)
        assertEquals(ReceiveSummary.SignatureLost, summary(receive = receiving, status = lost, granted = everything))
        assertEquals(ReceiveSummary.Off, summary(receive = ReceiveSettings(), status = lost))
    }

    @Test
    fun `before the first answer it waits for the types`() {
        assertEquals(ReceiveSummary.AwaitingTypes, summary(status = ReceiveStatus()))
    }

    @Test
    fun `an answer without types says nothing is mapped instead of waiting`() {
        assertEquals(ReceiveSummary.NothingMapped, summary(status = ReceiveStatus(answered = true)))
    }

    @Test
    fun `once the types are known and none is switched on it asks to choose one`() {
        assertEquals(ReceiveSummary.ChooseType, summary())
    }

    @Test
    fun `a switched on type without write permission is named as missing, not as received`() {
        assertEquals(ReceiveSummary.PermissionMissing(listOf(weight)), summary(receive = on.copy(types = setOf(weight))))
    }

    @Test
    fun `only granted and offered types are listed, with what was written today`() {
        val settings = on.copy(types = setOf(weight, pressure))
        val result = summary(receive = settings, status = offered.copy(writtenToday = 8), granted = setOf(pressure.writePermission))
        assertEquals(ReceiveSummary.Receiving(listOf(pressure), 8), result)
    }

    @Test
    fun `a type the integration no longer offers is left out`() {
        val settings = on.copy(types = setOf(weight, pressure))
        val onlyPressure = ReceiveStatus(configured = listOf(pressure.key), answered = true)
        assertEquals(ReceiveSummary.Receiving(listOf(pressure), 0), summary(receive = settings, status = onlyPressure, granted = everything))
    }
}
