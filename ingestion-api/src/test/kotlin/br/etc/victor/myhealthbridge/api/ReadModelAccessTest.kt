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
        grantReadAccess()

        asReader { reader ->
            assertEquals(
                1,
                reader.count("select count(*) from read_model.current_heart_rate where samsung_uid = 'uid-read-only'"),
            )

            listOf(
                "insert into health_record_identity (record_type, samsung_uid) values ('heart_rate', 'uid-forged')",
                "update observed_record_version set state = 'removed'",
                "delete from ingestion_item",
                "update ingestion_device set revoked_at = null",
                "delete from current_health_record",
            ).forEach { write ->
                assertTrue(reader.isDenied(write), "the read account was allowed to: $write")
            }

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
