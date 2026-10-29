package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * The one mapper of Samsung Health exercise records.
 *
 * An exercise is reported as a record whose sessions carry almost everything about it, including the
 * data that exists nowhere else: the route, which is a Health Category of its own for permission
 * purposes and never a record of its own. Whatever the source attached stays in the source payload of
 * the parent envelope, so a route Samsung Health did not disclose is simply absent rather than empty.
 *
 * Version 1 carried the totals and the route of the whole exercise. Version 2 also carries every
 * session as the source reported it, with its metrics, its log, its swim and its own leg of the route,
 * so a record a Full Reconciliation reads again gains them. Nothing is combined across sessions beyond
 * the totals, which keep the terms of ADR 0019.
 *
 * No field is declared as an enum here. This mapper interprets no constant — it decides nothing by the
 * exercise type, it carries it — so an unfamiliar constant is not a gap a maintainer could close, and
 * reporting one would ask for a change that no code needs.
 */
object ExerciseMapper : RecordMapper {

    override val version: String = "samsung-health-exercise/2"

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    /**
     * What the read model exposes about an exercise: what it was, how much of it there was, and each
     * session it was made of.
     *
     * The totals are summed over the sessions because that is where the source states them, and a
     * record holds more than one session only when the exercise itself had more than one leg. An
     * exercise whose type is absent is emitted without a normalization at all, so the API rejects it
     * and it stays a mapping pendency instead of becoming an unnamed activity.
     */
    private fun normalizedPayload(record: SourceRecord): JsonObject = buildJsonObject {
        val type = record.fields[EXERCISE_TYPE] as? SourceValue.Text ?: return@buildJsonObject
        val sessions = (record.fields[SESSIONS] as? SourceValue.Series)?.entries.orEmpty()

        put(
            "exercise",
            buildJsonObject {
                put("type", type.value)
                (record.fields[CUSTOM_TITLE] as? SourceValue.Text)?.let { put("customTitle", it.value) }
                quantity("duration", total(sessions, DURATION), SECONDS)
                quantity("distance", total(sessions, DISTANCE), METERS)
                quantity("calories", total(sessions, CALORIES), KILOCALORIES)
                sessions(sessions)
            },
        )
    }

    private fun JsonObjectBuilder.quantity(name: String, value: BigDecimal?, unit: String) {
        if (value == null) return
        put(
            name,
            buildJsonObject {
                put("value", JsonPrimitive(value))
                put("unit", unit)
            },
        )
    }

    /**
     * Null unless every session reported the measurement.
     *
     * Adding up the sessions that did report it would answer the same shape for a total of the whole
     * exercise and for a total of the part of it the source happened to measure, and nothing downstream
     * could tell the two apart. An absent total says what is true: this exercise has none.
     */
    private fun total(sessions: List<Map<String, SourceValue>>, field: String): BigDecimal? {
        if (sessions.isEmpty()) return null
        val measured = sessions.map { (it[field] as? SourceValue.Number ?: return null).value }
        return measured.reduce(BigDecimal::add)
    }

    /**
     * Every session, in the order the source reported them.
     *
     * Like a point of a route, a session states the unit of each metric in the name of its key, in the
     * unit the SDK documents for it. One that does not say when it began or ended is kept without the
     * time it did not state: its metrics, log and leg of the route are still the exercise's, and leaving
     * it out would move every session after it to another position.
     */
    private fun JsonObjectBuilder.sessions(sessions: List<Map<String, SourceValue>>) {
        if (sessions.isEmpty()) return
        put("sessions", JsonArray(sessions.map(::session)))
    }

