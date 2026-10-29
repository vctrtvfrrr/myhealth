package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * What a manual reader or a future Grafana is given: the read model, and nothing else.
 *
 * The granting script is not restated here, it is executed from the repository, so the account this
 * test exercises is the one the documented procedure actually creates.
 */
@Tag("integration")
class ReadModelAccessTest : IngestionApiTest() {

    @Test
    fun `a read only account queries the view and can write nothing`() {
        send(Envelopes.heartRate(samsungUid = "uid-read-only"))
        api.postBatch(
            Envelopes.batch(
                recordType = Envelopes.EXERCISE_RECORD_TYPE,
                items = listOf(Envelopes.exercise(samsungUid = "uid-read-only", route = listOf(Envelopes.routePoint()))),
            ),
            token,
        )
        api.postBatch(
            Envelopes.batch(
                recordType = Envelopes.SLEEP_RECORD_TYPE,
                items = listOf(
                    Envelopes.sleep(
                        samsungUid = "uid-read-only",
                        sessions = listOf(Envelopes.sleepSession(stages = listOf(Envelopes.sleepStage()))),
                    ),
                ),
            ),
            token,
        )
        listOf(
            BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE to BinnedEnvelopes.bloodOxygen(samsungUid = "uid-read-only"),
            BinnedEnvelopes.SKIN_TEMPERATURE_RECORD_TYPE to BinnedEnvelopes.skinTemperature(samsungUid = "uid-read-only"),
            BinnedEnvelopes.BLOOD_GLUCOSE_RECORD_TYPE to BinnedEnvelopes.bloodGlucose(
                samsungUid = "uid-read-only",
                series = listOf(BinnedEnvelopes.glucoseReading()),
            ),
        ).forEach { (recordType, item) -> api.postBatch(Envelopes.batch(recordType = recordType, items = listOf(item)), token) }
        listOf(
            ConsumptionEnvelopes.NUTRITION_RECORD_TYPE to ConsumptionEnvelopes.nutrition(samsungUid = "uid-read-only"),
            ConsumptionEnvelopes.WATER_INTAKE_RECORD_TYPE to ConsumptionEnvelopes.waterIntake(samsungUid = "uid-read-only"),
            ConsumptionEnvelopes.FLOORS_CLIMBED_RECORD_TYPE to ConsumptionEnvelopes.floorsClimbed(samsungUid = "uid-read-only"),
        ).forEach { (recordType, item) -> api.postBatch(Envelopes.batch(recordType = recordType, items = listOf(item)), token) }
        mapOf(
            MonitorEnvelopes.IRREGULAR_HEART_RHYTHM_RECORD_TYPE to MonitorEnvelopes.irregularHeartRhythm(samsungUid = "uid-read-only"),
            MonitorEnvelopes.SLEEP_APNEA_RECORD_TYPE to MonitorEnvelopes.sleepApnea(samsungUid = "uid-read-only"),
            MonitorEnvelopes.ENERGY_SCORE_RECORD_TYPE to MonitorEnvelopes.energyScore(samsungUid = "uid-read-only"),
        ).forEach { (recordType, item) -> api.postBatch(Envelopes.batch(recordType = recordType, items = listOf(item)), token) }
        api.postBatch(
            Snapshots.batch(items = listOf(Snapshots.steps(start = "2026-07-01T09:00:00", end = "2026-07-01T10:00:00"))),
            token,
        )
        grantReadAccess()

        asReader { reader ->
            assertEquals(
                1,
                reader.count("select count(*) from read_model.current_heart_rate where samsung_uid = 'uid-read-only'"),
            )
            assertEquals(
                1,
                reader.count("select count(*) from read_model.current_exercise where samsung_uid = 'uid-read-only'"),
            )
            assertEquals(
                1,
                reader.count(
                    "select count(*) from read_model.current_exercise_location where samsung_uid = 'uid-read-only'",
                ),
            )
            listOf(
                "current_sleep",
                "current_sleep_session",
                "current_sleep_stage",
                "current_blood_oxygen",
                "current_blood_oxygen_series",
                "current_skin_temperature",
                "current_skin_temperature_series",
                "current_blood_glucose",
                "current_blood_glucose_series",
                "current_nutrition",
                "current_water_intake",
                "current_floors_climbed",
            ).forEach { view ->
                assertEquals(
                    1,
                    reader.count("select count(*) from read_model.$view where samsung_uid = 'uid-read-only'"),
                    view,
                )
            }

            assertEquals(
                1,
                reader.count(
                    "select count(*) from read_model.current_observed_snapshot where window_start = '2026-07-01T09:00:00'",
                ),
            )
            assertEquals(
                1,
                reader.count("select count(*) from read_model.current_hourly_steps where window_start = '2026-07-01T09:00:00'"),
            )
            listOf("current_irregular_heart_rhythm", "current_sleep_apnea", "current_energy_score").forEach { view ->
                assertEquals(1, reader.count("select count(*) from read_model.$view where samsung_uid = 'uid-read-only'"), view)
            }

            assertEquals(
                1,
                reader.count(
                    """
                    select count(*) from read_model.current_heart_rate
                    where samsung_uid = 'uid-read-only'
                      and read_model.local_bucket('day', period_start_local) <= period_end_local
                    """,
                ),
            )
            assertEquals(
                1,
                reader.count(
                    "select count(*) from read_model.current_sleep_stage where samsung_uid = 'uid-read-only' and start_at_local is not null",
                ),
            )

            listOf(
                "insert into health_record_identity (record_type, samsung_uid) values ('heart_rate', 'uid-forged')",
                "update observed_record_version set state = 'removed'",
                "delete from ingestion_item",
                "update ingestion_device set revoked_at = null",
                "delete from current_health_record",
                "delete from current_observed_snapshot",
                "insert into observed_snapshot (snapshot_type) values ('steps')",
            ).forEach { write ->
                assertTrue(reader.isDenied(write), "the read account was allowed to: $write")
            }

            assertTrue(
                reader.isDenied("select count(*) from snapshot_observation"),
                "the preserved snapshot observations are reachable only through the read model",
            )
            assertTrue(
                reader.isDenied("select count(*) from observed_record_version"),
                "the preserved envelopes are reachable only through the read model",
            )
        }
    }

    private fun grantReadAccess() = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.execute("create role $READER login password '$READER_PASSWORD'")
            statement.execute(File(System.getProperty("repositoryRoot"), "docs/sql/read-access.sql").readText())
        }
    }

    private fun <T> asReader(block: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, READER, READER_PASSWORD).use(block)

    private fun Connection.count(sql: String): Int = createStatement().use { statement ->
        statement.executeQuery(sql).use { row ->
            row.next()
            row.getInt(1)
        }
    }

    private fun Connection.isDenied(sql: String): Boolean =
        runCatching { createStatement().use { it.execute(sql) } }
            .exceptionOrNull()
            ?.message
            .orEmpty()
            .contains("permission denied")

    private companion object {
        const val READER = "myhealth_read"
        const val READER_PASSWORD = "read-only"
    }
}
