package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic envelopes of the measurements that carry a binned series: heart rate from version 2 of its
 * mapper, blood oxygen, skin temperature and blood glucose.
 *
 * Every value is invented: the repository must never carry fixtures derived from real measurements.
 */
object BinnedEnvelopes {

    const val HEART_RATE_RECORD_TYPE = "heart_rate"
    const val HEART_RATE_MAPPER_VERSION = "samsung-health-heart-rate/2"
    const val BLOOD_OXYGEN_RECORD_TYPE = "blood_oxygen"
    const val BLOOD_OXYGEN_MAPPER_VERSION = "samsung-health-blood-oxygen/1"
    const val SKIN_TEMPERATURE_RECORD_TYPE = "skin_temperature"
    const val SKIN_TEMPERATURE_MAPPER_VERSION = "samsung-health-skin-temperature/1"
    const val BLOOD_GLUCOSE_RECORD_TYPE = "blood_glucose"
    const val BLOOD_GLUCOSE_MAPPER_VERSION = "samsung-health-blood-glucose/1"

    fun heartRate(
        samsungUid: String = "uid-heart-rate-1",
        rate: String? = "72.5",
        unit: String = "/min",
        min: String? = "58.0",
        max: String? = "131.0",
        series: List<JsonObject>? = listOf(heartRateBin()),
        mapperVersion: String = HEART_RATE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T03:15:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "heart_rate", rate) {
        if (withPayload) {
            putJsonObject("heartRate") {
                quantity("rate", rate, unit)
                quantity("min", min, unit)
                quantity("max", max, unit)
                series?.let { put("series", JsonArray(it)) }
            }
        }
    }

    fun heartRateBin(
        start: String? = "2026-08-10T03:00:00Z",
        end: String? = "2026-08-10T03:10:00Z",
        rate: String? = "72.5",
        min: String? = "58.0",
        max: String? = "131.0",
    ): JsonObject = bin(start, end, "rateBeatsPerMinute" to rate, "minBeatsPerMinute" to min, "maxBeatsPerMinute" to max)

    fun bloodOxygen(
        samsungUid: String = "uid-blood-oxygen-1",
        saturation: String? = "96.0",
        unit: String = "%",
        min: String? = "93.0",
        max: String? = "98.0",
        series: List<JsonObject>? = listOf(oxygenBin()),
        mapperVersion: String = BLOOD_OXYGEN_MAPPER_VERSION,
        observedAt: String = "2026-08-10T03:15:00Z",
        withPayload: Boolean = true,
        start: String = "2026-08-10T03:00:00Z",
        end: String = "2026-08-10T03:10:00Z",
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "spo2", saturation, start, end) {
        if (withPayload) {
            putJsonObject("bloodOxygen") {
                quantity("saturation", saturation, unit)
                quantity("min", min, unit)
                quantity("max", max, unit)
                series?.let { put("series", JsonArray(it)) }
            }
        }
    }

    fun oxygenBin(
        start: String? = "2026-08-10T03:00:00Z",
        end: String? = "2026-08-10T03:10:00Z",
        saturation: String? = "96.0",
        min: String? = "93.0",
        max: String? = "98.0",
    ): JsonObject = bin(start, end, "saturationPercent" to saturation, "minPercent" to min, "maxPercent" to max)

    fun skinTemperature(
        samsungUid: String = "uid-skin-temperature-1",
        temperature: String? = "33.5",
        unit: String = "Cel",
        min: String? = "33.1",
        max: String? = "33.9",
        series: List<JsonObject>? = listOf(skinTemperatureBin()),
        mapperVersion: String = SKIN_TEMPERATURE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T03:15:00Z",
        withPayload: Boolean = true,
        start: String = "2026-08-10T03:00:00Z",
        end: String = "2026-08-10T03:10:00Z",
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "temperature", temperature, start, end) {
        if (withPayload) {
            putJsonObject("skinTemperature") {
                quantity("temperature", temperature, unit)
                quantity("min", min, unit)
                quantity("max", max, unit)
                series?.let { put("series", JsonArray(it)) }
            }
        }
    }

    fun skinTemperatureBin(
        start: String? = "2026-08-10T03:00:00Z",
        end: String? = "2026-08-10T03:10:00Z",
        temperature: String? = "33.5",
        min: String? = "33.1",
        max: String? = "33.9",
    ): JsonObject = bin(start, end, "temperatureCelsius" to temperature, "minCelsius" to min, "maxCelsius" to max)

    fun bloodGlucose(
        samsungUid: String = "uid-blood-glucose-1",
        glucose: String? = "5.4",
        unit: String = "mmol/L",
        measurementType: String? = "PLASMA",
        sampleSourceType: String? = "CAPILLARY",
        mealStatus: String? = "FASTING",
        mealTime: String? = null,
        insulinInjected: String? = null,
        medicationTaken: JsonPrimitive? = null,
        series: List<JsonObject>? = null,
        mapperVersion: String = BLOOD_GLUCOSE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T11:05:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(samsungUid, mapperVersion, observedAt, "glucose", glucose) {
        if (withPayload) {
            putJsonObject("bloodGlucose") {
                quantity("glucose", glucose, unit)
                measurementType?.let { put("measurementType", it) }
                sampleSourceType?.let { put("sampleSourceType", it) }
                mealStatus?.let { put("mealStatus", it) }
                mealTime?.let { put("mealTime", it) }
                insulinInjected?.let { put("insulinInjected", measurement(it)) }
                medicationTaken?.let { put("medicationTaken", it) }
                series?.let { put("series", JsonArray(it)) }
            }
        }
    }

    fun glucoseReading(at: String? = "2026-08-10T11:00:00Z", glucose: String? = "5.4"): JsonObject = buildJsonObject {
        at?.let { put("at", it) }
        glucose?.let { put("glucoseMmolPerLiter", measurement(it)) }
    }

    private fun present(
        samsungUid: String,
        mapperVersion: String,
        observedAt: String,
        sourceField: String,
        sourceValue: String?,
        start: String = "2026-08-10T03:00:00Z",
        end: String = "2026-08-10T03:10:00Z",
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
            putJsonObject("sourcePayload") {
                putJsonObject("fields") { sourceValue?.toBigDecimalOrNull()?.let { put(sourceField, it) } }
            }
            putJsonObject("normalizedPayload", normalized)
        }
    }

    private fun bin(start: String?, end: String?, vararg values: Pair<String, String?>): JsonObject = buildJsonObject {
        start?.let { put("start", it) }
        end?.let { put("end", it) }
        values.forEach { (key, value) -> value?.let { put(key, measurement(it)) } }
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
