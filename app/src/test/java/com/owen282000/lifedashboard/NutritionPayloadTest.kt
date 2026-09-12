package com.owen282000.lifedashboard

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.owen282000.lifedashboard.NutritionSupport.putNutrition
import com.owen282000.lifedashboard.NutritionSupport.toNutritionData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Full NutritionRecord export (issue #50): the four original keys stay unchanged, every other
 * nutrient is optional, null is omitted, a real zero is kept, and units follow the key suffix.
 */
class NutritionPayloadTest {

    private val start = Instant.parse("2026-09-06T09:52:00Z")
    private val end = Instant.parse("2026-09-06T09:53:00Z")

    private fun json(record: NutritionData) = buildJsonObject { putNutrition(record) }

    @Test
    fun legacyRecordEmitsExactlyTheOriginalKeys() {
        val record = NutritionData(141.0, 11.8, 1.85, 10.01, start, end, "com.cronometer", "uuid-1")
        val keys = json(record).keys
        assertEquals(
            setOf("calories", "protein_grams", "carbs_grams", "fat_grams", "start_time", "end_time", "uuid", "source"),
            keys
        )
    }

    @Test
    fun nullNutrientsAreOmittedAndZeroIsKept() {
        val record = NutritionData(
            null, null, null, null, start, end,
            details = NutritionDetails(transFatG = 0.0, sodiumMg = 180.0)
        )
        val obj = json(record)
        assertEquals(0.0, obj.getValue("trans_fat_g").jsonPrimitive.double, 0.0)
        assertEquals(180.0, obj.getValue("sodium_mg").jsonPrimitive.double, 0.0)
        assertFalse("null nutrient must be omitted", "sugars_g" in obj)
        assertFalse("null calories must be omitted", "calories" in obj)
    }

    @Test
    fun fullyPopulatedDetailsEmitEveryKey() {
        val full = NutritionDetails(
            1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0,
            17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0, 29.0, 30.0,
            31.0, 32.0, 33.0, 34.0, 35.0, 36.0, 37.0, 38.0
        )
        val obj = json(NutritionData(null, null, null, null, start, end, name = "Egg", mealType = "breakfast", details = full))
        assertEquals(38, NutritionDetails.JSON_KEYS.size)
        NutritionDetails.JSON_KEYS.forEach { assertTrue("missing $it", it in obj) }
        assertEquals("Egg", obj.getValue("name").jsonPrimitive.content)
        assertEquals("breakfast", obj.getValue("meal_type").jsonPrimitive.content)
    }

    @Test
    fun mapperConvertsUnitsAccordingToTheKeySuffix() {
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(Device(manufacturer = "Test", model = "Unit", type = Device.TYPE_PHONE)),
            energy = Energy.kilocalories(141.0),
            protein = Mass.grams(11.8),
            sodium = Mass.grams(0.850),
            vitaminD = Mass.grams(0.000010),
            selenium = Mass.grams(0.000055),
            dietaryFiber = Mass.grams(2.4),
            transFat = Mass.grams(0.0),
            name = "Example food",
            mealType = MealType.MEAL_TYPE_BREAKFAST
        )
        val data = record.toNutritionData()
        assertEquals(141.0, data.calories!!, 1e-9)
        assertEquals(11.8, data.protein!!, 1e-9)
        assertEquals(850.0, data.details.sodiumMg!!, 1e-6)
        assertEquals(10.0, data.details.vitaminDMcg!!, 1e-6)
        assertEquals(55.0, data.details.seleniumMcg!!, 1e-6)
        assertEquals(2.4, data.details.dietaryFibreG!!, 1e-9)
        assertEquals(0.0, data.details.transFatG!!, 0.0)
        assertEquals(null, data.details.sugarsG)
        assertEquals("Example food", data.name)
        assertEquals("breakfast", data.mealType)
        assertEquals(start, data.startTime)
        assertEquals(end, data.endTime)
    }

    @Test
    fun rarelyWrittenNutrientsSurviveTheFullMapperAndWriterPath() {
        // Regression guard for the four fields a user reported as absent: they must reach the
        // JSON whenever Health Connect actually carries them.
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(Device(manufacturer = "Test", model = "Unit", type = Device.TYPE_PHONE)),
            energyFromFat = Energy.kilocalories(90.0),
            chloride = Mass.grams(1.2),
            thiamin = Mass.grams(0.0011),
            folicAcid = Mass.grams(0.0002)
        )
        val obj = json(record.toNutritionData())
        assertEquals(90.0, obj.getValue("energy_from_fat_kcal").jsonPrimitive.double, 1e-6)
        assertEquals(1200.0, obj.getValue("chloride_mg").jsonPrimitive.double, 1e-6)
        assertEquals(1.1, obj.getValue("thiamin_mg").jsonPrimitive.double, 1e-6)
        assertEquals(200.0, obj.getValue("folic_acid_mcg").jsonPrimitive.double, 1e-6)
    }

    @Test
    fun everyNutritionKeyIsDeclaredInThePublishedSchema() {
        val schema = Json.parseToJsonElement(File("../docs/webhook-schema.json").readText()).jsonObject
        val declared = schema.getValue("properties").jsonObject
            .getValue("nutrition").jsonObject
            .getValue("items").jsonObject
            .getValue("properties").jsonObject.keys
        val emitted = NutritionDetails.JSON_KEYS + listOf(
            "calories", "protein_grams", "carbs_grams", "fat_grams", "name", "meal_type",
            "start_time", "end_time", "uuid", "source"
        )
        val missing = emitted.filter { it !in declared }
        assertTrue("schema missing nutrition keys: $missing", missing.isEmpty())
    }
}
