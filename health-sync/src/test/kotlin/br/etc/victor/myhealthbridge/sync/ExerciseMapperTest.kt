package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ExerciseMapperTest {

    private val mapper = ExerciseMapper

    @Test
    fun `carries the Health Record Identity`() {
        val envelope = mapper.map(exerciseRecord(uid = "samsung-uid-7"))

        assertEquals("samsung-uid-7", envelope.samsungUid)
        assertEquals("samsung-health-exercise/1", envelope.mapperVersion)
    }

    @Test
    fun `observes at the time the source last changed the record`() {
        val envelope = mapper.map(exerciseRecord(updateTime = Instant.parse("2026-08-10T10:00:00Z")))

        assertEquals("2026-08-10T10:00:00Z", envelope.observedAt.instant)
        assertEquals("-03:00", envelope.observedAt.offset)
    }

    @Test
    fun `maps the same record identically on every import`() {
        val record = exerciseRecord(sessions = listOf(session(route = listOf(location()))))

        assertEquals(mapper.map(record), mapper.map(record))
    }

    @Test
    fun `keeps the period in UTC beside the original offset`() {
        val period = (mapper.map(exerciseRecord()).state as RecordState.Present).period

        assertEquals("2026-08-10T09:00:00Z", period.start.instant)
        assertEquals("2026-08-10T09:30:00Z", period.end.instant)
        assertEquals("-03:00", period.start.offset)
        assertEquals("-03:00", period.end.offset)
    }

    @Test
    fun `preserves the identity of the reporting application and device`() {
        val envelope = mapper.map(exerciseRecord())

        assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `normalizes what the exercise was and how much of it there was`() {
        val exercise = exerciseOf(mapper.map(exerciseRecord()))

        assertEquals("RUNNING", (exercise["type"] as JsonPrimitive).content)
        assertEquals("1800.000" to "s", quantity(exercise, "duration"))
        assertEquals("5000.0" to "m", quantity(exercise, "distance"))
        assertEquals("320.0" to "kcal", quantity(exercise, "calories"))
    }

    @Test
    fun `adds up what every leg of the exercise reported`() {
        val record = exerciseRecord(
            sessions = listOf(
                session(duration = "600.000", distance = "1500.0", calories = "90.0"),
                session(duration = "1200.000", distance = "3500.0", calories = "230.0"),
            ),
        )

        val exercise = exerciseOf(mapper.map(record))
        assertEquals("1800.000" to "s", quantity(exercise, "duration"))
        assertEquals("5000.0" to "m", quantity(exercise, "distance"))
        assertEquals("320.0" to "kcal", quantity(exercise, "calories"))
    }

    @Test
    fun `leaves out a total no session reported`() {
        val exercise = exerciseOf(mapper.map(exerciseRecord(sessions = listOf(session(distance = null)))))

        assertNull(exercise["distance"])
    }

    /** Half of an exercise measured is not a total of it, and would be indistinguishable from one. */
    @Test
    fun `leaves out a total only part of the exercise reported`() {
        val record = exerciseRecord(
            sessions = listOf(session(distance = "1500.0"), session(distance = null)),
        )

        val exercise = exerciseOf(mapper.map(record))
        assertNull(exercise["distance"])
        assertEquals("3600.000" to "s", quantity(exercise, "duration"))
    }

    @Test
    fun `leaves out every total of an exercise the source reported no session for`() {
        val exercise = exerciseOf(mapper.map(exerciseRecord(sessions = null)))

        assertEquals("RUNNING", (exercise["type"] as JsonPrimitive).content)
        assertNull(exercise["duration"])
    }

    @Test
    fun `emits no normalization for an exercise the source did not name`() {
        val envelope = mapper.map(exerciseRecord(exerciseType = null))

        assertEquals(emptyMap<String, JsonPrimitive>(), (envelope.state as RecordState.Present).normalizedPayload)
    }

    @Test
    fun `preserves the enum constant the source reported`() {
        val record = exerciseRecord(
            exerciseType = "POOL_SWIMMING",
            sessions = listOf(session(fields = mapOf("count_type" to SourceValue.Text("STROKE")))),
        )

        val fields = fieldsOf(mapper.map(record))
        assertEquals("POOL_SWIMMING", (fields["exercise_type"] as JsonPrimitive).content)
        val session = (fields["sessions"] as JsonArray)[0] as JsonObject
        assertEquals("STROKE", (session["count_type"] as JsonPrimitive).content)
    }

    @Test
    fun `preserves everything the source attached to the exercise`() {
        val record = exerciseRecord(
            customTitle = "morning loop",
            sessions = listOf(
                session(
                    route = listOf(location(at = "2026-08-10T09:00:00Z", latitude = "-23.5", longitude = "-46.6")),
                    fields = mapOf(
                        "auto_detected" to SourceValue.Flag(true),
                        "swimming_log" to SourceValue.Nested(mapOf("pool_length" to number("25"))),
                        "log" to SourceValue.Series(listOf(mapOf("heart_rate" to number("144.0")))),
                    ),
                ),
            ),
        )

        val fields = fieldsOf(mapper.map(record))
        assertEquals("morning loop", (fields["custom_title"] as JsonPrimitive).content)

        val session = (fields["sessions"] as JsonArray)[0] as JsonObject
        assertEquals("true", (session["auto_detected"] as JsonPrimitive).content)
        assertEquals("25", ((session["swimming_log"] as JsonObject)["pool_length"] as JsonPrimitive).content)
        assertEquals("144.0", (((session["log"] as JsonArray)[0] as JsonObject)["heart_rate"] as JsonPrimitive).content)

        val point = (session["route"] as JsonArray)[0] as JsonObject
        assertEquals("-23.5", (point["latitude"] as JsonPrimitive).content)
    }

    @Test
    fun `projects the route of every leg, in the order the source reported it`() {
        val record = exerciseRecord(
            sessions = listOf(
                session(route = listOf(location(at = "2026-08-10T09:00:00Z", latitude = "-23.5"))),
                session(route = listOf(location(at = "2026-08-10T09:20:00Z", latitude = "-23.6"))),
            ),
        )

        val route = exerciseOf(mapper.map(record))["route"] as JsonArray
        assertEquals(2, route.size)
        assertEquals("2026-08-10T09:00:00Z", ((route[0] as JsonObject)["at"] as JsonPrimitive).content)
        assertEquals("-23.6", ((route[1] as JsonObject)["latitudeDegrees"] as JsonPrimitive).content)
        assertEquals("760.0", ((route[1] as JsonObject)["altitudeMeters"] as JsonPrimitive).content)
        assertEquals("4.0", ((route[1] as JsonObject)["accuracyMeters"] as JsonPrimitive).content)
    }

    /** An exercise Samsung Health disclosed no location for is normalized without one, not with an empty one. */
    @Test
    fun `leaves the route out when the source disclosed none`() {
        val exercise = exerciseOf(mapper.map(exerciseRecord()))

        assertNull(exercise["route"])
    }

    @Test
    fun `leaves out a point that is not a location`() {
        val record = exerciseRecord(
            sessions = listOf(
                session(
                    route = listOf(
                        mapOf("timestamp" to SourceValue.Text("2026-08-10T09:00:00Z")),
                        location(latitude = "-23.5", longitude = "-46.6"),
                    ),
                ),
            ),
        )

        val route = exerciseOf(mapper.map(record))["route"] as JsonArray
        assertEquals(1, route.size)
    }

    @Test
    fun `leaves an altitude the source did not report out of the point`() {
        val record = exerciseRecord(sessions = listOf(session(route = listOf(location(altitude = null, accuracy = null)))))

        val point = (exerciseOf(mapper.map(record))["route"] as JsonArray)[0] as JsonObject
        assertNull(point["altitudeMeters"])
        assertNull(point["accuracyMeters"])
        assertTrue(point.containsKey("latitudeDegrees"))
    }

    @Test
    fun `leaves the instant standing alone when the source reported no local context`() {
        val record = sourceRecord(
            offset = null,
            fields = mapOf("exercise_type" to SourceValue.Text("YOGA")),
        )

        assertEquals("+00:00", mapper.map(record).observedAt.offset)
    }

    @Test
    fun `renders a removal the way every mapper does`() {
        val envelope = mapper.removalOf("uid-exercise-1", Instant.parse("2026-08-12T09:00:00Z"))

        assertEquals(SourceIdentity.Unknown, envelope.sourceProvenance.sourceApp)
        assertEquals("2026-08-12T09:00:00Z", envelope.observedAt.instant)
        assertEquals("+00:00", envelope.observedAt.offset)
        assertTrue(envelope.state is RecordState.Removed)
    }

    private fun exerciseOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload["exercise"] as JsonObject

    private fun fieldsOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).sourcePayload["fields"] as JsonObject

    private fun quantity(exercise: JsonObject, name: String): Pair<String, String> {
        val quantity = exercise[name] as JsonObject
        return (quantity["value"] as JsonPrimitive).content to (quantity["unit"] as JsonPrimitive).content
    }
}