    private fun session(session: Map<String, SourceValue>): JsonObject =
        buildJsonObject {
            (session[START_TIME] as? SourceValue.Text)?.let { put("start", it.value) }
            (session[END_TIME] as? SourceValue.Text)?.let { put("end", it.value) }
            SESSION_TEXTS.forEach { (source, key) -> (session[source] as? SourceValue.Text)?.let { put(key, it.value) } }
            SESSION_NUMBERS.forEach { (source, key) -> (session[source] as? SourceValue.Number)?.let { put(key, JsonPrimitive(it.value)) } }
            (session[AUTO_DETECTED] as? SourceValue.Flag)?.let { put("autoDetected", it.value) }
            (session[COMMENT] as? SourceValue.Text)?.let { put("comment", it.value) }
            (session[SWIMMING_LOG] as? SourceValue.Nested)?.let { put("swimming", swimming(it.fields)) }
            entries("log", session[LOG], ::logEntry)
            entries("route", session[ROUTE], ::point)
        }

    private fun JsonObjectBuilder.entries(
        key: String,
        source: SourceValue?,
        normalize: (Map<String, SourceValue>) -> JsonObject?,
    ) {
        val entries = (source as? SourceValue.Series)?.entries.orEmpty().mapNotNull(normalize)
        if (entries.isEmpty()) return
        put(key, JsonArray(entries))
    }

    /** One instant of the log; an entry that does not say when it was taken could not be plotted. */
    private fun logEntry(entry: Map<String, SourceValue>): JsonObject? {
        val at = entry[TIMESTAMP] as? SourceValue.Text ?: return null

        return buildJsonObject {
            put("at", at.value)
            LOG_NUMBERS.forEach { (source, key) -> (entry[source] as? SourceValue.Number)?.let { put(key, JsonPrimitive(it.value)) } }
        }
    }

    /**
     * The swim of a session in meters, whatever the pool was measured in.
     *
     * The source states the length of the pool and the distance swum in it in the unit of the pool. A
     * unit this mapper does not know would make any length in meters a guess, so both are left out and
     * the source payload keeps what was reported.
     */
    private fun swimming(log: Map<String, SourceValue>): JsonObject = buildJsonObject {
        val metersPerUnit = METERS_PER_POOL_UNIT[(log[POOL_LENGTH_UNIT] as? SourceValue.Text)?.value]
        if (metersPerUnit != null) {
            (log[POOL_LENGTH] as? SourceValue.Number)?.let { put("poolLengthMeters", JsonPrimitive(it.value * metersPerUnit)) }
            (log[TOTAL_DISTANCE] as? SourceValue.Number)?.let { put("totalDistanceMeters", JsonPrimitive(it.value * metersPerUnit)) }
        }
        (log[TOTAL_DURATION] as? SourceValue.Number)?.let { put("totalDurationSeconds", JsonPrimitive(it.value)) }
        entries("intervals", log[SWIMMING_INTERVALS], ::swimmingInterval)
    }

    private fun swimmingInterval(interval: Map<String, SourceValue>): JsonObject = buildJsonObject {
        (interval[DURATION] as? SourceValue.Number)?.let { put("durationSeconds", JsonPrimitive(it.value)) }
        (interval[STROKE_TYPE] as? SourceValue.Text)?.let { put("strokeType", it.value) }
        (interval[STROKE_COUNT] as? SourceValue.Number)?.let { put("strokeCount", JsonPrimitive(it.value)) }
        (interval[INTERVAL] as? SourceValue.Number)?.let { put("interval", JsonPrimitive(it.value)) }
    }

    /**
     * A point states its units in the name of each key rather than beside each value: a route is
     * thousands of points long, and the shape the totals use would repeat a unit as often as it repeats
     * a coordinate. A point without both coordinates is not a location and is left out.
     */
    private fun point(location: Map<String, SourceValue>): JsonObject? {
        val latitude = location[LATITUDE] as? SourceValue.Number ?: return null
        val longitude = location[LONGITUDE] as? SourceValue.Number ?: return null

        return buildJsonObject {
            (location[TIMESTAMP] as? SourceValue.Text)?.let { put("at", it.value) }
            put("latitudeDegrees", JsonPrimitive(latitude.value))
            put("longitudeDegrees", JsonPrimitive(longitude.value))
            (location[ALTITUDE] as? SourceValue.Number)?.let { put("altitudeMeters", JsonPrimitive(it.value)) }
            (location[ACCURACY] as? SourceValue.Number)?.let { put("accuracyMeters", JsonPrimitive(it.value)) }
        }
    }

