package br.etc.victor.myhealthbridge.api

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * V17 drops and recreates `current_heart_rate`, which takes the grant the read account had on it. An
 * installation granted before the deploy must still read it, and the new series view, without running
 * `read-access.sql` again: the default privileges it installed cover what the runtime role creates.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeartRateViewUpgradeTest {

    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")

    private lateinit var directory: Path

    private lateinit var api: IngestionApi

    private lateinit var token: String

    @BeforeAll
    fun grantBeforeTheDeploy() {
        postgres.start()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target(SCHEMA_BEFORE_HEART_RATE_RANGE)
            .load()
            .migrate()
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create role $READER login password '$READER_PASSWORD'")
                statement.execute(File(System.getProperty("repositoryRoot"), "docs/sql/read-access.sql").readText())
            }
        }

        directory = Files.createTempDirectory("ingestion-api")
        api = IngestionApi(postgres, directory).start()
        token = api.provision("phone")
    }

    @AfterAll
    fun stop() {
        api.stop()
        if (postgres.isRunning) postgres.stop()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `the read account still reads the heart rate views the deploy recreated`() {
        api.postBatch(Envelopes.batch(items = listOf(BinnedEnvelopes.heartRate(samsungUid = "uid-upgraded"))), token)

        DriverManager.getConnection(postgres.jdbcUrl, READER, READER_PASSWORD).use { reader ->
            listOf("current_heart_rate", "current_heart_rate_series").forEach { view ->
                val count = reader.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from read_model.$view where samsung_uid = 'uid-upgraded'")
                        .use { rows -> rows.next(); rows.getInt(1) }
                }
                assertEquals(1, count, view)
            }
        }
    }

    private companion object {
        const val SCHEMA_BEFORE_HEART_RATE_RANGE = "16"
        const val READER = "myhealth_read"
        const val READER_PASSWORD = "read-only-test"
    }
}
