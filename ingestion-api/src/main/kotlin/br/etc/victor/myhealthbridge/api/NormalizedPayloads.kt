package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The normalized payload shapes this API knows how to validate, selected by record type and mapper
 * version together.
 *
 * A record type nobody maps yet and a mapper version nobody knows are different failures: the first
 * means the vertical does not exist here, the second means this client is ahead of this server.
 */
object NormalizedPayloads {

    private val validators: Map<Pair<String, String>, (JsonObject) -> List<RejectionCode>> = mapOf(
        ("heart_rate" to "samsung-health-heart-rate/1") to ::validateHeartRate,
        ("exercise" to "samsung-health-exercise/1") to ::validateExercise,
    )

    private val recordTypes: Set<String> = validators.keys.mapTo(mutableSetOf()) { it.first }

    fun supports(recordType: String): Boolean = recordType in recordTypes

    fun validatorFor(recordType: String, mapperVersion: String): ((JsonObject) -> List<RejectionCode>)? =
        validators[recordType to mapperVersion]

    /** Beyond anything a wearable can plausibly report, so it catches mapper bugs, not arrhythmias. */
    private val PLAUSIBLE_BEATS_PER_MINUTE = BigDecimal.ONE..BigDecimal(500)

    private const val BEATS_PER_MINUTE = "/min"

    private fun validateHeartRate(payload: JsonObject): List<RejectionCode> {
        val heartRate = payload["heartRate"] as? JsonObject
            ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()

        val value = (heartRate["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.decimalOrNull()
        if (value == null || value !in PLAUSIBLE_BEATS_PER_MINUTE) codes += RejectionCode.INVALID_PAYLOAD

        val unit = (heartRate["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (unit != BEATS_PER_MINUTE) codes += RejectionCode.INVALID_UNIT

        return codes
    }

    /** Beyond anything a single exercise can plausibly report, for the reason the heart rate range is. */
    private val PLAUSIBLE_SECONDS = BigDecimal.ZERO..BigDecimal(604_800)
    private val PLAUSIBLE_METERS = BigDecimal.ZERO..BigDecimal(1_000_000)
    private val PLAUSIBLE_KILOCALORIES = BigDecimal.ZERO..BigDecimal(100_000)
    private val LATITUDE_DEGREES = BigDecimal(-90)..BigDecimal(90)
    private val LONGITUDE_DEGREES = BigDecimal(-180)..BigDecimal(180)

    private const val SECONDS = "s"
    private const val METERS = "m"
    private const val KILOCALORIES = "kcal"

    /** As long as the longest constant the pinned SDK names an exercise with, and no longer. */
    private const val MAX_EXERCISE_TYPE_LENGTH = 64

    /**
     * What the exercise was is the one thing required: a total the source never reported is absent
     * rather than zero, and an exercise indoors has no route at all.
     */
    private fun validateExercise(payload: JsonObject): List<RejectionCode> {
        val exercise = payload["exercise"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()

        val type = (exercise["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type.isNullOrEmpty() || type.length > MAX_EXERCISE_TYPE_LENGTH) codes += RejectionCode.INVALID_PAYLOAD

        codes += quantity(exercise["duration"], SECONDS, PLAUSIBLE_SECONDS)
        codes += quantity(exercise["distance"], METERS, PLAUSIBLE_METERS)
        codes += quantity(exercise["calories"], KILOCALORIES, PLAUSIBLE_KILOCALORIES)
        codes += route(exercise["route"])

        return codes
    }

    /** An absent quantity is nothing to reject; a present one has to be a measurement in its unit. */
    private fun quantity(node: JsonElement?, unit: String, plausible: ClosedRange<BigDecimal>): List<RejectionCode> {
        if (node == null) return emptyList()
        val quantity = node as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        val value = (quantity["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.decimalOrNull()
        if (value == null || value !in plausible) codes += RejectionCode.INVALID_PAYLOAD

        val reported = (quantity["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (reported != unit) codes += RejectionCode.INVALID_UNIT

        return codes
    }

    /**
     * A route states its units in the name of each key, so a point is checked against the range its
     * name promises rather than against a unit reported beside it.
     */
    private fun route(node: JsonElement?): List<RejectionCode> {
        if (node == null) return emptyList()
        val points = node as? JsonArray ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val valid = points.all { point ->
            val coordinates = point as? JsonObject ?: return@all false
            coordinates.degrees("latitudeDegrees", LATITUDE_DEGREES) &&
                coordinates.degrees("longitudeDegrees", LONGITUDE_DEGREES)
        }
        return if (valid) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }

    /** False when the point does not state the coordinate: a point without both is not a location. */
    private fun JsonObject.degrees(key: String, plausible: ClosedRange<BigDecimal>): Boolean {
        val degrees = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.decimalOrNull()
        return degrees != null && degrees in plausible
    }

    private fun JsonPrimitive.decimalOrNull(): BigDecimal? = runCatching { BigDecimal(content) }.getOrNull()
}
