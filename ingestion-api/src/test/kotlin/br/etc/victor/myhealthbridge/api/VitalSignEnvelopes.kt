package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic envelopes of blood pressure, body composition and body temperature readings.
 *
 * Every value is invented: the repository must never carry fixtures derived from real measurements.
 */
object VitalSignEnvelopes {

    const val BLOOD_PRESSURE_RECORD_TYPE = "blood_pressure"
    const val BLOOD_PRESSURE_MAPPER_VERSION = "samsung-health-blood-pressure/1"
    const val BODY_COMPOSITION_RECORD_TYPE = "body_composition"
    const val BODY_COMPOSITION_MAPPER_VERSION = "samsung-health-body-composition/1"
    const val BODY_TEMPERATURE_RECORD_TYPE = "body_temperature"
    const val BODY_TEMPERATURE_MAPPER_VERSION = "samsung-health-body-temperature/1"

    fun bloodPressure(
        samsungUid: String = "uid-blood-pressure-1",
        systolic: String? = "118.0",
        diastolic: String? = "76.0",
        mean: String? = "90.0",
        unit: String = "mm[Hg]",
        pulseRate: String? = "64",
        medicationTaken: JsonPrimitive? = JsonPrimitive(false),
        mapperVersion: String = BLOOD_PRESSURE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T10:01:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "systolic", systolic) {
        if (withPayload) {
            putJsonObject("bloodPressure") {
                quantity("systolic", systolic, unit)
                quantity("diastolic", diastolic, unit)
                quantity("mean", mean, unit)
                pulseRate?.let { put("pulseRate", measurement(it)) }
                medicationTaken?.let { put("medicationTaken", it) }
            }
        }
    }

    /** Each measure of a body composition with the unit it is reported in, the weight first. */
    val bodyCompositionMeasures: Map<String, Pair<String, String>> = linkedMapOf(
        "weight" to ("70.5" to "kg"),
        "height" to ("175.0" to "cm"),
        "bodyFat" to ("18.5" to "%"),
        "skeletalMuscle" to ("42.0" to "%"),
        "muscleMass" to ("45.5" to "%"),
        "basalMetabolicRate" to ("1650" to "kcal/d"),
        "bodyFatMass" to ("13.0" to "kg"),
        "fatFreeMass" to ("57.5" to "kg"),
        "fatFree" to ("81.5" to "%"),
        "skeletalMuscleMass" to ("29.6" to "kg"),
        "totalBodyWater" to ("42.1" to "L"),
    )

    fun bodyComposition(
        samsungUid: String = "uid-body-composition-1",
        measures: Map<String, Pair<String, String>?> = bodyCompositionMeasures,
        bodyMassIndex: String? = "23.0",
        mapperVersion: String = BODY_COMPOSITION_MAPPER_VERSION,
        observedAt: String = "2026-08-10T07:02:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "weight", measures["weight"]?.first) {
        if (withPayload) {
            putJsonObject("bodyComposition") {
                measures.forEach { (name, measure) -> measure?.let { (value, unit) -> quantity(name, value, unit) } }
                bodyMassIndex?.let { put("bodyMassIndex", measurement(it)) }
            }
        }
    }

    fun bodyTemperature(
        samsungUid: String = "uid-body-temperature-1",
        temperature: String? = "36.6",
        unit: String = "Cel",
        mapperVersion: String = BODY_TEMPERATURE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T08:00:30Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "temperature", temperature) {
        if (withPayload) {
            putJsonObject("bodyTemperature") { quantity("temperature", temperature, unit) }
        }
    }

    private fun present(
        samsungUid: String,
        mapperVersion: String,
        observedAt: String,
        sourceField: String,
        sourceValue: String?,
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
                    put("instant", "2026-08-10T07:00:00Z")
                    put("offset", "-03:00")
                }
                putJsonObject("end") {
                    put("instant", "2026-08-10T07:00:00Z")
                    put("offset", "-03:00")
                }
            }
            putJsonObject("sourcePayload") {
                putJsonObject("fields") { sourceValue?.toBigDecimalOrNull()?.let { put(sourceField, it) } }
            }
            putJsonObject("normalizedPayload", normalized)
        }
    }

    /** A number when it is one, so a test can also state what a defective mapper would have put there. */
    private fun measurement(value: String): JsonPrimitive =
        value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

    private fun JsonObjectBuilder.quantity(name: String, value: String?, unit: String) {
        if (value == null) return
        putJsonObject(name) {
            put("value", measurement(value))
            put("unit", unit)
        }
    }
}
