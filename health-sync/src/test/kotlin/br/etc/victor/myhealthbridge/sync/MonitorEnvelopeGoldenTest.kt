package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact documents this application sends for irregular heart rhythm notifications, sleep apnea
 * assessments and energy scores.
 *
 * The same literals are posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mappers emit is what the read model was proven to show.
 */
class MonitorEnvelopeGoldenTest {

    @Test
    fun `pins the document an irregular heart rhythm notification is sent as`() {
        val envelope = IrregularHeartRhythmMapper.map(irregularHeartRhythmRecord(uid = "uid-golden-irregular-heart-rhythm"))

        assertEquals(GOLDEN_IRREGULAR_HEART_RHYTHM, encode(envelope))
    }

    @Test
    fun `pins the document a sleep apnea assessment with no stated result is sent as`() {
        val envelope = SleepApneaMapper.map(sleepApneaRecord(uid = "uid-golden-sleep-apnea", result = "UNDEFINED"))

        assertEquals(GOLDEN_SLEEP_APNEA, encode(envelope))
    }

    @Test
    fun `pins the document an energy score is sent as`() {
        val envelope = EnergyScoreMapper.map(energyScoreRecord(uid = "uid-golden-energy-score"))

        assertEquals(GOLDEN_ENERGY_SCORE, encode(envelope))
    }

    private fun encode(envelope: HealthRecordEnvelope): String =
        IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope)

    private companion object {
        const val GOLDEN_IRREGULAR_HEART_RHYTHM = """{"samsungUid":"uid-golden-irregular-heart-rhythm","observedAt":{"instant":"2026-08-10T04:25:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-irregular-heart-rhythm/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T04:20:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T04:20:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"status":"DETECTED"},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"irregularHeartRhythm":{"status":"DETECTED"}}}}"""

        const val GOLDEN_SLEEP_APNEA = """{"samsungUid":"uid-golden-sleep-apnea","observedAt":{"instant":"2026-08-10T09:45:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-sleep-apnea/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T02:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"result":"UNDEFINED"},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"sleepApnea":{}}}}"""

        const val GOLDEN_ENERGY_SCORE = """{"samsungUid":"uid-golden-energy-score","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-energy-score/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"total_score":82.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"energyScore":{"score":82.0}}}}"""
    }
}
