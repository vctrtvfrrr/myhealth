package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager

/**
 * The Data Owner's final control over the remote copy: everything goes, and the import starts over.
 *
 * The scenario is the documented procedure against a disposable database: purge with the API stopped,
 * start it again so it migrates from empty, provision the device again, grant the read account again
 * with the same file the operator runs, and deliver the accessible history twice, as a Full
 * Reconciliation does. Each test leaves the database migrated, so neither depends on running first.
 */
@Tag("integration")
class DatabasePurgeTest : IngestionApiTest() {

    private val history = listOf(
        Envelopes.batch(
            items = listOf(
                Envelopes.heartRate(samsungUid = "uid-purged-heart-rate"),
                Envelopes.heartRate(samsungUid = "uid-purged-heart-rate", observedAt = "2026-08-11T10:00:00Z"),
            ),
        ),
        Envelopes.batch(
            recordType = Envelopes.EXERCISE_RECORD_TYPE,
            items = listOf(Envelopes.exercise(samsungUid = "uid-purged-exercise", route = listOf(Envelopes.routePoint()))),
        ),
        Envelopes.batch(
            recordType = Envelopes.SLEEP_RECORD_TYPE,
            items = listOf(
                Envelopes.sleep(
                    samsungUid = "uid-purged-sleep",
                    sessions = listOf(Envelopes.sleepSession(stages = listOf(Envelopes.sleepStage()))),
                ),
            ),
        ),
    )

    @Test
    fun `refuses to purge when the confirmation names another database`() {
        val device = api.provision("phone-kept-by-refusal")
        api.postBatch(Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-kept"))), device)

        val refused = api.attempt("database", "purge", "another-database")

        assertEquals(1, refused.exitCode)
        assertEquals(1, versionsOf("uid-kept").size)
        assertEquals(1, countOf("select count(*) from read_model.current_heart_rate where samsung_uid = 'uid-kept'"))
    }

    @Test
    fun `purges every schema it owns and a restarted api reimports the history without duplicates`() {
        deliver(token)
        val before = stored()
        grantReadAccess()

        api.stop()
        val purged = api.administer("database", "purge", postgres.databaseName)

        assertTrue(purged.contains(DatabaseAdmin.PURGED), purged)
        assertEquals(0, countOf("select count(*) from pg_namespace where nspname = 'read_model'"))
        assertEquals(0, countOf("select count(*) from pg_class where relnamespace = current_schema()::regnamespace"))
        assertEquals(0, countOf("select count(*) from pg_proc where pronamespace = current_schema()::regnamespace"))
        api.administer("database", "purge", postgres.databaseName)

        api.start()
        assertEquals(200, api.get("/ready").status)
        assertEquals(0, countOf("select count(*) from flyway_schema_history where not success"))
        assertEquals(401, api.postBatch(history.first(), token).status)

        val device = api.provision("phone")
        val reimported = deliver(device)
        assertEquals(before, stored())
        val reconciled = deliver(device)
        assertEquals(before, stored())

        assertNotEquals(0, reimported)
        assertEquals(0, reconciled)
        grantReadAccess()
        assertEquals(before.currentHeartRates, readerCount("select count(*) from read_model.current_heart_rate $PURGED_ONLY"))
    }

    private data class Stored(val versions: Int, val currentHeartRates: Int, val currentSleepStages: Int)

    /** Delivers the whole history and answers how many observations were new to the API. */
    private fun deliver(device: String): Int = history.sumOf { batch ->
        val response = api.postBatch(batch, device)
        assertEquals(200, response.status, response.body)
        statuses(response.body).count { it == "accepted" }
    }

    private fun stored() = Stored(
        versions = countOf(
            "select count(*) from observed_record_version v " +
                "join health_record_identity i on i.id = v.health_record_identity_id $PURGED_ONLY",
        ),
        currentHeartRates = countOf("select count(*) from read_model.current_heart_rate $PURGED_ONLY"),
        currentSleepStages = countOf("select count(*) from read_model.current_sleep_stage $PURGED_ONLY"),
    )

    /** The read account is a role, so it outlives the purge; only what it was granted goes. */
    private fun grantReadAccess() = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                "do $$ begin if not exists (select from pg_roles where rolname = '$READER') then " +
                    "create role $READER login password '$READER_PASSWORD'; end if; end $$",
            )
            statement.execute(File(System.getProperty("repositoryRoot"), "docs/sql/read-access.sql").readText())
        }
    }

    private fun readerCount(sql: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, READER, READER_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { row ->
                    row.next()
                    row.getInt(1)
                }
            }
        }

    private companion object {
        const val READER = "myhealth_read"
        const val READER_PASSWORD = "read-only"
        const val PURGED_ONLY = "where samsung_uid like 'uid-purged-%'"
    }
}
