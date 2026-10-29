package br.etc.victor.myhealthbridge.api

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Ingestions recorded before the API wrote their Health Category get it derived from what they stored:
 * an accepted or already present item, or else their inventory. One that stored neither stays without
 * a category, which the panels show as such.
 *
 * The history is written straight into the schema as it stood before, because no build of the API that
 * wrote it exists any more; the packaged API then migrates it on start, as a deploy would.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionHealthCategoryMigrationTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")

    private lateinit var directory: Path

    private lateinit var api: IngestionApi

    @BeforeAll
    fun migrateHistory() {
        postgres.start()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target(SCHEMA_BEFORE_CATEGORY)
            .load()
            .migrate()
        postgres.createConnection("").use(::writeHistory)

        directory = Files.createTempDirectory("ingestion-api")
        api = IngestionApi(postgres, directory).start()
    }

    @AfterAll
    fun stop() {
        api.stop()
        if (postgres.isRunning) postgres.stop()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `derives the category of a batch of health records from an accepted item`() {
        assertEquals("heart_rate", categoryOf(ACCEPTED_RECORD))
    }

    @Test
    fun `derives the category of a resend from an item already present`() {
        assertEquals("heart_rate", categoryOf(RESENT_RECORD))
    }

    @Test
    fun `derives the category of a batch of observed snapshots from its observation`() {
        assertEquals("steps", categoryOf(SNAPSHOT))
    }

    @Test
    fun `derives the category of an inventory that carried no item from the inventory`() {
        assertEquals("sleep", categoryOf(INVENTORY_ONLY))
    }

    @Test
    fun `leaves without a category an ingestion whose every item was rejected`() {
        assertEquals(null, categoryOf(REJECTED_ONLY))
        assertEquals(
            listOf(null, "1", "1", "0", "0", "1"),
            api.query { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        """
                        select health_category, contract_version, item_count, accepted_count,
                               already_present_count, rejected_count
                        from read_model.ingestion_outcome where ingestion_id = '$REJECTED_ONLY'
                        """.trimIndent(),
                    ).use { row ->
                        row.next()
                        (1..6).map { row.getString(it) }
                    }
                }
            },
        )
    }

    @Test
    fun `refuses a new ingestion that names no category`() {
        val refusal = runCatching {
            api.query { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into ingestion (id, ingestion_device_id, contract_version, item_count, received_at)
                        values ('00000000-0000-0000-0000-000000000006', 1, 1, 1, '2026-07-02T12:00:00Z')
                        """.trimIndent(),
                    )
                }
            }
        }.exceptionOrNull()

        assertTrue(refusal?.message.orEmpty().contains("ingestion_names_its_health_category"), refusal.toString())
    }

    private fun categoryOf(ingestionId: String): String? = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select health_category from ingestion where id = '$ingestionId'").use { row ->
                check(row.next()) { "ingestion $ingestionId disappeared" }
                row.getString(1)
            }
        }
    }

    private fun writeHistory(connection: Connection) = connection.createStatement().use { statement ->
        statement.execute(
            """
            insert into ingestion_device (id, device_label, token_digest, created_at)
            values (1, 'legacy-phone', '\x01', '2026-07-01T00:00:00Z');

            insert into health_record_identity (id, record_type, samsung_uid) values (1, 'heart_rate', 'uid-legacy');
            insert into observed_record_version (
                id, health_record_identity_id, content_digest, record_type, state, observed_at, observed_at_offset,
                mapper_version, envelope, first_received_at
            )
            values (1, 1, '\x02', 'heart_rate', 'present', '2026-07-01T12:00:00Z', '-03:00',
                    'samsung-health-heart-rate/1', '{}', '2026-07-01T12:05:00Z');

            insert into observed_snapshot (id, snapshot_type, window_start, window_end)
            values (1, 'steps', '2026-07-01T09:00:00', '2026-07-01T10:00:00');
            insert into snapshot_observation (
                id, observed_snapshot_id, content_digest, snapshot_type, observed_at, observed_at_offset,
                source_scope, mapper_version, empty, observation, first_received_at
            )
            values (1, 1, '\x03', 'steps', '2026-07-01T12:00:00Z', '-03:00', 'platform',
                    'samsung-health-steps/1', false, '{}', '2026-07-01T12:05:00Z');

            insert into ingestion (id, ingestion_device_id, contract_version, item_count, received_at) values
                ('$ACCEPTED_RECORD', 1, 1, 2, '2026-07-01T12:05:00Z'),
                ('$RESENT_RECORD', 1, 1, 1, '2026-07-01T12:10:00Z'),
                ('$SNAPSHOT', 1, 3, 1, '2026-07-01T12:05:00Z'),
                ('$INVENTORY_ONLY', 1, 2, 0, '2026-07-01T12:15:00Z'),
                ('$REJECTED_ONLY', 1, 1, 1, '2026-07-01T12:20:00Z');

            insert into ingestion_item (ingestion_id, position, status, observed_record_version_id, rejection_codes) values
                ('$ACCEPTED_RECORD', 0, 'rejected', null, '{invalid_payload}'),
                ('$ACCEPTED_RECORD', 1, 'accepted', 1, '{}'),
                ('$RESENT_RECORD', 0, 'already_present', 1, '{}'),
                ('$REJECTED_ONLY', 0, 'rejected', null, '{unsupported_mapper}');
            insert into ingestion_item (ingestion_id, position, status, snapshot_observation_id)
            values ('$SNAPSHOT', 0, 'accepted', 1);

            insert into declared_inventory (
                ingestion_id, record_type, interval_start, interval_end, read_at, declared_count, status, removal_count
            )
            values ('$INVENTORY_ONLY', 'sleep', '2026-06-30T00:00:00', '2026-07-01T00:00:00',
                    '2026-07-01T12:00:00Z', 0, 'concluded', 0);
            """.trimIndent(),
        )
    }

    private companion object {
        const val SCHEMA_BEFORE_CATEGORY = "15"

        const val ACCEPTED_RECORD = "00000000-0000-0000-0000-000000000001"
        const val RESENT_RECORD = "00000000-0000-0000-0000-000000000002"
        const val SNAPSHOT = "00000000-0000-0000-0000-000000000003"
        const val INVENTORY_ONLY = "00000000-0000-0000-0000-000000000004"
        const val REJECTED_ONLY = "00000000-0000-0000-0000-000000000005"
    }
}