    private const val EXERCISE_TYPE = "exercise_type"
    private const val CUSTOM_TITLE = "custom_title"
    private const val SESSIONS = "sessions"
    private const val START_TIME = "start_time"
    private const val END_TIME = "end_time"
    private const val DURATION = "duration"
    private const val DISTANCE = "distance"
    private const val CALORIES = "calories"
    private const val AUTO_DETECTED = "auto_detected"
    private const val COMMENT = "comment"
    private const val SWIMMING_LOG = "swimming_log"
    private const val LOG = "log"
    private const val ROUTE = "route"
    private const val TIMESTAMP = "timestamp"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val ALTITUDE = "altitude"
    private const val ACCURACY = "accuracy"
    private const val POOL_LENGTH = "pool_length"
    private const val POOL_LENGTH_UNIT = "pool_length_unit"
    private const val TOTAL_DISTANCE = "total_distance"
    private const val TOTAL_DURATION = "total_duration"
    private const val SWIMMING_INTERVALS = "swimming_intervals"
    private const val STROKE_TYPE = "stroke_type"
    private const val STROKE_COUNT = "stroke_count"
    private const val INTERVAL = "interval"

    private val SESSION_TEXTS = listOf(
        EXERCISE_TYPE to "type",
        CUSTOM_TITLE to "customTitle",
        "count_type" to "countType",
    )

    /** Each metric of a session under a key that names the unit the SDK documents for it. */
    private val SESSION_NUMBERS = listOf(
        DURATION to "durationSeconds",
        "count" to "count",
        CALORIES to "caloriesKilocalories",
        DISTANCE to "distanceMeters",
        "incline_distance" to "inclineDistanceMeters",
        "decline_distance" to "declineDistanceMeters",
        "altitude_gain" to "altitudeGainMeters",
        "altitude_loss" to "altitudeLossMeters",
        "min_altitude" to "minAltitudeMeters",
        "max_altitude" to "maxAltitudeMeters",
        "mean_speed" to "meanSpeedMetersPerSecond",
        "max_speed" to "maxSpeedMetersPerSecond",
        "mean_cadence" to "meanCadencePerMinute",
        "max_cadence" to "maxCadencePerMinute",
        "mean_calorie_burn_rate" to "meanCalorieBurnRateKilocaloriesPerHour",
        "max_calorie_burn_rate" to "maxCalorieBurnRateKilocaloriesPerHour",
        "mean_power" to "meanPowerWatts",
        "max_power" to "maxPowerWatts",
        "mean_rpm" to "meanRpm",
        "max_rpm" to "maxRpm",
        "mean_heart_rate" to "meanHeartRateBeatsPerMinute",
        "min_heart_rate" to "minHeartRateBeatsPerMinute",
        "max_heart_rate" to "maxHeartRateBeatsPerMinute",
        "vo2_max" to "vo2Max",
    )

    private val LOG_NUMBERS = listOf(
        "heart_rate" to "heartRateBeatsPerMinute",
        "cadence" to "cadencePerMinute",
        "count" to "count",
        "power" to "powerWatts",
        "speed" to "speedMetersPerSecond",
    )

    /** The units the SDK documents a pool in, and the international yard. */
    private val METERS_PER_POOL_UNIT = mapOf(
        "meter" to BigDecimal.ONE,
        "yard" to BigDecimal("0.9144"),
    )

    private const val SECONDS = "s"
    private const val METERS = "m"
    private const val KILOCALORIES = "kcal"
}
