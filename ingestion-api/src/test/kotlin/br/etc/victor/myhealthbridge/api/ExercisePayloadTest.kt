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

    @Test
    fun `rejects an exercise mapped by a version this server does not know`() {
        assertEquals(
            listOf(RejectionCode.UNSUPPORTED_MAPPER),
            reject(Envelopes.exercise(mapperVersion = "samsung-health-exercise/2")),
        )
    }

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
}
