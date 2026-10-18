package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant

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
        ("sleep" to "samsung-health-sleep/1") to ::validateSleep,
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

    /** From the deepest place a person can be to above the highest, and a fix no worse than useless. */
    private val PLAUSIBLE_ALTITUDE_METERS = BigDecimal(-500)..BigDecimal(10_000)
    private val PLAUSIBLE_ACCURACY_METERS = BigDecimal.ZERO..BigDecimal(100_000)

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
     *
     * Every key a point may carry is checked, not only the ones that make it a location: the read model
     * casts each of them to the type its name promises, and a value that cannot be cast would be stored
     * as valid and then fail every query over the view rather than only the record that carried it.
     */
    private fun route(node: JsonElement?): List<RejectionCode> {
        if (node == null) return emptyList()
        val points = node as? JsonArray ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val valid = points.all { point ->
            val coordinates = point as? JsonObject ?: return@all false
            coordinates.required("latitudeDegrees", LATITUDE_DEGREES) &&
                coordinates.required("longitudeDegrees", LONGITUDE_DEGREES) &&
                coordinates.optional("altitudeMeters", PLAUSIBLE_ALTITUDE_METERS) &&
                coordinates.optional("accuracyMeters", PLAUSIBLE_ACCURACY_METERS) &&
                coordinates.instant("at")
        }
        return if (valid) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }

    /**
     * Samsung Health does not document the scale of its sleep score, so the bound only catches what no
     * scale could mean; the read model casts it to an integer, so a fraction is refused here.
     */
    private val PLAUSIBLE_SLEEP_SCORE = BigDecimal.ZERO..BigDecimal(1_000)

    /** As long as the longest constant an SDK could plausibly name a stage with, as the exercise type is. */
    private const val MAX_SLEEP_STAGE_LENGTH = 64

    /**
     * A night requires nothing beyond its own normalization: a duration, a score or a session the source
     * did not report is absent. What did arrive is checked down to each stage, because the read model
     * casts every key of a session and of a stage to the type its name promises.
     */
    private fun validateSleep(payload: JsonObject): List<RejectionCode> {
        val sleep = payload["sleep"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        codes += quantity(sleep["duration"], SECONDS, PLAUSIBLE_SECONDS)
        if (!sleep.optionalWhole("score", PLAUSIBLE_SLEEP_SCORE)) codes += RejectionCode.INVALID_PAYLOAD
        if (!sessions(sleep["sessions"])) codes += RejectionCode.INVALID_PAYLOAD

        return codes
    }

    private fun sessions(node: JsonElement?): Boolean {
        if (node == null) return true
        val sessions = node as? JsonArray ?: return false

        return sessions.all { element ->
            val session = element as? JsonObject ?: return@all false
            session.requiredInstant("start") &&
                session.requiredInstant("end") &&
                session.optional("durationSeconds", PLAUSIBLE_SECONDS) &&
                stages(session["stages"])
        }
    }

    private fun stages(node: JsonElement?): Boolean {
        if (node == null) return true
        val stages = node as? JsonArray ?: return false

        return stages.all { element ->
            val stage = element as? JsonObject ?: return@all false
            val name = (stage["stage"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            stage.requiredInstant("start") &&
                stage.requiredInstant("end") &&
                !name.isNullOrEmpty() &&
                name.length <= MAX_SLEEP_STAGE_LENGTH
        }
    }

    private fun JsonObject.requiredInstant(key: String): Boolean = this[key] != null && instant(key)

    private fun JsonObject.optionalWhole(key: String, plausible: ClosedRange<BigDecimal>): Boolean {
        val node = this[key] ?: return true
        val value = (node as? JsonPrimitive)?.takeIf { !it.isString }?.decimalOrNull() ?: return false
        return value.stripTrailingZeros().scale() <= 0 && value in plausible
    }

    /** False when the point does not state the measurement: a point without both coordinates is not a location. */
    private fun JsonObject.required(key: String, plausible: ClosedRange<BigDecimal>): Boolean =
        this[key] != null && optional(key, plausible)

    private fun JsonObject.optional(key: String, plausible: ClosedRange<BigDecimal>): Boolean {
        val node = this[key] ?: return true
        val value = (node as? JsonPrimitive)?.takeIf { !it.isString }?.decimalOrNull()
        return value != null && value in plausible
    }

    /** The instant the point was taken at, in the same UTC form every time on the wire is written in. */
    private fun JsonObject.instant(key: String): Boolean {
        val node = this[key] ?: return true
        val text = (node as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return text.endsWith("Z") && runCatching { Instant.parse(text) }.isSuccess
    }

    private fun JsonPrimitive.decimalOrNull(): BigDecimal? = runCatching { BigDecimal(content) }.getOrNull()
}
