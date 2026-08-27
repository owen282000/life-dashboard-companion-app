package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards docs/webhook-schema.json against drifting from the app: every payload array the app
 * can emit must be declared in the published schema, so integrators can rely on it.
 */
class WebhookSchemaTest {

    @Test
    fun schemaCoversEveryPayloadKeyTheAppEmits() {
        val schemaFile = File("../docs/webhook-schema.json")
        assertTrue("schema file missing", schemaFile.exists())
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val declaredKeys = schema.getValue("properties").jsonObject.keys

        val payloadKeys = listOf(
            "steps", "sleep", "heart_rate", "distance", "active_calories", "total_calories",
            "weight", "height", "blood_pressure", "blood_glucose", "oxygen_saturation",
            "body_temperature", "respiratory_rate", "resting_heart_rate", "exercise",
            "hydration", "nutrition", "mindfulness", "body_fat", "lean_body_mass",
            "bone_mass", "body_water_mass", "heart_rate_variability", "menstruation_period",
            "menstruation_flow", "basal_metabolic_rate", "vo2_max", "skin_temperature",
            "basal_body_temperature", "intermenstrual_bleeding", "ovulation_test",
            "cervical_mucus", "sexual_activity", "screen_time"
        )
        // One array per HealthDataType (blood pressure is one array) plus screen_time.
        org.junit.Assert.assertEquals(HealthDataType.entries.size + 1, payloadKeys.size)

        val missing = payloadKeys.filter { it !in declaredKeys }
        assertTrue("schema missing keys: $missing", missing.isEmpty())
    }
}
