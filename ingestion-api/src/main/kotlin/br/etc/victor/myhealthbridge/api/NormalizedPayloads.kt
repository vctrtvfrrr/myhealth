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
        ("heart_rate" to "samsung-health-heart-rate/2") to ::validateRangedHeartRate,
        ("exercise" to "samsung-health-exercise/1") to ::validateExercise,
        ("sleep" to "samsung-health-sleep/1") to ::validateSleep,
        ("blood_oxygen" to "samsung-health-blood-oxygen/1") to ::validateBloodOxygen,
        ("skin_temperature" to "samsung-health-skin-temperature/1") to ::validateSkinTemperature,
        ("blood_glucose" to "samsung-health-blood-glucose/1") to ::validateBloodGlucose,
        ("nutrition" to "samsung-health-nutrition/1") to ::validateNutrition,
        ("water_intake" to "samsung-health-water-intake/1") to ::validateWaterIntake,
        ("floors_climbed" to "samsung-health-floors-climbed/1") to ::validateFloorsClimbed,
        ("irregular_heart_rhythm" to "samsung-health-irregular-heart-rhythm/1") to ::validateIrregularHeartRhythm,
        ("sleep_apnea" to "samsung-health-sleep-apnea/1") to ::validateSleepApnea,
        ("energy_score" to "samsung-health-energy-score/1") to ::validateEnergyScore,
    ) + VitalSignPayloads.validators

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
    internal fun quantity(node: JsonElement?, unit: String, plausible: ClosedRange<BigDecimal>): List<RejectionCode> {
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

    private val PERCENTAGE = BigDecimal.ZERO..BigDecimal(100)

    /** Beyond anything a skin sensor can plausibly report, for the reason the heart rate range is. */
    private val PLAUSIBLE_SKIN_CELSIUS = BigDecimal.ZERO..BigDecimal(60)

    /** Beyond anything a glucose meter can plausibly report, for the reason the heart rate range is. */
    private val PLAUSIBLE_GLUCOSE_MMOL_PER_LITER = BigDecimal.ZERO..BigDecimal(100)

    /** Samsung Health documents no unit for it, so the bound only catches what no dose could mean. */
    private val PLAUSIBLE_INSULIN = BigDecimal.ZERO..BigDecimal(1_000)

    private const val PERCENT = "%"
    private const val CELSIUS = "Cel"
    private const val MILLIMOLES_PER_LITER = "mmol/L"

    /** As long as the longest constant an SDK could plausibly name a glucose context with. */
    private const val MAX_GLUCOSE_CONSTANT_LENGTH = 64

    private fun validateRangedHeartRate(payload: JsonObject): List<RejectionCode> =
        rangedMeasurement(payload["heartRate"], "rate", BEATS_PER_MINUTE, PLAUSIBLE_BEATS_PER_MINUTE, "BeatsPerMinute")

    private fun validateBloodOxygen(payload: JsonObject): List<RejectionCode> =
        rangedMeasurement(payload["bloodOxygen"], "saturation", PERCENT, PERCENTAGE, "Percent")

    private fun validateSkinTemperature(payload: JsonObject): List<RejectionCode> =
        rangedMeasurement(payload["skinTemperature"], "temperature", CELSIUS, PLAUSIBLE_SKIN_CELSIUS, "Celsius")

    /**
     * The measurement is required and its range is not. Inside the series a bin states its unit in the
     * name of each key, like a point of a route, so each one is checked against the range its name
     * promises; the read model casts all of them.
     */
    private fun rangedMeasurement(
        node: JsonElement?,
        valueKey: String,
        unit: String,
        plausible: ClosedRange<BigDecimal>,
        binUnitSuffix: String,
    ): List<RejectionCode> {
        val measurement = node as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val value = measurement[valueKey] ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        codes += quantity(value, unit, plausible)
        codes += quantity(measurement["min"], unit, plausible)
        codes += quantity(measurement["max"], unit, plausible)

        val series = series(measurement["series"]) { bin ->
            bin.requiredInstant("start") &&
                bin.requiredInstant("end") &&
                bin.required("$valueKey$binUnitSuffix", plausible) &&
                bin.optional("min$binUnitSuffix", plausible) &&
                bin.optional("max$binUnitSuffix", plausible)
        }
        if (!series) codes += RejectionCode.INVALID_PAYLOAD

        return codes.distinct()
    }

    /**
     * The glucose level is required; how it was taken and how it relates to a meal are carried under the
     * constants the source names them with, so an unknown constant is accepted the way a sleep stage is.
     */
    private fun validateBloodGlucose(payload: JsonObject): List<RejectionCode> {
        val glucose = payload["bloodGlucose"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val level = glucose["glucose"] ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        codes += quantity(level, MILLIMOLES_PER_LITER, PLAUSIBLE_GLUCOSE_MMOL_PER_LITER)

        val context = listOf("measurementType", "sampleSourceType", "mealStatus").all { glucose.optionalConstant(it) } &&
            glucose.instant("mealTime") &&
            glucose.optional("insulinInjected", PLAUSIBLE_INSULIN) &&
            glucose.optionalFlag("medicationTaken")
        val series = series(glucose["series"]) { reading ->
            reading.requiredInstant("at") && reading.required("glucoseMmolPerLiter", PLAUSIBLE_GLUCOSE_MMOL_PER_LITER)
        }
        if (!context || !series) codes += RejectionCode.INVALID_PAYLOAD

        return codes.distinct()
    }

    /** A meal of a hundred kilograms of anything is beyond what any logged meal could mean, in every unit. */
    private val PLAUSIBLE_GRAMS = BigDecimal.ZERO..BigDecimal(100_000)
    private val PLAUSIBLE_MILLIGRAMS = BigDecimal.ZERO..BigDecimal(100_000_000)
    private val PLAUSIBLE_MICROGRAMS = BigDecimal.ZERO..BigDecimal(100_000_000_000)

    /** Beyond anything a person can plausibly drink at once, for the reason the heart rate range is. */
    private val PLAUSIBLE_MILLILITERS = BigDecimal.ZERO..BigDecimal(100_000)

    /** Samsung Health documents no unit for it, so the bound only catches what no climb could mean. */
    private val PLAUSIBLE_FLOORS = BigDecimal.ZERO..BigDecimal(10_000)

    private const val GRAMS = "g"
    private const val MILLIGRAMS = "mg"
    private const val MICROGRAMS = "ug"
    private const val MILLILITERS = "mL"

    /** Each nutrient a meal may carry, with the unit Samsung Health documents for it and its plausible range. */
    private val NUTRIENTS: Map<String, Pair<String, ClosedRange<BigDecimal>>> = mapOf(
        "calories" to (KILOCALORIES to PLAUSIBLE_KILOCALORIES),
        "totalFat" to (GRAMS to PLAUSIBLE_GRAMS),
        "saturatedFat" to (GRAMS to PLAUSIBLE_GRAMS),
        "polysaturatedFat" to (GRAMS to PLAUSIBLE_GRAMS),
        "monosaturatedFat" to (GRAMS to PLAUSIBLE_GRAMS),
        "transFat" to (GRAMS to PLAUSIBLE_GRAMS),
        "carbohydrate" to (GRAMS to PLAUSIBLE_GRAMS),
        "dietaryFiber" to (GRAMS to PLAUSIBLE_GRAMS),
        "sugar" to (GRAMS to PLAUSIBLE_GRAMS),
        "protein" to (GRAMS to PLAUSIBLE_GRAMS),
        "cholesterol" to (MILLIGRAMS to PLAUSIBLE_MILLIGRAMS),
        "sodium" to (MILLIGRAMS to PLAUSIBLE_MILLIGRAMS),
        "potassium" to (MILLIGRAMS to PLAUSIBLE_MILLIGRAMS),
        "vitaminA" to (MICROGRAMS to PLAUSIBLE_MICROGRAMS),
        "vitaminC" to (MILLIGRAMS to PLAUSIBLE_MILLIGRAMS),
        "calcium" to (MILLIGRAMS to PLAUSIBLE_MILLIGRAMS),
        "iron" to (MILLIGRAMS to PLAUSIBLE_MILLIGRAMS),
    )

    /**
     * A meal requires nothing beyond its own normalization: Samsung Health reports a nutrient only when it
     * was logged. The meal type is carried under the constant the source names it with, so an unknown one
     * is accepted the way a sleep stage is; the title is free text.
     */
    private fun validateNutrition(payload: JsonObject): List<RejectionCode> {
        val meal = payload["nutrition"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        NUTRIENTS.forEach { (key, expected) -> codes += quantity(meal[key], expected.first, expected.second) }
        if (!meal.optionalConstant("mealType") || !meal.optionalText("title")) codes += RejectionCode.INVALID_PAYLOAD

        return codes.distinct()
    }

    private fun validateWaterIntake(payload: JsonObject): List<RejectionCode> {
        val intake = payload["waterIntake"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val amount = intake["amount"] ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        return quantity(amount, MILLILITERS, PLAUSIBLE_MILLILITERS)
    }

    private fun validateFloorsClimbed(payload: JsonObject): List<RejectionCode> {
        val climbed = payload["floorsClimbed"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        return if (climbed.required("floors", PLAUSIBLE_FLOORS)) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }

    private fun JsonObject.optionalText(key: String): Boolean {
        val node = this[key] ?: return true
        return (node as? JsonPrimitive)?.isString == true
    }

    /** Samsung Health documents no scale for it, so the bound only catches what no score could mean. */
    private val PLAUSIBLE_ENERGY_SCORE = BigDecimal.ZERO..BigDecimal(1_000)

    /**
     * A notification is a fact by itself, so its status is optional: the source leaves it undefined at
     * times, and the notification still happened. An unknown constant is accepted the way a sleep stage is.
     */
    private fun validateIrregularHeartRhythm(payload: JsonObject): List<RejectionCode> =
        monitored(payload["irregularHeartRhythm"], "status")

    /** An assessment is a fact by itself, so its result is optional, for the reason a notification's status is. */
    private fun validateSleepApnea(payload: JsonObject): List<RejectionCode> = monitored(payload["sleepApnea"], "result")

    private fun monitored(node: JsonElement?, constant: String): List<RejectionCode> {
        val monitored = node as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        return if (monitored.optionalConstant(constant)) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }

    /** The score is the whole record, so it is required, the way a heart rate is; it states no unit. */
    private fun validateEnergyScore(payload: JsonObject): List<RejectionCode> {
        val energyScore = payload["energyScore"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        return if (energyScore.required("score", PLAUSIBLE_ENERGY_SCORE)) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }

    private fun series(node: JsonElement?, valid: (JsonObject) -> Boolean): Boolean {
        if (node == null) return true
        val entries = node as? JsonArray ?: return false
        return entries.all { entry -> (entry as? JsonObject)?.let(valid) ?: false }
    }

    private fun JsonObject.optionalConstant(key: String): Boolean {
        val node = this[key] ?: return true
        val text = (node as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return text.isNotEmpty() && text.length <= MAX_GLUCOSE_CONSTANT_LENGTH
    }

    internal fun JsonObject.optionalFlag(key: String): Boolean {
        val node = this[key] ?: return true
        return (node as? JsonPrimitive)?.takeIf { !it.isString }?.content in setOf("true", "false")
    }

    private fun JsonObject.requiredInstant(key: String): Boolean = this[key] != null && instant(key)

    internal fun JsonObject.optionalWhole(key: String, plausible: ClosedRange<BigDecimal>): Boolean {
        val node = this[key] ?: return true
        val value = (node as? JsonPrimitive)?.takeIf { !it.isString }?.decimalOrNull() ?: return false
        return value.stripTrailingZeros().scale() <= 0 && value in plausible
    }

    /** False when the point does not state the measurement: a point without both coordinates is not a location. */
    private fun JsonObject.required(key: String, plausible: ClosedRange<BigDecimal>): Boolean =
        this[key] != null && optional(key, plausible)

    internal fun JsonObject.optional(key: String, plausible: ClosedRange<BigDecimal>): Boolean {
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
