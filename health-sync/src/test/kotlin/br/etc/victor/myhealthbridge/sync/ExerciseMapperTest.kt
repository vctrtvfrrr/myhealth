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
        assertEquals("samsung-health-exercise/2", envelope.mapperVersion)
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
    fun `projects the route of every leg inside the session it belongs to`() {
        val record = exerciseRecord(
            sessions = listOf(
                session(route = listOf(location(at = "2026-08-10T09:00:00Z", latitude = "-23.5"))),
                session(route = listOf(location(at = "2026-08-10T09:20:00Z", latitude = "-23.6"))),
            ),
        )

        val exercise = exerciseOf(mapper.map(record))
        assertNull(exercise["route"])
        assertEquals(listOf("2026-08-10T09:00:00Z"), routeOf(exercise, 0).map { text(it, "at") })
        val point = routeOf(exercise, 1).single()
        assertEquals("-23.6", text(point, "latitudeDegrees"))
        assertEquals("760.0", text(point, "altitudeMeters"))
        assertEquals("4.0", text(point, "accuracyMeters"))
    }

    /** An exercise Samsung Health disclosed no location for is normalized without one, not with an empty one. */
    @Test
    fun `leaves the route out when the source disclosed none`() {
        val exercise = exerciseOf(mapper.map(exerciseRecord()))

        assertNull(exercise["route"])
        assertNull(sessionsOf(exercise).single()["route"])
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

        assertEquals(1, routeOf(exerciseOf(mapper.map(record)), 0).size)
    }

    @Test
    fun `leaves an altitude the source did not report out of the point`() {
        val record = exerciseRecord(sessions = listOf(session(route = listOf(location(altitude = null, accuracy = null)))))

        val point = routeOf(exerciseOf(mapper.map(record)), 0).single()
        assertNull(point["altitudeMeters"])
        assertNull(point["accuracyMeters"])
        assertTrue(point.containsKey("latitudeDegrees"))
    }

    @Test
    fun `carries the title the Data Owner gave the exercise`() {
        assertEquals("morning loop", text(exerciseOf(mapper.map(exerciseRecord(customTitle = "morning loop"))), "customTitle"))
        assertNull(exerciseOf(mapper.map(exerciseRecord()))["customTitle"])
    }

    /** Nothing is combined across sessions: each one is carried as the source reported it. */
    @Test
    fun `projects every session, in the order the source reported them`() {
        val record = exerciseRecord(
            sessions = listOf(
                session(start = "2026-08-10T09:00:00Z", end = "2026-08-10T09:10:00Z", duration = "600.000"),
                session(start = "2026-08-10T09:12:00Z", end = "2026-08-10T09:30:00Z", duration = "1080.000"),
            ),
        )

        val sessions = sessionsOf(exerciseOf(mapper.map(record)))
        assertEquals(listOf("2026-08-10T09:00:00Z", "2026-08-10T09:12:00Z"), sessions.map { text(it, "start") })
        assertEquals(listOf("2026-08-10T09:10:00Z", "2026-08-10T09:30:00Z"), sessions.map { text(it, "end") })
        assertEquals(listOf("600.000", "1080.000"), sessions.map { text(it, "durationSeconds") })
    }

    @Test
    fun `normalizes every metric of a session under a key that names its unit`() {
        val record = exerciseRecord(sessions = listOf(session(fields = sessionMetrics())))

        val session = sessionsOf(exerciseOf(mapper.map(record))).single()
        assertEquals(
            mapOf(
                "start" to "2026-08-10T09:00:00Z",
                "end" to "2026-08-10T09:30:00Z",
                "durationSeconds" to "1800.000",
                "type" to "BIKING",
                "customTitle" to "hill leg",
                "countType" to "REPETITION",
                "count" to "42",
                "caloriesKilocalories" to "320.0",
                "distanceMeters" to "5000.0",
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
                "autoDetected" to "false",
                "comment" to "windy",
            ),
            session.mapValues { (it.value as JsonPrimitive).content },
        )
    }

    @Test
    fun `leaves out a metric the session did not report`() {
        val record = exerciseRecord(sessions = listOf(session(duration = null, distance = null, calories = null)))

        val session = sessionsOf(exerciseOf(mapper.map(record))).single()
        assertEquals(setOf("start", "end"), session.keys)
    }

    @Test
    fun `leaves out a session that does not say when it began and ended`() {
        val record = exerciseRecord(
            sessions = listOf(session(start = null), session(end = null), session(start = "2026-08-10T09:12:00Z")),
        )

        val exercise = exerciseOf(mapper.map(record))
        assertEquals(listOf("2026-08-10T09:12:00Z"), sessionsOf(exercise).map { text(it, "start") })
        assertEquals("5400.000" to "s", quantity(exercise, "duration"))
    }

    @Test
    fun `projects the log of a session, in the order the source reported it`() {
        val record = exerciseRecord(
            sessions = listOf(
                session(
                    fields = mapOf(
                        "log" to SourceValue.Series(
                            listOf(
                                exerciseLog(),
                                exerciseLog(at = null),
                                exerciseLog(at = "2026-08-10T09:02:00Z", cadence = null, count = null, power = null, speed = null),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val log = (sessionsOf(exerciseOf(mapper.map(record))).single()["log"] as JsonArray).map { it as JsonObject }
        assertEquals(
            listOf(
                mapOf(
                    "at" to "2026-08-10T09:01:00Z",
                    "heartRateBeatsPerMinute" to "128.0",
                    "cadencePerMinute" to "82.0",
                    "count" to "3",
                    "powerWatts" to "175.0",
                    "speedMetersPerSecond" to "6.25",
                ),
                mapOf("at" to "2026-08-10T09:02:00Z", "heartRateBeatsPerMinute" to "128.0"),
            ),
            log.map { entry -> entry.mapValues { (it.value as JsonPrimitive).content } },
        )
    }

    @Test
    fun `summarizes the swim of a session in meters and seconds`() {
        val record = exerciseRecord(
            exerciseType = "POOL_SWIMMING",
            sessions = listOf(session(fields = mapOf("swimming_log" to swimmingLog()))),
        )

        val swimming = sessionsOf(exerciseOf(mapper.map(record))).single()["swimming"] as JsonObject
        assertEquals("25", text(swimming, "poolLengthMeters"))
        assertEquals("1000.0", text(swimming, "totalDistanceMeters"))
        assertEquals("1500.000", text(swimming, "totalDurationSeconds"))
    }

    /** The source states a pool, and the distance swum in it, in the unit the pool was measured in. */
    @Test
    fun `converts a pool measured in yards to meters`() {
        val log = swimmingLog(poolLength = "25", poolLengthUnit = "yard", totalDistance = "500.0")
        val record = exerciseRecord(sessions = listOf(session(fields = mapOf("swimming_log" to log))))

        val swimming = sessionsOf(exerciseOf(mapper.map(record))).single()["swimming"] as JsonObject
        assertEquals("22.8600", text(swimming, "poolLengthMeters"))
        assertEquals("457.20000", text(swimming, "totalDistanceMeters"))
    }

    /** A length in a unit this mapper does not know would be a guess in meters, so it is left out. */
    @Test
    fun `leaves out a length measured in a unit it does not know`() {
        val record = exerciseRecord(
            sessions = listOf(session(fields = mapOf("swimming_log" to swimmingLog(poolLengthUnit = "furlong")))),
        )

        val swimming = sessionsOf(exerciseOf(mapper.map(record))).single()["swimming"] as JsonObject
        assertNull(swimming["poolLengthMeters"])
        assertNull(swimming["totalDistanceMeters"])
        assertEquals("1500.000", text(swimming, "totalDurationSeconds"))
    }

    @Test
    fun `projects every interval of the swim, with the stroke the source named it by`() {
        val log = swimmingLog(
            intervals = listOf(
                swimmingInterval(),
                swimmingInterval(duration = "101.250", strokeType = "BACKSTROKE", strokeCount = "40", interval = "2"),
            ),
        )
        val record = exerciseRecord(sessions = listOf(session(fields = mapOf("swimming_log" to log))))

        val swimming = sessionsOf(exerciseOf(mapper.map(record))).single()["swimming"] as JsonObject
        val intervals = (swimming["intervals"] as JsonArray).map { interval ->
            (interval as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }
        assertEquals(
            listOf(
                mapOf("durationSeconds" to "95.500", "strokeType" to "FREESTYLE", "strokeCount" to "36", "interval" to "1"),
                mapOf("durationSeconds" to "101.250", "strokeType" to "BACKSTROKE", "strokeCount" to "40", "interval" to "2"),
            ),
            intervals,
        )
    }

    @Test
    fun `leaves the swim out of a session that was no swim`() {
        assertNull(sessionsOf(exerciseOf(mapper.map(exerciseRecord()))).single()["swimming"])
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

    private fun sessionsOf(exercise: JsonObject): List<JsonObject> =
        (exercise["sessions"] as JsonArray).map { it as JsonObject }

    private fun routeOf(exercise: JsonObject, session: Int): List<JsonObject> =
        (sessionsOf(exercise)[session]["route"] as JsonArray).map { it as JsonObject }

    private fun text(node: JsonObject, key: String): String = (node[key] as JsonPrimitive).content

    private fun fieldsOf(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).sourcePayload["fields"] as JsonObject

    private fun quantity(exercise: JsonObject, name: String): Pair<String, String> {
        val quantity = exercise[name] as JsonObject
        return (quantity["value"] as JsonPrimitive).content to (quantity["unit"] as JsonPrimitive).content
    }
}
