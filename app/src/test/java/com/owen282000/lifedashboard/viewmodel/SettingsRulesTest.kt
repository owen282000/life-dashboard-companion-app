package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSectionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `a header needs a name that is an HTTP token and a value OkHttp sends`() {
        assertNull(SettingsRules.headerProblem("X-Api-Key", "abc"))
        assertNull(SettingsRules.headerProblem("Authorization", "Bearer abc.def"))
        assertNull(SettingsRules.headerProblem(" X-Trimmed ", " value "))
        assertEquals(UiMessage.HeaderNeedsNameAndValue, SettingsRules.headerProblem("", "abc"))
        assertEquals(UiMessage.HeaderNeedsNameAndValue, SettingsRules.headerProblem("X-Api-Key", "  "))
        assertEquals(UiMessage.InvalidHeaderName, SettingsRules.headerProblem("Bad Header", "abc"))
        assertEquals(UiMessage.InvalidHeaderName, SettingsRules.headerProblem("X-Key:", "abc"))
        assertEquals(UiMessage.InvalidHeaderName, SettingsRules.headerProblem("Schlüssel", "abc"))
        assertEquals(UiMessage.InvalidHeaderValue, SettingsRules.headerProblem("X-Api-Key", "a\r\nX-Injected: 1"))
        assertEquals(UiMessage.InvalidHeaderValue, SettingsRules.headerProblem("X-Api-Key", "caf\u00e9"))
    }

    private fun mqtt(enabled: Boolean, host: String, port: String, shared: Boolean = true): MqttDraft {
        val blank = MqttBroker("", 1883, false, null, null)
        val draft = MqttDraft(MqttSectionSettings(enabled, shared, blank, "lifedashboard"), blank, port)
        return draft.withActiveBroker(blank.copy(host = host))
    }

    @Test
    fun `an enabled broker needs a usable host and a port from 1 to 65535`() {
        assertNull(SettingsRules.mqttProblem(mqtt(true, "192.168.1.10", "1883")))
        assertNull(SettingsRules.mqttProblem(mqtt(true, "core-mosquitto", "8883", shared = false)))
        assertNull(SettingsRules.mqttProblem(mqtt(true, "[fd00::1]", "65535")))
        assertEquals(UiMessage.MqttHostMissing, SettingsRules.mqttProblem(mqtt(true, " ", "1883")))
        assertEquals(UiMessage.MqttHostInvalid, SettingsRules.mqttProblem(mqtt(true, "bad host", "1883")))
        assertEquals(UiMessage.MqttPortInvalid, SettingsRules.mqttProblem(mqtt(true, "broker.local", "0")))
        assertEquals(UiMessage.MqttPortInvalid, SettingsRules.mqttProblem(mqtt(true, "broker.local", "65536")))
        assertEquals(UiMessage.MqttPortInvalid, SettingsRules.mqttProblem(mqtt(true, "broker.local", "")))
    }

    @Test
    fun `a switched off broker shows its errors but never stops a save`() {
        assertNull(SettingsRules.mqttProblem(mqtt(false, "", "")))
        assertNull(SettingsRules.mqttProblem(mqtt(false, "bad host", "1883")))
        assertNull(SettingsRules.mqttProblem(mqtt(false, "broker.local", "0")))
        // The fields still say what is wrong once a host is typed.
        assertNull(SettingsRules.mqttHostProblem(mqtt(false, "", "")))
        assertEquals(UiMessage.MqttHostInvalid, SettingsRules.mqttHostProblem(mqtt(false, "bad host", "1883")))
        assertEquals(UiMessage.MqttPortInvalid, SettingsRules.mqttPortProblem(mqtt(false, "broker.local", "0")))
    }

    @Test
    fun `port text that is no port keeps the port the broker had`() {
        assertEquals(1883, mqtt(true, "broker.local", "0").withPort().activeBroker.port)
        assertEquals(8883, mqtt(true, "broker.local", "8883").withPort().activeBroker.port)
    }
}
