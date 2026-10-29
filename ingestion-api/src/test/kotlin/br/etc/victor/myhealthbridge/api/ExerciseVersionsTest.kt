package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Both versions of the exercise mapper side by side: version 1 sent the totals and one route for the
 * whole exercise, version 2 also sends every session with its metrics, log, swim and leg of the route.
 * The API accepts both, because an application not yet updated still sends version 1, and a Full
 * Reconciliation turns every exercise version 1 delivered into one of version 2 observed at the same
 * instant.
 */
@Tag("integration")
class ExerciseVersionsTest : IngestionApiTest() {

    private fun sendExercises(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = Envelopes.EXERCISE_RECORD_TYPE, items = items.toList()), token)

    @Test
    fun `accepts a batch carrying exercises of both versions`() {
        val response = sendExercises(
            Envelopes.exercise(samsungUid = "uid-version-1"),
            ExerciseEnvelopes.exercise(samsungUid = "uid-version-2"),
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted", "accepted"), statuses(response.body))
    }

    @Test
    fun `shows an exercise of version 1 without a title, a session or a session for its route`() {
        sendExercises(Envelopes.exercise(samsungUid = "uid-old", route = listOf(Envelopes.routePoint(), Envelopes.routePoint())))

        assertEquals(listOf("RUNNING", "Corrida", null, Envelopes.EXERCISE_MAPPER_VERSION), current("uid-old"))
        assertEquals(0, countOf(rowsOf("current_exercise_session", "uid-old")))
        assertEquals(0, countOf(rowsOf("current_exercise_log", "uid-old")))
        assertEquals(listOf(listOf(null, "1"), listOf(null, "2")), locations("uid-old").map { it.take(2) })
    }

    @Test
    fun `shows every session of version 2, with every metric, in the order the source reported them`() {
        sendExercises(
            ExerciseEnvelopes.exercise(
                samsungUid = "uid-sessions",
                sessions = listOf(
                    ExerciseEnvelopes.session(end = "2026-08-10T09:12:00Z"),
                    ExerciseEnvelopes.session(
                        start = "2026-08-10T09:12:00Z",
                        values = mapOf("type" to "WALKING", "customTitle" to null, "comment" to null, "autoDetected" to true),
                    ),
                ),
            ),
        )

        assertEquals(listOf("RUNNING", "Corrida", "morning loop", ExerciseEnvelopes.MAPPER_VERSION), current("uid-sessions"))
        val sessions = rows(
            """
            select position::text, start_at::text, end_at::text, duration_seconds::text, exercise_type, exercise_label,
                custom_title, count::text, count_type, calories_kilocalories::text, distance_meters::text,
                incline_distance_meters::text, decline_distance_meters::text, altitude_gain_meters::text,
                altitude_loss_meters::text, min_altitude_meters::text, max_altitude_meters::text,
                mean_speed_meters_per_second::text, max_speed_meters_per_second::text, mean_cadence_per_minute::text,
                max_cadence_per_minute::text, mean_calorie_burn_rate_kilocalories_per_hour::text,
                max_calorie_burn_rate_kilocalories_per_hour::text, mean_power_watts::text, max_power_watts::text,
                mean_rpm::text, max_rpm::text, mean_heart_rate_beats_per_minute::text,
                min_heart_rate_beats_per_minute::text, max_heart_rate_beats_per_minute::text, vo2_max::text,
                auto_detected::text, comment, pool_length_meters::text, swimming_distance_meters::text,
                swimming_duration_seconds::text
            from read_model.current_exercise_session where samsung_uid = ? order by position
            """,
            "uid-sessions",
        )
        assertEquals(
            listOf(
                "1", "2026-08-10 09:00:00+00", "2026-08-10 09:12:00+00", "1800", "BIKING", "Ciclismo", "hill leg", "42",
                "REPETITION", "320", "12000", "1200", "1100", "85.5", "80.25", "700", "785.5", "6.5", "12.25",
                "80", "110", "540", "900", "180", "420", "78", "105", "142", "96", "171", "44.5",
                "false", "windy", null, null, null,
            ),
            sessions[0],
        )
        assertEquals(
            listOf("2", "2026-08-10 09:12:00+00", "2026-08-10 09:30:00+00", "WALKING", "Caminhada", null, "true", null),
            listOf(0, 1, 2, 4, 5, 6, 31, 32).map { sessions[1][it] },
        )
    }

    @Test
    fun `shows the log of each session by instant`() {
        sendExercises(
            ExerciseEnvelopes.exercise(
                samsungUid = "uid-log",
                sessions = listOf(
                    ExerciseEnvelopes.session(log = listOf(ExerciseEnvelopes.logEntry(), ExerciseEnvelopes.logEntry(at = "2026-08-10T09:02:00Z"))),
                    ExerciseEnvelopes.session(
                        start = "2026-08-10T09:30:00Z",
                        end = "2026-08-10T09:40:00Z",
                        log = listOf(
                            ExerciseEnvelopes.logEntry(
                                at = "2026-08-10T09:31:00Z",
                                values = mapOf("cadencePerMinute" to null, "count" to null, "powerWatts" to null),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                listOf("1", "1", "2026-08-10 09:01:00+00", "128", "82", "3", "175", "6.25"),
                listOf("1", "2", "2026-08-10 09:02:00+00", "128", "82", "3", "175", "6.25"),
                listOf("2", "1", "2026-08-10 09:31:00+00", "128", null, null, null, "6.25"),
            ),
            rows(
                """
                select session_position::text, position::text, at::text, heart_rate_beats_per_minute::text,
                    cadence_per_minute::text, count::text, power_watts::text, speed_meters_per_second::text
                from read_model.current_exercise_log where samsung_uid = ? order by session_position, position
                """,
                "uid-log",
            ),
        )
    }

    @Test
    fun `shows the swim of a session, summary and intervals`() {
        sendExercises(
            ExerciseEnvelopes.exercise(
                samsungUid = "uid-swim",
                type = "POOL_SWIMMING",
                sessions = listOf(
                    ExerciseEnvelopes.session(
                        values = mapOf("type" to "POOL_SWIMMING", "countType" to "STROKE"),
                        swimming = ExerciseEnvelopes.swimming(
                            intervals = listOf(
                                ExerciseEnvelopes.swimmingInterval(),
                                ExerciseEnvelopes.swimmingInterval(
                                    durationSeconds = "101.25",
                                    strokeType = "BACKSTROKE",
                                    strokeCount = "40",
                                    interval = "2",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Natação em piscina", current("uid-swim")[1])
        assertEquals(
            listOf(listOf("STROKE", "25", "1000", "1500")),
            rows(
                """
                select count_type, pool_length_meters::text, swimming_distance_meters::text,
                    swimming_duration_seconds::text
                from read_model.current_exercise_session where samsung_uid = ?
                """,
                "uid-swim",
            ),
        )
        assertEquals(
            listOf(
                listOf("1", "1", "95.5", "FREESTYLE", "36", "1"),
                listOf("1", "2", "101.25", "BACKSTROKE", "40", "2"),
            ),
            rows(
                """
                select session_position::text, position::text, duration_seconds::text, stroke_type,
                    stroke_count::text, interval_number::text
                from read_model.current_exercise_swimming_interval where samsung_uid = ?
                order by session_position, position
                """,
                "uid-swim",
            ),
        )
    }

    @Test
    fun `marks the leg of the route each point belongs to`() {
        sendExercises(
            ExerciseEnvelopes.exercise(
                samsungUid = "uid-legs",
                sessions = listOf(
                    ExerciseEnvelopes.session(
                        route = listOf(
                            Envelopes.routePoint(latitude = "-23.5"),
                            Envelopes.routePoint(at = "2026-08-10T09:05:00Z", latitude = "-23.51"),
                        ),
                    ),
                    ExerciseEnvelopes.session(start = "2026-08-10T09:30:00Z", end = "2026-08-10T09:40:00Z"),
                    ExerciseEnvelopes.session(
                        start = "2026-08-10T09:40:00Z",
                        end = "2026-08-10T09:50:00Z",
                        route = listOf(Envelopes.routePoint(at = "2026-08-10T09:41:00Z", latitude = "-23.6")),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(listOf("1", "1", "-23.5"), listOf("1", "2", "-23.51"), listOf("3", "1", "-23.6")),
            locations("uid-legs").map { listOf(it[0], it[1], it[2]) },
        )
    }

    @Test
    fun `shows the constant of an exercise type it has no Portuguese name for`() {
        sendExercises(
            ExerciseEnvelopes.exercise(
                samsungUid = "uid-unnamed",
                type = "UNDERWATER_HOCKEY",
                sessions = listOf(ExerciseEnvelopes.session(values = mapOf("type" to "UNDERWATER_HOCKEY"))),
            ),
        )

        assertEquals("UNDERWATER_HOCKEY", current("uid-unnamed")[1])
        assertEquals(
            listOf(listOf("UNDERWATER_HOCKEY")),
            rows("select exercise_label from read_model.current_exercise_session where samsung_uid = ?", "uid-unnamed"),
        )
    }

    @Test
    fun `makes the version a Full Reconciliation reads again current, observed at the same instant`() {
        sendExercises(Envelopes.exercise(samsungUid = "uid-reread", observedAt = OBSERVED_AT, route = listOf(Envelopes.routePoint())))

        val reread = sendExercises(
            ExerciseEnvelopes.exercise(
                samsungUid = "uid-reread",
                observedAt = OBSERVED_AT,
                sessions = listOf(ExerciseEnvelopes.session(route = listOf(Envelopes.routePoint()))),
            ),
        )

        assertEquals(listOf("accepted"), statuses(reread.body))
        assertEquals(2, versionsOf("uid-reread").size)
        assertEquals(ExerciseEnvelopes.MAPPER_VERSION, current("uid-reread").last())
        assertEquals(1, countOf(rowsOf("current_exercise_session", "uid-reread")))
        assertEquals(listOf(listOf("1", "1")), locations("uid-reread").map { it.take(2) })
    }

    /** The outbox of an application updated mid-flight may still hold the version 1 it already delivered. */
    @Test
    fun `keeps version 2 current when the version 1 it replaced arrives again`() {
        val first = Envelopes.exercise(samsungUid = "uid-resent", observedAt = OBSERVED_AT)
        sendExercises(first)
        sendExercises(ExerciseEnvelopes.exercise(samsungUid = "uid-resent", observedAt = OBSERVED_AT))

        val resent = sendExercises(first)

        assertEquals(listOf("already_present"), statuses(resent.body))
        assertEquals(ExerciseEnvelopes.MAPPER_VERSION, current("uid-resent").last())
    }

    private fun current(samsungUid: String): List<String?> = rows(
        "select exercise_type, exercise_label, custom_title, mapper_version from read_model.current_exercise where samsung_uid = ?",
        samsungUid,
    ).single()

    private fun locations(samsungUid: String): List<List<String?>> = rows(
        """
        select session_position::text, position::text, latitude_degrees::text
        from read_model.current_exercise_location where samsung_uid = ? order by session_position, position
        """,
        samsungUid,
    )

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun rows(sql: String, samsungUid: String): List<List<String?>> = api.query { connection ->
        connection.createStatement().use { it.execute("set time zone 'UTC'") }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                }
            }
        }
    }

    private companion object {
        const val OBSERVED_AT = "2026-08-10T22:00:00Z"
    }
}
