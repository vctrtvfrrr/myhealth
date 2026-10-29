package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact documents this application sends for an exercise of two legs, routes and logs and all, and
 * for a swim.
 *
 * The same literals are posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that what the mapper emits is what the read model was proven to show. Changing
 * one here without changing it there breaks that proof, which is the point.
 */
class ExerciseEnvelopeGoldenTest {

    @Test
    fun `pins the document an exercise of two legs is sent as`() {
        val envelope = ExerciseMapper.map(
            exerciseRecord(
                uid = "uid-golden-exercise",
                customTitle = "morning loop",
                sessions = listOf(
                    session(
                        end = "2026-08-10T09:12:00Z",
                        duration = "720.000",
                        distance = "2000.0",
                        calories = "130.0",
                        route = listOf(location(at = "2026-08-10T09:00:00Z", latitude = "-23.5", longitude = "-46.6")),
                        fields = mapOf(
                            "exercise_type" to SourceValue.Text("RUNNING"),
                            "mean_speed" to number("2.75"),
                            "max_speed" to number("4.5"),
                            "mean_heart_rate" to number("141.0"),
                            "auto_detected" to SourceValue.Flag(false),
                            "log" to SourceValue.Series(listOf(exerciseLog(at = "2026-08-10T09:01:00Z", power = null))),
                        ),
                    ),
                    session(
                        start = "2026-08-10T09:12:00Z",
                        duration = "1080.000",
                        distance = "3000.0",
                        calories = "190.0",
                        route = listOf(
                            location(
                                at = "2026-08-10T09:15:00Z",
                                latitude = "-23.6",
                                longitude = "-46.7",
                                altitude = null,
                                accuracy = null,
                            ),
                        ),
                        fields = mapOf("exercise_type" to SourceValue.Text("WALKING"), "comment" to SourceValue.Text("cool down")),
                    ),
                ),
            ),
        )

        assertEquals(GOLDEN, IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope))
    }

    @Test
    fun `pins the document a swim is sent as`() {
        val envelope = ExerciseMapper.map(
            exerciseRecord(
                uid = "uid-golden-swim",
                exerciseType = "POOL_SWIMMING",
                sessions = listOf(
                    session(
                        distance = "1000.0",
                        fields = mapOf(
                            "count_type" to SourceValue.Text("STROKE"),
                            "count" to number("720"),
                            "swimming_log" to swimmingLog(
                                intervals = listOf(
                                    swimmingInterval(),
                                    swimmingInterval(duration = "101.250", strokeType = "BACKSTROKE", strokeCount = "40", interval = "2"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(GOLDEN_SWIM, IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope))
    }

    private companion object {
        const val GOLDEN =
            """{"samsungUid":"uid-golden-exercise","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/2","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"RUNNING","custom_title":"morning loop","sessions":[{"start_time":"2026-08-10T09:00:00Z","end_time":"2026-08-10T09:12:00Z","duration":720.0,"distance":2000.0,"calories":130.0,"route":[{"timestamp":"2026-08-10T09:00:00Z","latitude":-23.5,"longitude":-46.6,"altitude":760.0,"accuracy":4.0}],"exercise_type":"RUNNING","mean_speed":2.75,"max_speed":4.5,"mean_heart_rate":141.0,"auto_detected":false,"log":[{"timestamp":"2026-08-10T09:01:00Z","heart_rate":128.0,"cadence":82.0,"count":3,"speed":6.25}]},{"start_time":"2026-08-10T09:12:00Z","end_time":"2026-08-10T09:30:00Z","duration":1080.0,"distance":3000.0,"calories":190.0,"route":[{"timestamp":"2026-08-10T09:15:00Z","latitude":-23.6,"longitude":-46.7}],"exercise_type":"WALKING","comment":"cool down"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"exercise":{"type":"RUNNING","customTitle":"morning loop","duration":{"value":1800.0,"unit":"s"},"distance":{"value":5000.0,"unit":"m"},"calories":{"value":320.0,"unit":"kcal"},"sessions":[{"start":"2026-08-10T09:00:00Z","end":"2026-08-10T09:12:00Z","type":"RUNNING","durationSeconds":720.0,"caloriesKilocalories":130.0,"distanceMeters":2000.0,"meanSpeedMetersPerSecond":2.75,"maxSpeedMetersPerSecond":4.5,"meanHeartRateBeatsPerMinute":141.0,"autoDetected":false,"log":[{"at":"2026-08-10T09:01:00Z","heartRateBeatsPerMinute":128.0,"cadencePerMinute":82.0,"count":3,"speedMetersPerSecond":6.25}],"route":[{"at":"2026-08-10T09:00:00Z","latitudeDegrees":-23.5,"longitudeDegrees":-46.6,"altitudeMeters":760.0,"accuracyMeters":4.0}]},{"start":"2026-08-10T09:12:00Z","end":"2026-08-10T09:30:00Z","type":"WALKING","durationSeconds":1080.0,"caloriesKilocalories":190.0,"distanceMeters":3000.0,"comment":"cool down","route":[{"at":"2026-08-10T09:15:00Z","latitudeDegrees":-23.6,"longitudeDegrees":-46.7}]}]}}}}"""

        const val GOLDEN_SWIM =
            """{"samsungUid":"uid-golden-swim","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/2","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"POOL_SWIMMING","sessions":[{"start_time":"2026-08-10T09:00:00Z","end_time":"2026-08-10T09:30:00Z","duration":1800.0,"distance":1000.0,"calories":320.0,"count_type":"STROKE","count":720,"swimming_log":{"pool_length":25,"pool_length_unit":"meter","total_distance":1000.0,"total_duration":1500.0,"swimming_intervals":[{"duration":95.5,"stroke_type":"FREESTYLE","stroke_count":36,"interval":1},{"duration":101.25,"stroke_type":"BACKSTROKE","stroke_count":40,"interval":2}]}}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"exercise":{"type":"POOL_SWIMMING","duration":{"value":1800.0,"unit":"s"},"distance":{"value":1000.0,"unit":"m"},"calories":{"value":320.0,"unit":"kcal"},"sessions":[{"start":"2026-08-10T09:00:00Z","end":"2026-08-10T09:30:00Z","countType":"STROKE","durationSeconds":1800.0,"count":720,"caloriesKilocalories":320.0,"distanceMeters":1000.0,"swimming":{"poolLengthMeters":25,"totalDistanceMeters":1000.0,"totalDurationSeconds":1500.0,"intervals":[{"durationSeconds":95.5,"strokeType":"FREESTYLE","strokeCount":36,"interval":1},{"durationSeconds":101.25,"strokeType":"BACKSTROKE","strokeCount":40,"interval":2}]}}]}}}}"""
    }
}
