package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.NormalizedPayloads.optional
import br.etc.victor.myhealthbridge.api.NormalizedPayloads.optionalFlag
import br.etc.victor.myhealthbridge.api.NormalizedPayloads.optionalWhole
import br.etc.victor.myhealthbridge.api.NormalizedPayloads.quantity
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

/**
 * The normalized payloads of blood pressure, body composition and body temperature: readings of plain
 * fields, where what Samsung Health requires is required and everything else is checked only when it
 * arrived, since the read model casts every key to the type its name promises.
 */
internal object VitalSignPayloads {

    val validators: Map<Pair<String, String>, (JsonObject) -> List<RejectionCode>> = mapOf(
        ("blood_pressure" to "samsung-health-blood-pressure/1") to ::validateBloodPressure,
        ("body_composition" to "samsung-health-body-composition/1") to ::validateBodyComposition,
        ("body_temperature" to "samsung-health-body-temperature/1") to ::validateBodyTemperature,
    )

    /** Beyond anything a cuff can plausibly report, for the reason the heart rate range is. */
    private val PLAUSIBLE_MILLIMETERS_OF_MERCURY = BigDecimal.ZERO..BigDecimal(400)
    private val PLAUSIBLE_PULSE_RATE = BigDecimal.ONE..BigDecimal(500)

    private val PLAUSIBLE_KILOGRAMS = BigDecimal.ZERO..BigDecimal(1_000)
    private val PLAUSIBLE_CENTIMETERS = BigDecimal.ZERO..BigDecimal(300)
    private val PERCENTAGE = BigDecimal.ZERO..BigDecimal(100)
    private val PLAUSIBLE_KILOCALORIES_PER_DAY = BigDecimal.ZERO..BigDecimal(20_000)
    private val PLAUSIBLE_LITERS = BigDecimal.ZERO..BigDecimal(1_000)
    private val PLAUSIBLE_BODY_MASS_INDEX = BigDecimal.ZERO..BigDecimal(1_000)

    /** Beyond anything a body can plausibly have, for the reason the heart rate range is. */
    private val PLAUSIBLE_BODY_CELSIUS = BigDecimal.ZERO..BigDecimal(60)

    private const val MILLIMETERS_OF_MERCURY = "mm[Hg]"
    private const val KILOGRAMS = "kg"
    private const val CENTIMETERS = "cm"
    private const val PERCENT = "%"
    private const val KILOCALORIES_PER_DAY = "kcal/d"
    private const val LITERS = "L"
    private const val CELSIUS = "Cel"

    private val bodyCompositionMeasures: Map<String, Pair<String, ClosedRange<BigDecimal>>> = mapOf(
        "height" to (CENTIMETERS to PLAUSIBLE_CENTIMETERS),
        "bodyFat" to (PERCENT to PERCENTAGE),
        "skeletalMuscle" to (PERCENT to PERCENTAGE),
        "muscleMass" to (PERCENT to PERCENTAGE),
        "basalMetabolicRate" to (KILOCALORIES_PER_DAY to PLAUSIBLE_KILOCALORIES_PER_DAY),
        "bodyFatMass" to (KILOGRAMS to PLAUSIBLE_KILOGRAMS),
        "fatFreeMass" to (KILOGRAMS to PLAUSIBLE_KILOGRAMS),
        "fatFree" to (PERCENT to PERCENTAGE),
        "skeletalMuscleMass" to (KILOGRAMS to PLAUSIBLE_KILOGRAMS),
        "totalBodyWater" to (LITERS to PLAUSIBLE_LITERS),
    )

    /** Samsung Health requires both pressures; the pulse rate is a whole number, as the SDK types it. */
    private fun validateBloodPressure(payload: JsonObject): List<RejectionCode> {
        val pressure = payload["bloodPressure"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val systolic = pressure["systolic"] ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val diastolic = pressure["diastolic"] ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        codes += quantity(systolic, MILLIMETERS_OF_MERCURY, PLAUSIBLE_MILLIMETERS_OF_MERCURY)
        codes += quantity(diastolic, MILLIMETERS_OF_MERCURY, PLAUSIBLE_MILLIMETERS_OF_MERCURY)
        codes += quantity(pressure["mean"], MILLIMETERS_OF_MERCURY, PLAUSIBLE_MILLIMETERS_OF_MERCURY)
        if (!pressure.optionalWhole("pulseRate", PLAUSIBLE_PULSE_RATE) || !pressure.optionalFlag("medicationTaken")) {
            codes += RejectionCode.INVALID_PAYLOAD
        }
        return codes.distinct()
    }

    /** The weight is the one field Samsung Health requires of a body composition. */
    private fun validateBodyComposition(payload: JsonObject): List<RejectionCode> {
        val composition = payload["bodyComposition"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val weight = composition["weight"] ?: return listOf(RejectionCode.INVALID_PAYLOAD)

        val codes = mutableListOf<RejectionCode>()
        codes += quantity(weight, KILOGRAMS, PLAUSIBLE_KILOGRAMS)
        bodyCompositionMeasures.forEach { (key, measure) -> codes += quantity(composition[key], measure.first, measure.second) }
        if (!composition.optional("bodyMassIndex", PLAUSIBLE_BODY_MASS_INDEX)) codes += RejectionCode.INVALID_PAYLOAD
        return codes.distinct()
    }

    private fun validateBodyTemperature(payload: JsonObject): List<RejectionCode> {
        val reading = payload["bodyTemperature"] as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val temperature = reading["temperature"] ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        return quantity(temperature, CELSIUS, PLAUSIBLE_BODY_CELSIUS)
    }
}
