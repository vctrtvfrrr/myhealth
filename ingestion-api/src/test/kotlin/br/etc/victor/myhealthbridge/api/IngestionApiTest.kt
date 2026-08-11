package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path

/**
 * One disposable PostgreSQL and one packaged API process per test class.
 *
 * The database is never cleaned between tests of a class: deleting Observed Record Versions is exactly
 * what the schema forbids, and reusing an empty database per class keeps each scenario honest about
 * what it depends on.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IngestionApiTest(private val settings: Map<String, String> = emptyMap()) {

    protected val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17")

    protected lateinit var directory: Path
        private set

    protected lateinit var api: IngestionApi
        private set

    protected lateinit var token: String
        private set

    @BeforeAll
    fun startApi() {
        postgres.start()
        directory = Files.createTempDirectory("ingestion-api")
        api = IngestionApi(postgres, directory, settings).start()
        token = api.provision("phone")
    }

    @AfterAll
    fun stopApi() {
        api.stop()
        if (postgres.isRunning) postgres.stop()
        directory.toFile().deleteRecursively()
    }

    protected fun send(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(items = items.toList()), token)

    protected fun statuses(body: String): List<String> = results(body)
        .map { it.jsonObject["status"]!!.jsonPrimitive.content }

    protected fun codes(body: String): List<List<String>> = results(body)
        .map { result ->
            result.jsonObject["codes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }

    protected fun problemCode(body: String): String =
        Json.parseToJsonElement(body).jsonObject["code"]!!.jsonPrimitive.content

    protected fun ingestionId(body: String): String =
        Json.parseToJsonElement(body).jsonObject["ingestionId"]!!.jsonPrimitive.content

    protected fun versionsOf(samsungUid: String): List<StoredVersion> = api.query { connection ->
        connection.prepareStatement(
            """
            select v.state, v.record_type, v.observed_at_offset, v.mapper_version, v.envelope::text as envelope,
                   encode(v.content_digest, 'hex') as digest, v.period_start::text as period_start
            from observed_record_version v
            join health_record_identity i on i.id = v.health_record_identity_id
            where i.samsung_uid = ?
            order by v.id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            StoredVersion(
                                COLUMNS.associateWith { rows.getString(it) },
                            ),
                        )
                    }
                }
            }
        }
    }

    protected fun countOf(sql: String): Int = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { row ->
                row.next()
                row.getInt(1)
            }
        }
    }

    private fun results(body: String) = Json.parseToJsonElement(body).jsonObject["results"]!!.jsonArray

    class StoredVersion(private val columns: Map<String, String?>) {
        fun get(column: String): String = requireNotNull(columns[column]) { "$column was null" }
        fun getOrNull(column: String): String? = columns[column]
        fun envelope(): JsonObject = Json.parseToJsonElement(get("envelope")).jsonObject
    }

    private companion object {
        val COLUMNS = listOf(
            "state",
            "record_type",
            "observed_at_offset",
            "mapper_version",
            "envelope",
            "digest",
            "period_start",
        )
    }
}
