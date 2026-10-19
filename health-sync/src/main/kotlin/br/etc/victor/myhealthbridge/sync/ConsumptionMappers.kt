package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one mapper of Samsung Health water intake records.
 *
 * An absent amount is emitted as absent rather than substituted, so the API rejects the item and it
 * stays a mapping pendency instead of becoming an invented intake.
 */
object WaterIntakeMapper : RecordMapper {

    override val version: String = "samsung-health-water-intake/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        buildJsonObject {
            val amount = record.fields[AMOUNT] as? SourceValue.Number ?: return@buildJsonObject
            put("waterIntake", buildJsonObject { put("amount", quantityOf(amount, MILLILITERS)) })
        },
    )

    private const val AMOUNT = "amount"
    private const val MILLILITERS = "mL"
}

/**
 * The one mapper of Samsung Health floors climbed records.
 *
 * Samsung Health documents no unit for the count, so none is claimed for it here: it is carried bare,
 * as the source reported it, and an absent one is left absent for the reason the water intake is.
 */
object FloorsClimbedMapper : RecordMapper {

    override val version: String = "samsung-health-floors-climbed/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        buildJsonObject {
            val floors = record.fields[FLOOR] as? SourceValue.Number ?: return@buildJsonObject
            put("floorsClimbed", buildJsonObject { put("floors", JsonPrimitive(floors.value)) })
        },
    )

    private const val FLOOR = "floor"
}

/**
 * The one mapper of Samsung Health nutrition records, one per meal logged.
 *
 * A meal requires nothing beyond its own normalization: Samsung Health reports a nutrient only when it
 * was logged, so each one the source did not report is absent rather than zero. The meal type is
 * carried under the constant the source names it with, except `UNDEFINED`, which is how the SDK says the
 * source did not state it.
 */
object NutritionMapper : RecordMapper {

    override val version: String = "samsung-health-nutrition/1"

    override val knownEnums: Map<String, Set<String>> = mapOf(
        MEAL_TYPE to setOf(UNDEFINED, "BREAKFAST", "LUNCH", "DINNER", "MORNING_SNACK", "AFTERNOON_SNACK", "EVENING_SNACK"),
    )

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        buildJsonObject {
            put(
                "nutrition",
                buildJsonObject {
                    (record.fields[MEAL_TYPE] as? SourceValue.Text)?.value?.takeIf { it != UNDEFINED }?.let { put("mealType", it) }
                    (record.fields[TITLE] as? SourceValue.Text)?.let { put("title", it.value) }
                    NUTRIENTS.forEach { (field, normalized) ->
                        val (key, unit) = normalized
                        (record.fields[field] as? SourceValue.Number)?.let { put(key, quantityOf(it, unit)) }
                    }
                },
            )
        },
    )

    private const val MEAL_TYPE = "meal_type"
    private const val TITLE = "title"
    private const val UNDEFINED = "UNDEFINED"

    private const val KILOCALORIES = "kcal"
    private const val GRAMS = "g"
    private const val MILLIGRAMS = "mg"
    private const val MICROGRAMS = "ug"

    /** Each source field with the key and unit it is normalized under, in the order the SDK declares them. */
    private val NUTRIENTS: List<Pair<String, Pair<String, String>>> = listOf(
        "calories" to ("calories" to KILOCALORIES),
        "total_fat" to ("totalFat" to GRAMS),
        "saturated_fat" to ("saturatedFat" to GRAMS),
        "polysaturated_fat" to ("polysaturatedFat" to GRAMS),
        "monosaturated_fat" to ("monosaturatedFat" to GRAMS),
        "trans_fat" to ("transFat" to GRAMS),
        "carbohydrate" to ("carbohydrate" to GRAMS),
        "dietary_fiber" to ("dietaryFiber" to GRAMS),
        "sugar" to ("sugar" to GRAMS),
        "protein" to ("protein" to GRAMS),
        "cholesterol" to ("cholesterol" to MILLIGRAMS),
        "sodium" to ("sodium" to MILLIGRAMS),
        "potassium" to ("potassium" to MILLIGRAMS),
        "vitamin_a" to ("vitaminA" to MICROGRAMS),
        "vitamin_c" to ("vitaminC" to MILLIGRAMS),
        "calcium" to ("calcium" to MILLIGRAMS),
        "iron" to ("iron" to MILLIGRAMS),
    )
}

private fun quantityOf(number: SourceValue.Number, unit: String): JsonObject = buildJsonObject {
    put("value", JsonPrimitive(number.value))
    put("unit", unit)
}
