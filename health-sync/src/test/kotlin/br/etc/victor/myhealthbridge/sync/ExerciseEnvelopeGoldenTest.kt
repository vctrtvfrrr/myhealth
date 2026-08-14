package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact document this application sends for an exercise, route and all.
 *
 * The same literal is posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mapper emits is what the read model was proven to show. Changing
 * one here without changing it there breaks that proof, which is the point.
 */
class ExerciseEnvelopeGoldenTest {

    @Test
    fun `pins the document an exercise with its route is sent as`() {
        val envelope = ExerciseMapper.map(
            exerciseRecord(
                uid = "uid-golden-exercise",
                customTitle = "morning loop",
                sessions = listOf(
                    session(
                        route = listOf(
                            location(at = "2026-08-10T09:00:00Z", latitude = "-23.5", longitude = "-46.6"),
                            location(
                                at = "2026-08-10T09:15:00Z",
                                latitude = "-23.6",
                                longitude = "-46.7",
                                altitude = null,
                                accuracy = null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            GOLDEN,
            IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope),
        )
    }

    private companion object {
        const val GOLDEN ="""{"samsungUid":"uid-golden-exercise","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"RUNNING","custom_title":"morning loop","sessions":[{"duration":1800.0,"distance":5000.0,"calories":320.0,"route":[{"timestamp":"2026-08-10T09:00:00Z","latitude":-23.5,"longitude":-46.6,"altitude":760.0,"accuracy":4.0},{"timestamp":"2026-08-10T09:15:00Z","latitude":-23.6,"longitude":-46.7}]}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"exercise":{"type":"RUNNING","duration":{"value":1800.0,"unit":"s"},"distance":{"value":5000.0,"unit":"m"},"calories":{"value":320.0,"unit":"kcal"},"route":[{"at":"2026-08-10T09:00:00Z","latitudeDegrees":-23.5,"longitudeDegrees":-46.6,"altitudeMeters":760.0,"accuracyMeters":4.0},{"at":"2026-08-10T09:15:00Z","latitudeDegrees":-23.6,"longitudeDegrees":-46.7}]}}}}"""
    }
}
