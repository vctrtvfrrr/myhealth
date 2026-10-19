package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.bloodGlucose
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.bloodOxygen
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.glucoseReading
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.oxygenBin
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.skinTemperature
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.skinTemperatureBin
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the API accepts as a blood oxygen, skin temperature or blood glucose measurement.
 *
 * The measurement itself is required, because a record without it would be an invented reading. Its
 * range, the context of a glucose reading and the series are optional because the source reports them
 * only when it has them; what did arrive is checked down to each bin, since the read model casts every
 * key of a bin to the type its name promises.
 */
class BinnedSeriesPayloadTest {

    @Nested
    inner class BloodOxygen {

        private val validator = ItemValidator(BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE)

        @Test
        fun `accepts a synthetic measurement with its series`() {
            accept(validator, bloodOxygen(series = listOf(oxygenBin(), oxygenBin(min = null, max = null))))
        }

        @Test
        fun `accepts a measurement the source reported no range nor series for`() {
            accept(validator, bloodOxygen(min = null, max = null, series = null))
        }

        @Test
        fun `rejects a measurement the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodOxygen(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodOxygen(saturation = null)))
        }

        @Test
        fun `rejects a saturation no percentage can mean`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodOxygen(saturation = "101")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodOxygen(min = "-1")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodOxygen(max = "high")))
        }

        @Test
        fun `rejects a saturation reported in another unit`() {
            assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, bloodOxygen(unit = "ratio")))
        }

        @Test
        fun `rejects a bin that does not say when it was or what it measured`() {
            listOf(
                oxygenBin(start = null),
                oxygenBin(end = "2026-08-10T03:10:00"),
                oxygenBin(saturation = null),
                oxygenBin(saturation = "150"),
                oxygenBin(min = "low"),
            ).forEach { bin ->
                assertEquals(
                    listOf(RejectionCode.INVALID_PAYLOAD),
                    reject(validator, bloodOxygen(series = listOf(bin))),
                    "accepted $bin",
                )
            }
        }

        @Test
        fun `rejects a measurement mapped by a version this server does not know`() {
            assertEquals(
                listOf(RejectionCode.UNSUPPORTED_MAPPER),
                reject(validator, bloodOxygen(mapperVersion = "samsung-health-blood-oxygen/2")),
            )
        }
    }

    @Nested
    inner class SkinTemperature {

        private val validator = ItemValidator(BinnedEnvelopes.SKIN_TEMPERATURE_RECORD_TYPE)

        @Test
        fun `accepts a synthetic measurement with its series`() {
            accept(validator, skinTemperature(series = listOf(skinTemperatureBin(), skinTemperatureBin(max = null))))
        }

        @Test
        fun `accepts a measurement the source reported no range nor series for`() {
            accept(validator, skinTemperature(min = null, max = null, series = null))
        }

        @Test
        fun `rejects a measurement the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, skinTemperature(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, skinTemperature(temperature = null)))
        }

        @Test
        fun `rejects a temperature no skin can have`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, skinTemperature(temperature = "93.5")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, skinTemperature(min = "-40")))
        }

        @Test
        fun `rejects a temperature reported in another unit`() {
            assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, skinTemperature(unit = "[degF]")))
        }

        @Test
        fun `rejects a bin that does not say when it was or what it measured`() {
            listOf(
                skinTemperatureBin(start = "yesterday"),
                skinTemperatureBin(end = null),
                skinTemperatureBin(temperature = null),
                skinTemperatureBin(max = "warm"),
            ).forEach { bin ->
                assertEquals(
                    listOf(RejectionCode.INVALID_PAYLOAD),
                    reject(validator, skinTemperature(series = listOf(bin))),
                    "accepted $bin",
                )
            }
        }
    }

    @Nested
    inner class BloodGlucose {

        private val validator = ItemValidator(BinnedEnvelopes.BLOOD_GLUCOSE_RECORD_TYPE)

        @Test
        fun `accepts a synthetic reading with everything the source can say about it`() {
            accept(
                validator,
                bloodGlucose(
                    mealTime = "2026-08-10T10:00:00Z",
                    insulinInjected = "4.0",
                    medicationTaken = JsonPrimitive(true),
                    series = listOf(glucoseReading(), glucoseReading(at = "2026-08-10T11:05:00Z", glucose = "5.9")),
                ),
            )
        }

        @Test
        fun `accepts a reading the source said nothing else about`() {
            accept(validator, bloodGlucose(measurementType = null, sampleSourceType = null, mealStatus = null))
        }

        /** A constant this server never heard of is still carried, the way a sleep stage is. */
        @Test
        fun `accepts a meal status constant it does not know`() {
            accept(validator, bloodGlucose(mealStatus = "DURING_MEAL"))
        }

        @Test
        fun `rejects a reading the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodGlucose(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodGlucose(glucose = null)))
        }

        @Test
        fun `rejects a glucose level no blood can have`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodGlucose(glucose = "-1")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, bloodGlucose(glucose = "540")))
        }

        /** Milligrams per deciliter is the other unit glucose is commonly read in, and the read model has one. */
        @Test
        fun `rejects a glucose level reported in another unit`() {
            assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(validator, bloodGlucose(unit = "mg/dL")))
        }

        @Test
        fun `rejects context the read model could not read`() {
            listOf(
                bloodGlucose(measurementType = ""),
                bloodGlucose(mealStatus = "X".repeat(65)),
                bloodGlucose(mealTime = "10:00"),
                bloodGlucose(insulinInjected = "a lot"),
                bloodGlucose(insulinInjected = "-2"),
                bloodGlucose(medicationTaken = JsonPrimitive("yes")),
            ).forEach { item ->
                assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, item), "accepted $item")
            }
        }

        @Test
        fun `rejects a reading of the series that does not say when or how much`() {
            listOf(
                glucoseReading(at = null),
                glucoseReading(glucose = null),
                glucoseReading(glucose = "sweet"),
            ).forEach { reading ->
                assertEquals(
                    listOf(RejectionCode.INVALID_PAYLOAD),
                    reject(validator, bloodGlucose(series = listOf(reading))),
                    "accepted $reading",
                )
            }
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
