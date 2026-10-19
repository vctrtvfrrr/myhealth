package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.MonitorEnvelopes.energyScore
import br.etc.victor.myhealthbridge.api.MonitorEnvelopes.irregularHeartRhythm
import br.etc.victor.myhealthbridge.api.MonitorEnvelopes.sleepApnea
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the API accepts as an irregular heart rhythm notification, a sleep apnea assessment or an energy
 * score.
 *
 * A notification and an assessment are facts by themselves, so their constant is optional: the source
 * leaves it undefined at times, and the record still happened. The score is the whole record, so it is
 * required, the way a heart rate is.
 */
class MonitorPayloadTest {

    @Nested
    inner class IrregularHeartRhythm {

        private val validator = ItemValidator(MonitorEnvelopes.IRREGULAR_HEART_RHYTHM_RECORD_TYPE)

        @Test
        fun `accepts a synthetic notification, with or without its status`() {
            accept(validator, irregularHeartRhythm())
            accept(validator, irregularHeartRhythm(status = null))
        }

        @Test
        fun `rejects a notification the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, irregularHeartRhythm(withPayload = false)))
        }

        @Test
        fun `rejects a status that is not a constant`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, irregularHeartRhythm(status = "")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, irregularHeartRhythm(status = "X".repeat(65))))
        }
    }

    @Nested
    inner class SleepApnea {

        private val validator = ItemValidator(MonitorEnvelopes.SLEEP_APNEA_RECORD_TYPE)

        @Test
        fun `accepts a synthetic assessment, with or without its result`() {
            accept(validator, sleepApnea(result = "DETECTED"))
            accept(validator, sleepApnea(result = null))
        }

        @Test
        fun `rejects an assessment the mapper did not normalize`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, sleepApnea(withPayload = false)))
        }

        @Test
        fun `rejects a result that is not a constant`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, sleepApnea(result = "")))
        }
    }

    @Nested
    inner class EnergyScore {

        private val validator = ItemValidator(MonitorEnvelopes.ENERGY_SCORE_RECORD_TYPE)

        @Test
        fun `accepts a synthetic score of a day`() {
            accept(validator, energyScore())
        }

        @Test
        fun `rejects a day the mapper reported no score for`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, energyScore(withPayload = false)))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, energyScore(score = null)))
        }

        @Test
        fun `rejects a score no scale could mean`() {
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, energyScore(score = "-1")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, energyScore(score = "1001")))
            assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(validator, energyScore(score = "high")))
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
