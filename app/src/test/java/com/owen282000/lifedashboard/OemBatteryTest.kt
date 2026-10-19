package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.screens.OemBattery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which phones get a second button on the battery card, whatever case Build.MANUFACTURER uses. */
class OemBatteryTest {

    @Test
    fun vendorsWithAListOfTheirOwnGetTheirScreen() {
        assertEquals(OemBattery.SAMSUNG, OemBattery.forManufacturer("samsung"))
        assertEquals(OemBattery.XIAOMI, OemBattery.forManufacturer("Xiaomi"))
        assertEquals(OemBattery.XIAOMI, OemBattery.forManufacturer("POCO"))
        assertEquals(OemBattery.XIAOMI, OemBattery.forManufacturer("Redmi"))
        assertEquals(OemBattery.HUAWEI, OemBattery.forManufacturer("HONOR"))
    }

    @Test
    fun phonesThatUseAndroidsOwnSettingGetNone() {
        assertNull(OemBattery.forManufacturer("Google"))
        assertNull(OemBattery.forManufacturer("motorola"))
        assertNull(OemBattery.forManufacturer("OnePlus"))
    }
}
