package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bloodPressure
import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bodyComposition
import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bodyCompositionMeasures
import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bodyTemperature
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the API accepts as a blood pressure, body composition or body temperature reading.
 *
 * What Samsung Health requires of a reading is required here, because a record without it would be an
 * invented reading; everything else is optional because the source reports it only when it has it, and
 * what did arrive is checked, since the read model casts every key to the type its name promises.
 */
class VitalSignPayloadTest {

    @Nested
    inner class BloodPressure {

        private val validator = ItemValidator(VitalSignEnvelopes.BLOOD_PRESSURE_RECORD_TYPE)

        @Test
        fun `accepts a synthetic reading`() {
            accept(validator, bloodPressure(medicationTaken = JsonPrimitive(true)))
        }

        @Test
        fun `accepts a reading with nothing but both pressures`() {
            accept(validator, bloodPressure(mean = null, pulseRate = null, medicationTaken = null))
        }

        @Test
        fun `rejects a reading the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodPressure(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodPressure(systolic = null)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodPressure(diastolic = null)))
        }

        @Test
        fun `rejects a pressure no cuff can report`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodPressure(systolic = "-1")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodPressure(diastolic = "high")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodPressure(mean = "900")))
        }

        @Test
        fun `rejects a pressure reported in another unit`() {
            assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, bloodPressure(unit = "kPa")))
        }

        @Test
        fun `rejects a pulse rate or a medication the read model could not cast`() {
            listOf(
                bloodPressure(pulseRate = "fast"),
                bloodPressure(pulseRate = "0"),
                bloodPressure(pulseRate = "72.5"),
                bloodPressure(medicationTaken = JsonPrimitive("yes")),
            ).forEach { item ->
                assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, item), "accepted $item")
            }
        }

        @Test
        fun `rejects a reading mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, bloodPressure(mapperVersion = "samsung-health-blood-pressure/2")),
            )
        }
    }

    @Nested
    inner class BodyComposition {

        private val validator = ItemValidator(VitalSignEnvelopes.BODY_COMPOSITION_RECORD_TYPE)

        @Test
        fun `accepts a synthetic measurement with every measure`() {
            accept(validator, bodyComposition())
        }

        @Test
        fun `accepts a weighing with nothing but the weight`() {
            accept(validator, bodyComposition(measures = mapOf("weight" to ("70.5" to "kg")), bodyMassIndex = null))
        }

        @Test
        fun `rejects a measurement without the weight`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bodyComposition(withPayload = false)))
            assertEquals(
                listOf(RejectionCode.INVALID_PAYLOAD),
                reject(validator, bodyComposition(measures = bodyCompositionMeasures - "weight")),
            )
        }

        @Test
        fun `rejects any measure no body can have`() {
            bodyCompositionMeasures.forEach { (name, measure) ->
                val implausible = bodyCompositionMeasures + (name to ("-1" to measure.second))
                assertEquals(
                    listOf(RejectionCode.INVALID_PAYLOAD),
                    reject(validator, bodyComposition(measures = implausible)),
                    "accepted a negative $name",
                )
            }
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bodyComposition(bodyMassIndex = "lean")))
        }

        @Test
        fun `rejects any measure reported in another unit`() {
            bodyCompositionMeasures.forEach { (name, measure) ->
                val misreported = bodyCompositionMeasures + (name to (measure.first to "[lb_av]"))
                assertEquals(
                    listOf(RejectionCode.INVALID_UNIT),
                    reject(validator, bodyComposition(measures = misreported)),
                    "accepted $name in pounds",
                )
            }
        }

        @Test
        fun `rejects a measurement mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, bodyComposition(mapperVersion = "samsung-health-body-composition/2")),
            )
        }
    }

    @Nested
    inner class BodyTemperature {

        private val validator = ItemValidator(VitalSignEnvelopes.BODY_TEMPERATURE_RECORD_TYPE)

        @Test
        fun `accepts a synthetic reading`() {
            accept(validator, bodyTemperature())
        }

        @Test
        fun `rejects a reading the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bodyTemperature(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bodyTemperature(temperature = null)))
        }

        @Test
        fun `rejects a temperature no body can have`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bodyTemperature(temperature = "98.6")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bodyTemperature(temperature = "-1")))
        }

        @Test
        fun `rejects a temperature reported in another unit`() {
            assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, bodyTemperature(unit = "[degF]")))
        }

        @Test
        fun `rejects a reading mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, bodyTemperature(mapperVersion = "samsung-health-body-temperature/2")),
            )
        }
    }

    private fun accept(validator: ItemValidator, item: JsonObject): ObservedEnvelope =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> validation.envelope
            is ItemValidation.Invalid -> error("expected an observation, got ${validation.codes}")
        }

    private fun reject(validator: ItemValidator, item: JsonObject): List<RejectionCode> =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> error("expected a rejection")
            is ItemValidation.Invalid -> validation.codes
        }
}
