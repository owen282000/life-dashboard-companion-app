package com.owen282000.lifedashboard.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {

    @Test
    fun `http and https addresses with a host are valid`() {
        assertTrue(SettingsRules.isValidUrl("https://ha.example.com/api/webhook/abc"))
        assertTrue(SettingsRules.isValidUrl("http://192.168.1.10:8123/api/webhook/abc"))
        assertTrue(SettingsRules.isValidUrl("http://[fd00::1]:8123/hook"))
        assertTrue(SettingsRules.isValidUrl("http://my_server.local/hook"))
        assertTrue(SettingsRules.isValidUrl("HTTPS://EXAMPLE.ORG/hook"))
    }

    @Test
    fun `surrounding spaces are ignored, as the tabs trim before saving`() {
        assertTrue(SettingsRules.isValidUrl("  https://example.org/hook "))
    }

    @Test
    fun `an address without a host is not valid`() {
        assertFalse(SettingsRules.isValidUrl("http://"))
        assertFalse(SettingsRules.isValidUrl("https://"))
        assertFalse(SettingsRules.isValidUrl("http:/"))
    }

    @Test
    fun `other schemes and loose text are not valid`() {
        assertFalse(SettingsRules.isValidUrl(""))
        assertFalse(SettingsRules.isValidUrl("   "))
        assertFalse(SettingsRules.isValidUrl("example.org/hook"))
        assertFalse(SettingsRules.isValidUrl("httpfoo"))
        assertFalse(SettingsRules.isValidUrl("ftp://example.org/hook"))
        assertFalse(SettingsRules.isValidUrl("mqtt://broker.local"))
    }
}
