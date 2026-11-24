package com.owen282000.lifedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WeightUnitTest {

    private fun locale(tag: String) = Locale.forLanguageTag(tag)

    @Test
    fun `without a choice the region decides`() {
        assertTrue(WeightUnit.inPounds(locale("en-US")))
        assertTrue(WeightUnit.inPounds(locale("en-LR")))
        assertFalse(WeightUnit.inPounds(locale("en-GB")))
        assertFalse(WeightUnit.inPounds(locale("nl-NL")))
        assertFalse(WeightUnit.inPounds(locale("en")))
    }

    @Test
    fun `metric in Regional preferences wins over an American region`() {
        assertFalse(WeightUnit.inPounds(locale("en-US-u-ms-metric")))
    }

    @Test
    fun `an imperial choice wins over a metric region`() {
        assertTrue(WeightUnit.inPounds(locale("en-AU-u-ms-ussystem")))
        assertTrue(WeightUnit.inPounds(locale("en-GB-u-ms-uksystem")))
    }

    @Test
    fun `other extensions on the locale change nothing`() {
        assertTrue(WeightUnit.inPounds(locale("en-US-u-mu-celsius-fw-mon")))
        assertFalse(WeightUnit.inPounds(locale("de-DE-u-mu-fahrenhe")))
    }
}
