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
 * No field is declared as an enum here. This mapper interprets no constant — it decides nothing by the
 * exercise type, it carries it — so an unfamiliar constant is not a gap a maintainer could close, and
 * reporting one would ask for a change that no code needs.
 */
object ExerciseMapper : RecordMapper {

    override val version: String = "samsung-health-exercise/1"

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    /**
     * What the read model exposes about an exercise: what it was, how much of it there was, and where
     * it went.
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
                quantity("duration", total(sessions, DURATION), SECONDS)
                quantity("distance", total(sessions, DISTANCE), METERS)
                quantity("calories", total(sessions, CALORIES), KILOCALORIES)
                route(sessions)
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

    /** Null when no session reported the measurement, which is different from every one reporting zero. */
    private fun total(sessions: List<Map<String, SourceValue>>, field: String): BigDecimal? = sessions
        .mapNotNull { it[field] as? SourceValue.Number }
        .map { it.value }
        .reduceOrNull(BigDecimal::add)

    /**
     * The route of the whole exercise, in the order its sessions were reported.
     *
     * A point states its units in the name of each key rather than beside each value: a route is
     * thousands of points long, and the shape the scalars above use would repeat a unit as often as it
     * repeats a coordinate. A point without both coordinates is not a location and is left out.
     */
    private fun JsonObjectBuilder.route(sessions: List<Map<String, SourceValue>>) {
        val points = sessions
            .flatMap { (it[ROUTE] as? SourceValue.Series)?.entries.orEmpty() }
            .mapNotNull(::point)
        if (points.isEmpty()) return
        put("route", JsonArray(points))
    }

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
    private const val SESSIONS = "sessions"
    private const val DURATION = "duration"
    private const val DISTANCE = "distance"
    private const val CALORIES = "calories"
    private const val ROUTE = "route"
    private const val TIMESTAMP = "timestamp"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val ALTITUDE = "altitude"
    private const val ACCURACY = "accuracy"

    private const val SECONDS = "s"
    private const val METERS = "m"
    private const val KILOCALORIES = "kcal"
}
