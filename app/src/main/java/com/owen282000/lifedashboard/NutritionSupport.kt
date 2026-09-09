package com.owen282000.lifedashboard

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * NutritionRecord to payload mapping (issue #50). Kept out of HealthConnectManager and
 * HealthSyncManager so the 40-odd nutrient fields live in one place and the JSON writer can be
 * unit tested without Android.
 */
object NutritionSupport {

    fun mealTypeToString(mealType: Int): String? = when (mealType) {
        MealType.MEAL_TYPE_BREAKFAST -> "breakfast"
        MealType.MEAL_TYPE_LUNCH -> "lunch"
        MealType.MEAL_TYPE_DINNER -> "dinner"
        MealType.MEAL_TYPE_SNACK -> "snack"
        MealType.MEAL_TYPE_UNKNOWN -> "unknown"
        else -> null
    }

    /**
     * Health Connect exposes nutrients as Mass; the unit accessors are explicit here so the
     * webhook key suffix (_g, _mg, _mcg) and the exported number can never disagree.
     */
    fun NutritionRecord.toNutritionData(): NutritionData = NutritionData(
        calories = energy?.inKilocalories,
        protein = protein?.inGrams,
        carbs = totalCarbohydrate?.inGrams,
        fat = totalFat?.inGrams,
        startTime = startTime,
        endTime = endTime,
        source = metadata.dataOrigin.packageName,
        uuid = metadata.id,
        name = name,
        mealType = mealTypeToString(mealType),
        details = NutritionDetails(
            energyFromFatKcal = energyFromFat?.inKilocalories,
            dietaryFibreG = dietaryFiber?.inGrams,
            sugarsG = sugar?.inGrams,
            saturatedFatG = saturatedFat?.inGrams,
            monounsaturatedFatG = monounsaturatedFat?.inGrams,
            polyunsaturatedFatG = polyunsaturatedFat?.inGrams,
            unsaturatedFatG = unsaturatedFat?.inGrams,
            transFatG = transFat?.inGrams,
            cholesterolMg = cholesterol?.inMilligrams,
            sodiumMg = sodium?.inMilligrams,
            potassiumMg = potassium?.inMilligrams,
            calciumMg = calcium?.inMilligrams,
            chlorideMg = chloride?.inMilligrams,
            chromiumMcg = chromium?.inMicrograms,
            copperMg = copper?.inMilligrams,
            iodineMcg = iodine?.inMicrograms,
            ironMg = iron?.inMilligrams,
            magnesiumMg = magnesium?.inMilligrams,
            manganeseMg = manganese?.inMilligrams,
            molybdenumMcg = molybdenum?.inMicrograms,
            phosphorusMg = phosphorus?.inMilligrams,
            seleniumMcg = selenium?.inMicrograms,
            zincMg = zinc?.inMilligrams,
            vitaminAMcg = vitaminA?.inMicrograms,
            vitaminB6Mg = vitaminB6?.inMilligrams,
            vitaminB12Mcg = vitaminB12?.inMicrograms,
            vitaminCMg = vitaminC?.inMilligrams,
            vitaminDMcg = vitaminD?.inMicrograms,
            vitaminEMg = vitaminE?.inMilligrams,
            vitaminKMcg = vitaminK?.inMicrograms,
            thiaminMg = thiamin?.inMilligrams,
            riboflavinMg = riboflavin?.inMilligrams,
            niacinMg = niacin?.inMilligrams,
            pantothenicAcidMg = pantothenicAcid?.inMilligrams,
            biotinMcg = biotin?.inMicrograms,
            folateMcg = folate?.inMicrograms,
            folicAcidMcg = folicAcid?.inMicrograms,
            caffeineMg = caffeine?.inMilligrams
        )
    )

    /**
     * Writes one nutrition record. The four original keys keep their names for backward
     * compatibility; every other field is optional and omitted when null, while a real zero
     * is written as a value.
     */
    fun JsonObjectBuilder.putNutrition(record: NutritionData) {
        record.calories?.let { put("calories", it) }
        record.protein?.let { put("protein_grams", it) }
        record.carbs?.let { put("carbs_grams", it) }
        record.fat?.let { put("fat_grams", it) }
        record.name?.let { put("name", it) }
        record.mealType?.let { put("meal_type", it) }
        record.details.fields.forEach { (key, value) -> value?.let { put(key, it) } }
        put("start_time", record.startTime.toString())
        put("end_time", record.endTime.toString())
        record.uuid?.let { put("uuid", it) }
        record.source?.let { put("source", it) }
    }
}
