package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the API accepts as a night of sleep.
 *
 * Everything inside the night is optional because the source reports it only when it has it, so what
 * is checked is the shape of whatever did arrive — and every key the read model casts, since a value it
 * cannot cast would fail every query over the view rather than only the night that carried it.
 */
class SleepPayloadTest {

    private val validator = ItemValidator(Envelopes.SLEEP_RECORD_TYPE)

    @Test
    fun `accepts a synthetic night`() {
        val envelope = accept(Envelopes.sleep())

        assertEquals("uid-sleep-1", envelope.envelope.samsungUid)
        assertEquals(Envelopes.SLEEP_RECORD_TYPE, envelope.recordType)
        assertEquals("present", envelope.stateName)
    }

    @Test
    fun `accepts a night with its stages`() {
        accept(
            Envelopes.sleep(
                sessions = listOf(
                    Envelopes.sleepSession(stages = listOf(Envelopes.sleepStage(), Envelopes.sleepStage(stage = "REM"))),
                ),
            ),
        )
    }

    @Test
    fun `accepts a night the source reported nothing inside of`() {
        accept(Envelopes.sleep(duration = null, score = null, sessions = null))
    }

    /** A stage this server never heard of is still a stage: the API carries it rather than refusing the night. */
    @Test
    fun `accepts a stage constant it does not know`() {
        accept(Envelopes.sleep(sessions = listOf(Envelopes.sleepSession(stages = listOf(Envelopes.sleepStage(stage = "NAP"))))))
    }

    @Test
    fun `rejects a night the mapper did not normalize`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.sleep(withSleep = false)))
    }

    @Test
    fun `rejects a duration outside the range the mapper can mean`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.sleep(duration = "-1.0")))
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.sleep(duration = "999999999")))
    }

    @Test
    fun `rejects a duration reported in another unit`() {
        assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(Envelopes.sleep(durationUnit = "min")))
    }

    @Test
    fun `rejects a score the read model could not read as a whole number`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.sleep(score = "82.5")))
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.sleep(score = "good")))
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.sleep(score = "-1")))
    }

    @Test
    fun `rejects a session that does not say when it happened`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.sleep(sessions = listOf(Envelopes.sleepSession(start = null)))),
        )
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.sleep(sessions = listOf(Envelopes.sleepSession(end = "yesterday")))),
        )
    }

    @Test
    fun `rejects a session duration the read model could not read`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(Envelopes.sleep(sessions = listOf(Envelopes.sleepSession(durationSeconds = "long")))),
        )
    }

    @Test
    fun `rejects a stage that does not say which stage or when`() {
        listOf(
            Envelopes.sleepStage(stage = null),
            Envelopes.sleepStage(stage = ""),
            Envelopes.sleepStage(start = null),
            Envelopes.sleepStage(end = "2026-08-10T02:40:00"),
        ).forEach { stage ->
            assertEquals(
                listOf(RejectionCode.INVALID_PAYLOAD),
                reject(Envelopes.sleep(sessions = listOf(Envelopes.sleepSession(stages = listOf(stage))))),
                "accepted $stage",
            )
        }
    }

    @Test
    fun `rejects a night mapped by a version this server does not know`() {
        assertEquals(
            listOf(RejectionCode.UNSUPPORTED_MAPPER),
            reject(Envelopes.sleep(mapperVersion = "samsung-health-sleep/2")),
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
