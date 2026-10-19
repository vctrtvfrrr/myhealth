package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class NutritionMapperTest {

    private val mapper = NutritionMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(nutritionRecord(uid = "samsung-uid-3"))

        assertEquals("samsung-uid-3", envelope.samsungUid)
        assertEquals("samsung-health-nutrition/1", envelope.mapperVersion)
    }

    @Test
    fun `reads a meal without an end as a period that starts and ends with it`() {
        val envelope = mapper.map(nutritionRecord(end = null))
        val period = (envelope.state as RecordState.Present).period

        assertEquals("2026-08-10T15:02:00Z", envelope.observedAt.instant)
        assertEquals("2026-08-10T15:00:00Z", period.start.instant)
        assertEquals("2026-08-10T15:00:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(nutritionRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `maps the same record identically on every import`() {
        assertEquals(mapper.map(nutritionRecord()), mapper.map(nutritionRecord()))
    }

    @Test
    fun `carries the meal, its title and its calories in kilocalories`() {
        val meal = mealOf(mapper.map(nutritionRecord(mealType = "AFTERNOON_SNACK", title = "Synthetic snack", calories = "180.5")))

        assertEquals("AFTERNOON_SNACK", text(meal, "mealType"))
        assertEquals("Synthetic snack", text(meal, "title"))
        assertEquals("180.5" to "kcal", quantity(meal, "calories"))
    }

    /** The units are the ones Samsung Health documents for each nutrient, in the notation every unit here uses. */
    @Test
    fun `normalizes every nutrient into the unit Samsung Health documents for it`() {
        val nutrients = mapOf(
            "total_fat" to ("totalFat" to "g"),
            "saturated_fat" to ("saturatedFat" to "g"),
            "polysaturated_fat" to ("polysaturatedFat" to "g"),
            "monosaturated_fat" to ("monosaturatedFat" to "g"),
            "trans_fat" to ("transFat" to "g"),
            "carbohydrate" to ("carbohydrate" to "g"),
            "dietary_fiber" to ("dietaryFiber" to "g"),
            "sugar" to ("sugar" to "g"),
            "protein" to ("protein" to "g"),
            "cholesterol" to ("cholesterol" to "mg"),
            "sodium" to ("sodium" to "mg"),
            "potassium" to ("potassium" to "mg"),
            "vitamin_c" to ("vitaminC" to "mg"),
            "calcium" to ("calcium" to "mg"),
            "iron" to ("iron" to "mg"),
            "vitamin_a" to ("vitaminA" to "ug"),
        )
        val meal = mealOf(mapper.map(nutritionRecord(nutrients = nutrients.keys.associateWith { "1.5" })))

        nutrients.values.forEach { (key, unit) -> assertEquals("1.5" to unit, quantity(meal, key), key) }
    }

    /** `UNDEFINED` is how the SDK says the source did not state it, which the read model shows as absent. */
    @Test
    fun `leaves out what the source left undefined or did not report`() {
        val meal = mealOf(mapper.map(nutritionRecord(mealType = "UNDEFINED", title = null, calories = null, nutrients = emptyMap())))

        assertEquals(emptySet<String>(), meal.keys)
    }

    @Test
    fun `preserves everything the source reported, enum constant included`() {
        val record = nutritionRecord(mealType = "UNDEFINED")
        val fields = (mapper.map(record).state as RecordState.Present).sourcePayload["fields"] as JsonObject

        assertEquals("UNDEFINED", (fields["meal_type"] as JsonPrimitive).content)
        assertEquals("Synthetic lunch", (fields["title"] as JsonPrimitive).content)
        assertEquals("780.0", (fields["sodium"] as JsonPrimitive).content)
    }

    @Test
    fun `knows every constant the pinned SDK names for the meal`() {
        listOf("UNDEFINED", "BREAKFAST", "LUNCH", "DINNER", "MORNING_SNACK", "AFTERNOON_SNACK", "EVENING_SNACK").forEach {
            assertEquals(emptyList<String>(), mapper.unknownEnums(nutritionRecord(mealType = it)), it)
        }
    }

    @Test
    fun `reports a constant it does not know while still carrying it`() {
        val record = nutritionRecord(mealType = "MIDNIGHT_SNACK")

        assertEquals(listOf("meal_type=MIDNIGHT_SNACK"), mapper.unknownEnums(record))
        assertEquals("MIDNIGHT_SNACK", text(mealOf(mapper.map(record)), "mealType"))
    }

    @Test
    fun `never reports the title, which is free text`() {
        assertEquals(emptyList<String>(), mapper.unknownEnums(nutritionRecord(title = "Not a constant")))
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-nutrition-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals("samsung-health-nutrition/1", envelope.mapperVersion)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun mealOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["nutrition"] as JsonObject

    private fun quantity(meal: JsonObject, key: String): Pair<String, String> {
        val quantity = meal[key] as JsonObject
        return text(quantity, "value") to text(quantity, "unit")
    }

    private fun text(node: JsonObject, key: String): String = (node[key] as JsonPrimitive).content
}
