package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.Nutrient
import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.floorsClimbed
import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.nutrition
import br.etc.victor.myhealthbridge.api.ConsumptionEnvelopes.waterIntake
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the API accepts as a meal, a water intake or a climb.
 *
 * The amount of water and the floors are required, because a record without them would be an invented
 * one. A meal requires nothing beyond its own normalization, since Samsung Health reports a nutrient
 * only when it was logged; what did arrive is checked, because the read model casts every nutrient to
 * the number its column promises.
 */
class ConsumptionPayloadTest {

    @Nested
    inner class Nutrition {

        private val validator = ItemValidator(ConsumptionEnvelopes.NUTRITION_RECORD_TYPE)

        @Test
        fun `accepts a synthetic meal with every nutrient Samsung Health documents`() {
            val nutrients = listOf(
                "totalFat" to "g", "saturatedFat" to "g", "polysaturatedFat" to "g", "monosaturatedFat" to "g",
                "transFat" to "g", "carbohydrate" to "g", "dietaryFiber" to "g", "sugar" to "g", "protein" to "g",
                "cholesterol" to "mg", "sodium" to "mg", "potassium" to "mg", "vitaminC" to "mg", "calcium" to "mg",
                "iron" to "mg", "vitaminA" to "ug",
            ).associate { (key, unit) -> key to Nutrient("1.5", unit) }

            accept(validator, nutrition(nutrients = nutrients))
        }

        @Test
        fun `accepts a meal the source reported nothing but its time for`() {
            accept(validator, nutrition(mealType = null, title = null, calories = null, nutrients = emptyMap()))
        }

        @Test
        fun `accepts a meal type it has never seen, carried under the constant the source named it with`() {
            accept(validator, nutrition(mealType = JsonPrimitive("MIDNIGHT_SNACK")))
        }

        @Test
        fun `rejects a meal the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, nutrition(withPayload = false)))
        }

        @Test
        fun `rejects what the read model could not read`() {
            listOf(
                nutrition(mealType = JsonPrimitive("")),
                nutrition(mealType = JsonPrimitive("X".repeat(65))),
                nutrition(mealType = JsonPrimitive(3)),
                nutrition(title = JsonPrimitive(42)),
                nutrition(calories = Nutrient("plenty", "kcal")),
                nutrition(calories = Nutrient("-1", "kcal")),
                nutrition(nutrients = mapOf("protein" to Nutrient("-0.5", "g"))),
                nutrition(nutrients = mapOf("sodium" to Nutrient("salty", "mg"))),
            ).forEach { item ->
                assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, item), "accepted $item")
            }
        }

        @Test
        fun `rejects a nutrient reported in a unit other than the one Samsung Health documents for it`() {
            listOf(
                nutrition(calories = Nutrient("640.0", "kJ")),
                nutrition(nutrients = mapOf("protein" to Nutrient("32.5", "mg"))),
                nutrition(nutrients = mapOf("sodium" to Nutrient("780.0", "g"))),
                nutrition(nutrients = mapOf("vitaminA" to Nutrient("120.0", "mg"))),
            ).forEach { item ->
                assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, item), "accepted $item")
            }
        }

        @Test
        fun `rejects a meal mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, nutrition(mapperVersion = "samsung-health-nutrition/2")),
            )
        }
    }

    @Nested
    inner class WaterIntake {

        private val validator = ItemValidator(ConsumptionEnvelopes.WATER_INTAKE_RECORD_TYPE)

        @Test
        fun `accepts a synthetic intake`() {
            accept(validator, waterIntake())
        }

        @Test
        fun `rejects an intake the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, waterIntake(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, waterIntake(amount = null)))
        }

        @Test
        fun `rejects an amount no intake can mean`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, waterIntake(amount = "-1")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, waterIntake(amount = "a glass")))
        }

        @Test
        fun `rejects an amount reported in another unit`() {
            assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, waterIntake(unit = "L")))
        }

        @Test
        fun `rejects an intake mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, waterIntake(mapperVersion = "samsung-health-water-intake/2")),
            )
        }
    }

    @Nested
    inner class FloorsClimbed {

        private val validator = ItemValidator(ConsumptionEnvelopes.FLOORS_CLIMBED_RECORD_TYPE)

        @Test
        fun `accepts a synthetic climb, fractions of a floor included`() {
            accept(validator, floorsClimbed(floors = "2.5"))
        }

        @Test
        fun `rejects a climb the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, floorsClimbed(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, floorsClimbed(floors = null)))
        }

        @Test
        fun `rejects a count no climb can mean`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, floorsClimbed(floors = "-1")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, floorsClimbed(floors = "many")))
        }

        @Test
        fun `rejects a climb mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, floorsClimbed(mapperVersion = "samsung-health-floors-climbed/2")),
            )
        }
    }

    private fun accept(validator: ItemValidator, item: JsonObject): ObservedEnvelope =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> validation.envelope
            is ItemValidation.Invalid -> error("expected an observation, got ${validation.codes}")
        }

    private fun reject(validator: ItemValidator, item: JsonObject): List<RejectionCode> =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> error("expected a rejection")
            is ItemValidation.Invalid -> validation.codes
        }
}
