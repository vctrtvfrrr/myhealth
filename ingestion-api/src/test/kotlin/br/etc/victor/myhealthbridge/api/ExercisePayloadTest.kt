package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the API accepts as an exercise.
 *
 * The totals and the route are optional because the source reports them only when it has them, so what
 * is checked is the shape and the unit of whatever did arrive — a mapper defect, never an athlete.
 */
class ExercisePayloadTest {

    private val validator = ItemValidator(Envelopes.EXERCISE_RECORD_TYPE)

    @Test
    fun `accepts a synthetic exercise`() {
        val envelope = accept(Envelopes.exercise())

        assertEquals("uid-exercise-1", envelope.envelope.samsungUid)
        assertEquals(Envelopes.EXERCISE_RECORD_TYPE, envelope.recordType)
        assertEquals("present", envelope.stateName)
    }

    @Test
    fun `accepts an exercise the source reported no totals for`() {
        accept(Envelopes.exercise(duration = null, distance = null, calories = null))
    }

    @Test
    fun `accepts the route the exercise carries`() {
        accept(Envelopes.exercise(route = listOf(Envelopes.routePoint(), Envelopes.routePoint(altitude = null))))
    }

    @Test
    fun `rejects an exercise the mapper did not name`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.exercise(type = null)))
    }

    @Test
    fun `rejects a total outside the range the mapper can mean`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.exercise(distance = "-1.0")))
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.exercise(duration = "999999999")))
    }

    @Test
    fun `rejects a total reported in another unit`() {
        assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(Envelopes.exercise(distanceUnit = "km")))
        assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(Envelopes.exercise(caloriesUnit = "cal")))
    }

    @Test
    fun `rejects a point of the route that is not a location`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.exercise(route = listOf(Envelopes.routePoint(latitude = null)))),
        )
    }

    @Test
    fun `rejects a coordinate no place on earth has`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.exercise(route = listOf(Envelopes.routePoint(latitude = "91.0")))),
        )
    }

    /**
     * The read model casts every key of a point to the type its name promises. A value that cannot be
     * cast has to be refused here: stored, it would fail every query over the view rather than only the
     * exercise that carried it.
     */
    @Test
    fun `rejects a point the read model could not read`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.exercise(route = listOf(Envelopes.routePoint(at = "not-an-instant")))),
        )
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.exercise(route = listOf(Envelopes.routePoint(altitude = "up")))),
        )
    }

    @Test
    fun `rejects an altitude no place on earth has`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.exercise(route = listOf(Envelopes.routePoint(altitude = "99000.0")))),
        )
    }

    @Test
    fun `rejects an exercise mapped by a version this server does not know`() {
        assertEquals(
            listOf(RejectionCode.UNSUPPORTED_MAPPER),
            reject(Envelopes.exercise(mapperVersion = "samsung-health-exercise/99")),
        )
    }

    @Test
    fun `accepts an exercise of either version, so an application not yet updated keeps delivering`() {
        accept(Envelopes.exercise())
        accept(
            ExerciseEnvelopes.exercise(
                sessions = listOf(
                    ExerciseEnvelopes.session(
                        log = listOf(ExerciseEnvelopes.logEntry()),
                        route = listOf(Envelopes.routePoint()),
                    ),
                    ExerciseEnvelopes.session(
                        start = "2026-08-10T09:30:00Z",
                        values = mapOf("type" to "POOL_SWIMMING"),
                        swimming = ExerciseEnvelopes.swimming(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `accepts a session that says nothing beyond when it was`() {
        val bare = ExerciseEnvelopes.session(values = SESSION_KEYS.associateWith { null })

        accept(ExerciseEnvelopes.exercise(customTitle = null, sessions = listOf(bare)))
        accept(ExerciseEnvelopes.exercise(sessions = null))
    }

    @Test
    fun `accepts a session that does not say when it began or ended`() {
        accept(ExerciseEnvelopes.exercise(sessions = listOf(ExerciseEnvelopes.session(start = null, end = null))))
    }

    @Test
    fun `rejects a session whose times the read model could not read`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), rejectSession(ExerciseEnvelopes.session(end = "yesterday")))
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(ExerciseEnvelopes.session(start = "+10000-01-01T00:00:00Z")),
        )
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(ExerciseEnvelopes.session(log = listOf(ExerciseEnvelopes.logEntry(at = "-0001-01-01T00:00:00Z")))),
        )
    }

    /** Version 2 carries the route inside its sessions; a route beside them too would show every point twice. */
    @Test
    fun `rejects an exercise of version 2 that also carries the route of version 1`() {
        val both = ExerciseEnvelopes.exercise(
            route = listOf(Envelopes.routePoint()),
            sessions = listOf(ExerciseEnvelopes.session(route = listOf(Envelopes.routePoint()))),
        )

        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(both))
    }

    @Test
    fun `rejects a metric of a session outside the range its name promises`() {
        listOf(
            "meanSpeedMetersPerSecond" to "-1",
            "maxHeartRateBeatsPerMinute" to "600",
            "minAltitudeMeters" to "99000",
            "durationSeconds" to "999999999",
        ).forEach { (key, value) ->
            assertEquals(
                listOf(RejectionCode.INVALID_PAYLOAD),
                rejectSession(ExerciseEnvelopes.session(values = mapOf(key to value))),
                key,
            )
        }
    }

    /** Every key of a session is cast by the read model, so one it could not read is refused here. */
    @Test
    fun `rejects a session the read model could not read`() {
        listOf<Map<String, Any?>>(
            mapOf("meanPowerWatts" to "strong"),
            mapOf("autoDetected" to "yes"),
            mapOf("type" to ""),
            mapOf("comment" to "12"),
        ).forEach { values ->
            assertEquals(
                listOf(RejectionCode.INVALID_PAYLOAD),
                rejectSession(ExerciseEnvelopes.session(values = values)),
                values.toString(),
            )
        }
    }

    @Test
    fun `rejects a log entry the read model could not place or read`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(ExerciseEnvelopes.session(log = listOf(ExerciseEnvelopes.logEntry(at = null)))),
        )
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(
                ExerciseEnvelopes.session(log = listOf(ExerciseEnvelopes.logEntry(values = mapOf("speedMetersPerSecond" to "fast")))),
            ),
        )
    }

    @Test
    fun `rejects a swim the read model could not read`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(ExerciseEnvelopes.session(swimming = ExerciseEnvelopes.swimming(poolLengthMeters = "-25"))),
        )
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(
                ExerciseEnvelopes.session(
                    swimming = ExerciseEnvelopes.swimming(intervals = listOf(ExerciseEnvelopes.swimmingInterval(strokeCount = "many"))),
                ),
            ),
        )
    }

    @Test
    fun `rejects a point of a leg of the route that is not a location`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            rejectSession(ExerciseEnvelopes.session(route = listOf(Envelopes.routePoint(longitude = null)))),
        )
    }

    @Test
    fun `rejects a title that is not text`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(ExerciseEnvelopes.exercise(customTitle = "12")))
    }

    @Test
    fun `rejects an exercise of version 2 the mapper did not name`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(ExerciseEnvelopes.exercise(type = null)))
    }

    private fun rejectSession(session: JsonObject): List<RejectionCode> =
        reject(ExerciseEnvelopes.exercise(sessions = listOf(session)))

    private fun accept(item: JsonObject): ObservedEnvelope =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> validation.envelope
            is ItemValidation.Invalid -> error("expected an observation, got ${validation.codes}")
        }

    private fun reject(item: JsonObject): List<RejectionCode> =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> error("expected a rejection")
            is ItemValidation.Invalid -> validation.codes
        }

    private companion object {
        val SESSION_KEYS = listOf(
            "type", "customTitle", "durationSeconds", "count", "countType", "caloriesKilocalories", "distanceMeters",
            "inclineDistanceMeters", "declineDistanceMeters", "altitudeGainMeters", "altitudeLossMeters",
            "minAltitudeMeters", "maxAltitudeMeters", "meanSpeedMetersPerSecond", "maxSpeedMetersPerSecond",
            "meanCadencePerMinute", "maxCadencePerMinute", "meanCalorieBurnRateKilocaloriesPerHour",
            "maxCalorieBurnRateKilocaloriesPerHour", "meanPowerWatts", "maxPowerWatts", "meanRpm", "maxRpm",
            "meanHeartRateBeatsPerMinute", "minHeartRateBeatsPerMinute", "maxHeartRateBeatsPerMinute", "vo2Max",
            "autoDetected", "comment",
        )
    }
}
