package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic envelopes of a meal, a water intake and a climb.
 *
 * Every value is invented: the repository must never carry fixtures derived from real measurements.
 */
object ConsumptionEnvelopes {

    const val NUTRITION_RECORD_TYPE = "nutrition"
    const val NUTRITION_MAPPER_VERSION = "samsung-health-nutrition/1"
    const val WATER_INTAKE_RECORD_TYPE = "water_intake"
    const val WATER_INTAKE_MAPPER_VERSION = "samsung-health-water-intake/1"
    const val FLOORS_CLIMBED_RECORD_TYPE = "floors_climbed"
    const val FLOORS_CLIMBED_MAPPER_VERSION = "samsung-health-floors-climbed/1"

    /** A nutrient as the mapper normalizes it: its value beside the unit Samsung Health documents for it. */
    data class Nutrient(val value: String, val unit: String)

    fun nutrition(
        samsungUid: String = "uid-nutrition-1",
        mealType: JsonElement? = JsonPrimitive("LUNCH"),
        title: JsonElement? = JsonPrimitive("Synthetic lunch"),
        calories: Nutrient? = Nutrient("640.0", "kcal"),
        nutrients: Map<String, Nutrient> = mapOf("protein" to Nutrient("32.5", "g"), "sodium" to Nutrient("780.0", "mg")),
        mapperVersion: String = NUTRITION_MAPPER_VERSION,
        observedAt: String = "2026-08-10T15:02:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt) {
        if (withPayload) {
            putJsonObject("nutrition") {
                mealType?.let { put("mealType", it) }
                title?.let { put("title", it) }
                calories?.let { quantity("calories", it.value, it.unit) }
                nutrients.forEach { (key, nutrient) -> quantity(key, nutrient.value, nutrient.unit) }
            }
        }
    }

    fun waterIntake(
        samsungUid: String = "uid-water-intake-1",
        amount: String? = "250.0",
        unit: String = "mL",
        mapperVersion: String = WATER_INTAKE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T12:31:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt) {
        if (withPayload) {
            putJsonObject("waterIntake") { amount?.let { quantity("amount", it, unit) } }
        }
    }

    fun floorsClimbed(
        samsungUid: String = "uid-floors-climbed-1",
        floors: String? = "3.0",
        mapperVersion: String = FLOORS_CLIMBED_MAPPER_VERSION,
        observedAt: String = "2026-08-10T09:11:00Z",
        withPayload: Boolean = true,
        start: String = PERIOD_START,
        end: String = PERIOD_END,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, start, end) {
        if (withPayload) {
            putJsonObject("floorsClimbed") { floors?.let { put("floors", measurement(it)) } }
        }
    }

    private const val PERIOD_START = "2026-08-10T09:00:00Z"
    private const val PERIOD_END = "2026-08-10T09:10:00Z"

    private fun present(
        samsungUid: String,
        mapperVersion: String,
        observedAt: String,
        start: String = PERIOD_START,
        end: String = PERIOD_END,
        normalized: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", "-03:00")
        }
        put("mapperVersion", mapperVersion)
        putJsonObject("sourceProvenance") {
            putJsonObject("sourceApp") {
                put("kind", "known")
                put("id", "com.example.shealth")
            }
            putJsonObject("sourceDevice") {
                put("kind", "known")
                put("id", "device-1")
            }
        }
        putJsonObject("state") {
            put("kind", "present")
            putJsonObject("period") {
                putJsonObject("start") {
                    put("instant", start)
                    put("offset", "-03:00")
                }
                putJsonObject("end") {
                    put("instant", end)
                    put("offset", "-03:00")
                }
            }
            putJsonObject("sourcePayload") { putJsonObject("fields") {} }
            putJsonObject("normalizedPayload", normalized)
        }
    }

    /** A number when it is one, so a test can also state what a defective mapper would have put there. */
    private fun measurement(value: String): JsonPrimitive =
        value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

    private fun JsonObjectBuilder.quantity(name: String, value: String, unit: String) {
        putJsonObject(name) {
            put("value", measurement(value))
            put("unit", unit)
        }
    }
}
