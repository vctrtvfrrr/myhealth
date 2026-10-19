package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MonitorMappersTest {

    @Nested
    inner class IrregularHeartRhythm {

        private val mapper = IrregularHeartRhythmMapper

        @Test
        fun `carries the Health Record Identity and the provenance of the notification`() {
            val envelope = mapper.map(irregularHeartRhythmRecord(uid = "samsung-uid-7"))

            assertEquals("samsung-uid-7", envelope.samsungUid)
            assertEquals("samsung-health-irregular-heart-rhythm/1", envelope.mapperVersion)
            assertEquals(SourceIdentity.Known("com.example.shealth"), envelope.sourceProvenance.sourceApp)
            assertEquals(SourceIdentity.Known("device-1"), envelope.sourceProvenance.sourceDevice)
        }

        @Test
        fun `dates the notification at the detection, in UTC beside the original offset`() {
            val envelope = mapper.map(irregularHeartRhythmRecord())
            val period = (envelope.state as RecordState.Present).period

            assertEquals("2026-08-10T04:25:00Z", envelope.observedAt.instant)
            assertEquals("2026-08-10T04:20:00Z", period.start.instant)
            assertEquals("2026-08-10T04:20:00Z", period.end.instant)
            assertEquals("-03:00", period.start.offset)
        }

        @Test
        fun `carries the status under the constant the source named it with`() {
            val envelope = mapper.map(irregularHeartRhythmRecord(status = "DETECTED"))

            assertEquals(buildJsonObject { put("status", "DETECTED") }, normalized(envelope)["irregularHeartRhythm"])
        }

        /** UNDEFINED is how the SDK says the source did not state it, so the notification stands without one. */
        @Test
        fun `keeps a notification whose status the source left undefined, without a status`() {
            val undefined = normalized(mapper.map(irregularHeartRhythmRecord(status = "UNDEFINED")))
            val absent = normalized(mapper.map(irregularHeartRhythmRecord(status = null)))

            assertEquals(JsonObject(emptyMap()), undefined["irregularHeartRhythm"])
            assertEquals(JsonObject(emptyMap()), absent["irregularHeartRhythm"])
        }

        @Test
        fun `knows every status the pinned SDK declares`() {
            assertEquals(mapOf("status" to setOf("UNDEFINED", "DETECTED")), mapper.knownEnums)
        }

        @Test
        fun `preserves the status as the source reported it`() {
            val envelope = mapper.map(irregularHeartRhythmRecord(status = "UNDEFINED"))

            assertEquals(JsonPrimitive("UNDEFINED"), sourceFields(envelope)["status"])
        }
    }

    @Nested
    inner class SleepApnea {

        private val mapper = SleepApneaMapper

        @Test
        fun `carries the Health Record Identity of the assessment`() {
            val envelope = mapper.map(sleepApneaRecord(uid = "samsung-uid-8"))

            assertEquals("samsung-uid-8", envelope.samsungUid)
            assertEquals("samsung-health-sleep-apnea/1", envelope.mapperVersion)
        }

        @Test
        fun `keeps the period the assessment covers, in UTC beside the original offset`() {
            val period = (mapper.map(sleepApneaRecord()).state as RecordState.Present).period

            assertEquals("2026-08-10T02:00:00Z", period.start.instant)
            assertEquals("2026-08-10T09:30:00Z", period.end.instant)
            assertEquals("-03:00", period.end.offset)
        }

        @Test
        fun `carries the result under the constant the source named it with`() {
            val detected = normalized(mapper.map(sleepApneaRecord(result = "DETECTED")))
            val notDetected = normalized(mapper.map(sleepApneaRecord(result = "NOT_DETECTED")))

            assertEquals(buildJsonObject { put("result", "DETECTED") }, detected["sleepApnea"])
            assertEquals(buildJsonObject { put("result", "NOT_DETECTED") }, notDetected["sleepApnea"])
        }

        @Test
        fun `keeps an assessment whose result the source left undefined, without a result`() {
            assertEquals(JsonObject(emptyMap()), normalized(mapper.map(sleepApneaRecord(result = "UNDEFINED")))["sleepApnea"])
        }

        @Test
        fun `knows every result the pinned SDK declares`() {
            assertEquals(mapOf("result" to setOf("UNDEFINED", "DETECTED", "NOT_DETECTED")), mapper.knownEnums)
        }
    }

    @Nested
    inner class EnergyScore {

        private val mapper = EnergyScoreMapper

        @Test
        fun `carries the Health Record Identity of the day's score`() {
            val envelope = mapper.map(energyScoreRecord(uid = "samsung-uid-9"))

            assertEquals("samsung-uid-9", envelope.samsungUid)
            assertEquals("samsung-health-energy-score/1", envelope.mapperVersion)
        }

        /** Samsung Health reports no end for the score, so it is the point the day starts at, as stated. */
        @Test
        fun `dates the score at the start of its local day, with no end the source did not report`() {
            val envelope = mapper.map(energyScoreRecord())
            val period = (envelope.state as RecordState.Present).period

            assertEquals("2026-08-10T10:00:00Z", envelope.observedAt.instant)
            assertEquals("2026-08-10T03:00:00Z", period.start.instant)
            assertEquals("2026-08-10T03:00:00Z", period.end.instant)
            assertEquals("-03:00", period.start.offset)
        }

        /** Samsung Health documents no scale, so the score is carried without a unit it never claimed. */
        @Test
        fun `normalizes the score without a unit`() {
            val score = normalized(mapper.map(energyScoreRecord(score = "82.0")))["energyScore"] as JsonObject

            assertEquals(BigDecimal("82.0"), (score["score"] as JsonPrimitive).content.toBigDecimal())
            assertNull(score["unit"])
        }

        /** An absent score is not substituted, so the API rejects it and it stays a mapping pendency. */
        @Test
        fun `leaves the normalized score out when the source did not report one`() {
            assertNull(normalized(mapper.map(energyScoreRecord(score = null)))["energyScore"])
        }

        @Test
        fun `maps the same record identically on every import`() {
            assertEquals(mapper.map(energyScoreRecord()), mapper.map(energyScoreRecord()))
        }
    }

    private fun normalized(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).normalizedPayload

    private fun sourceFields(envelope: HealthRecordEnvelope): JsonObject =
        (envelope.state as RecordState.Present).sourcePayload["fields"] as JsonObject
}
