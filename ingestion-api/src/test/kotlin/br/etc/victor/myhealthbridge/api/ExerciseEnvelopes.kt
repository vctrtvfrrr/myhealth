package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Synthetic exercises as version 2 of their mapper sends them: the totals and the type of version 1, the
 * title, and every session with its metrics, log, swim and leg of the route.
 *
 * Every value is invented, the coordinates included: the repository must never carry fixtures derived
 * from real measurements or from a place the Data Owner has been.
 */
object ExerciseEnvelopes {

    const val MAPPER_VERSION = "samsung-health-exercise/2"

    fun exercise(
        samsungUid: String = "uid-exercise-2",
        type: String? = "RUNNING",
        customTitle: String? = "morning loop",
        sessions: List<JsonObject>? = listOf(session()),
        observedAt: String = "2026-08-10T10:00:00Z",
        mapperVersion: String = MAPPER_VERSION,
    ): JsonObject {
        val v1 = Envelopes.exercise(samsungUid = samsungUid, type = type, mapperVersion = mapperVersion, observedAt = observedAt)
        val state = v1.getValue("state") as JsonObject
        val normalized = state.getValue("normalizedPayload") as JsonObject
        val exercise = JsonObject(
            (normalized.getValue("exercise") as JsonObject) + buildMap<String, JsonElement> {
                customTitle?.let { put("customTitle", primitive(it)) }
                sessions?.let { put("sessions", JsonArray(it)) }
            },
        )
        return JsonObject(v1 + ("state" to JsonObject(state + ("normalizedPayload" to JsonObject(mapOf("exercise" to exercise))))))
    }

    /** A session, with every metric a ride can carry unless a test says otherwise; [values] override them. */
    fun session(
        start: String? = "2026-08-10T09:00:00Z",
        end: String? = "2026-08-10T09:30:00Z",
        values: Map<String, Any?> = emptyMap(),
        log: List<JsonObject>? = null,
        swimming: JsonObject? = null,
        route: List<JsonObject>? = null,
    ): JsonObject = buildJsonObject {
        start?.let { put("start", it) }
        end?.let { put("end", it) }
        (SESSION_METRICS + values).forEach { (key, value) -> value?.let { put(key, primitive(it)) } }
        log?.let { put("log", JsonArray(it)) }
        swimming?.let { put("swimming", it) }
        route?.let { put("route", JsonArray(it)) }
    }

    fun logEntry(at: String? = "2026-08-10T09:01:00Z", values: Map<String, Any?> = emptyMap()): JsonObject = buildJsonObject {
        at?.let { put("at", it) }
        (LOG_METRICS + values).forEach { (key, value) -> value?.let { put(key, primitive(it)) } }
    }

    fun swimming(
        poolLengthMeters: String? = "25",
        totalDistanceMeters: String? = "1000.0",
        totalDurationSeconds: String? = "1500.0",
        intervals: List<JsonObject>? = listOf(swimmingInterval()),
    ): JsonObject = buildJsonObject {
        poolLengthMeters?.let { put("poolLengthMeters", primitive(it)) }
        totalDistanceMeters?.let { put("totalDistanceMeters", primitive(it)) }
        totalDurationSeconds?.let { put("totalDurationSeconds", primitive(it)) }
        intervals?.let { put("intervals", JsonArray(it)) }
    }

    fun swimmingInterval(
        durationSeconds: String? = "95.5",
        strokeType: String? = "FREESTYLE",
        strokeCount: String? = "36",
        interval: String? = "1",
    ): JsonObject = buildJsonObject {
        durationSeconds?.let { put("durationSeconds", primitive(it)) }
        strokeType?.let { put("strokeType", it) }
        strokeCount?.let { put("strokeCount", primitive(it)) }
        interval?.let { put("interval", primitive(it)) }
    }

    /** A number when it is one, a flag when it is one, so a test can also state what a defective mapper would send. */
    private fun primitive(value: Any): JsonPrimitive = when (value) {
        is Boolean -> JsonPrimitive(value)
        is String -> value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private val SESSION_METRICS: Map<String, Any> = mapOf(
        "type" to "BIKING",
        "customTitle" to "hill leg",
        "durationSeconds" to "1800.0",
        "count" to "42",
        "countType" to "REPETITION",
        "caloriesKilocalories" to "320.0",
        "distanceMeters" to "12000.0",
        "inclineDistanceMeters" to "1200.0",
        "declineDistanceMeters" to "1100.0",
        "altitudeGainMeters" to "85.5",
        "altitudeLossMeters" to "80.25",
        "minAltitudeMeters" to "700.0",
        "maxAltitudeMeters" to "785.5",
        "meanSpeedMetersPerSecond" to "6.5",
        "maxSpeedMetersPerSecond" to "12.25",
        "meanCadencePerMinute" to "80.0",
        "maxCadencePerMinute" to "110.0",
        "meanCalorieBurnRateKilocaloriesPerHour" to "540.0",
        "maxCalorieBurnRateKilocaloriesPerHour" to "900.0",
        "meanPowerWatts" to "180.0",
        "maxPowerWatts" to "420.0",
        "meanRpm" to "78.0",
        "maxRpm" to "105.0",
        "meanHeartRateBeatsPerMinute" to "142.0",
        "minHeartRateBeatsPerMinute" to "96.0",
        "maxHeartRateBeatsPerMinute" to "171.0",
        "vo2Max" to "44.5",
        "autoDetected" to false,
        "comment" to "windy",
    )

    private val LOG_METRICS: Map<String, Any> = mapOf(
        "heartRateBeatsPerMinute" to "128.0",
        "cadencePerMinute" to "82.0",
        "count" to "3",
        "powerWatts" to "175.0",
        "speedMetersPerSecond" to "6.25",
    )
}
