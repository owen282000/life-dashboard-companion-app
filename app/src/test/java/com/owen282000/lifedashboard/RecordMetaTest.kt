package com.owen282000.lifedashboard

import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** P2-7: the `metadata` object a record carries with Record metadata on. */
class RecordMetaTest {

    private val start = Instant.parse("2026-10-04T06:00:00Z")
    private val plus2 = ZoneOffset.ofHours(2)
    private val watch = Device(manufacturer = "Google", model = "Pixel Watch 3", type = Device.TYPE_WATCH)

    @Test
    fun `an interval record carries its start and end offsets`() {
        val steps = StepsRecord(start, plus2, start.plusSeconds(60), ZoneOffset.ofHours(3), 12, Metadata.autoRecorded(watch, "steps-1", 4))
        val json = RecordMeta.of(steps).toJson()
        assertEquals("+02:00", json.getValue("start_zone_offset").jsonPrimitive.content)
        assertEquals("+03:00", json.getValue("end_zone_offset").jsonPrimitive.content)
        assertFalse(json.containsKey("zone_offset"))
        assertEquals("steps-1", json.getValue("client_record_id").jsonPrimitive.content)
        assertEquals(4L, json.getValue("client_record_version").jsonPrimitive.long)
        assertEquals("automatic", json.getValue("recording_method").jsonPrimitive.content)
        val device = json.getValue("device").jsonObject
        assertEquals("Google", device.getValue("manufacturer").jsonPrimitive.content)
        assertEquals("Pixel Watch 3", device.getValue("model").jsonPrimitive.content)
        assertEquals("watch", device.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `a record at one moment carries one offset`() {
        val weight = WeightRecord(start, plus2, Mass.kilograms(72.4), Metadata.manualEntry())
        val json = RecordMeta.of(weight).toJson()
        assertEquals("+02:00", json.getValue("zone_offset").jsonPrimitive.content)
        assertFalse(json.containsKey("start_zone_offset"))
        assertEquals("manual", json.getValue("recording_method").jsonPrimitive.content)
    }

    @Test
    fun `what Health Connect does not have is left out`() {
        val hrv = HeartRateVariabilityRmssdRecord(start, null, 42.0, Metadata.unknownRecordingMethod())
        val json = RecordMeta.of(hrv).toJson()
        assertFalse(json.containsKey("zone_offset"))
        assertFalse(json.containsKey("client_record_id"))
        assertFalse(json.containsKey("client_record_version"))
        assertFalse(json.containsKey("device"))
        assertEquals("unknown", json.getValue("recording_method").jsonPrimitive.content)
        assertEquals(setOf("last_modified", "recording_method"), json.keys)
    }

    @Test
    fun `a workout the user started counts as active`() {
        val night = SleepSessionRecord(start, plus2, start.plusSeconds(3600), plus2, Metadata.activelyRecorded(watch))
        assertEquals("active", RecordMeta.of(night).toJson().getValue("recording_method").jsonPrimitive.content)
    }

    @Test
    fun `every device type has a name`() {
        assertEquals("ring", RecordMeta.deviceTypeName(Device.TYPE_RING))
        assertEquals("chest_strap", RecordMeta.deviceTypeName(Device.TYPE_CHEST_STRAP))
        assertEquals("unknown", RecordMeta.deviceTypeName(Device.TYPE_UNKNOWN))
        assertEquals("unknown", RecordMeta.deviceTypeName(99))
    }
}
