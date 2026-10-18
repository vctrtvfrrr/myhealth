package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact document this application sends for a night of sleep, stages and all.
 *
 * The same literal is posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mapper emits is what the read model was proven to show.
 */
class SleepEnvelopeGoldenTest {

    @Test
    fun `pins the document a night with its stages is sent as`() {
        val envelope = SleepMapper.map(
            sleepRecord(
                uid = "uid-golden-sleep",
                duration = "25200.000",
                sessions = listOf(
                    sleepSession(
                        start = "2026-08-10T02:00:00Z",
                        end = "2026-08-10T05:00:00Z",
                        duration = "10800.000",
                        stages = listOf(
                            sleepStage(start = "2026-08-10T02:00:00Z", end = "2026-08-10T02:40:00Z", stage = "LIGHT"),
                            sleepStage(start = "2026-08-10T02:40:00Z", end = "2026-08-10T03:30:00Z", stage = "DEEP"),
                        ),
                    ),
                    sleepSession(start = "2026-08-10T05:30:00Z", end = "2026-08-10T09:30:00Z", duration = "14400.000"),
                ),
            ),
        )

        assertEquals(
            GOLDEN,
            IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope),
        )
    }

    private companion object {
        const val GOLDEN ="""{"samsungUid":"uid-golden-sleep","observedAt":{"instant":"2026-08-10T09:35:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-sleep/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T02:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"sleep_duration":25200.0,"sleep_score":82,"sessions":[{"start_time":"2026-08-10T02:00:00Z","end_time":"2026-08-10T05:00:00Z","duration":10800.0,"stages":[{"start_time":"2026-08-10T02:00:00Z","end_time":"2026-08-10T02:40:00Z","stage":"LIGHT"},{"start_time":"2026-08-10T02:40:00Z","end_time":"2026-08-10T03:30:00Z","stage":"DEEP"}]},{"start_time":"2026-08-10T05:30:00Z","end_time":"2026-08-10T09:30:00Z","duration":14400.0}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"sleep":{"duration":{"value":25200.0,"unit":"s"},"score":82,"sessions":[{"start":"2026-08-10T02:00:00Z","end":"2026-08-10T05:00:00Z","durationSeconds":10800.0,"stages":[{"start":"2026-08-10T02:00:00Z","end":"2026-08-10T02:40:00Z","stage":"LIGHT"},{"start":"2026-08-10T02:40:00Z","end":"2026-08-10T03:30:00Z","stage":"DEEP"}]},{"start":"2026-08-10T05:30:00Z","end":"2026-08-10T09:30:00Z","durationSeconds":14400.0}]}}}}"""
    }
}
