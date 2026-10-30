package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * The lines the API really writes about a refused or an unavailable batch, read by the Loki panels of
 * the operational dashboard. The database is taken away half way through, as in [IngestionUnavailableTest].
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OperationalLogsTest : IngestionApiTest() {

    private val queries = OperationalLogQueries.read()

    @Test
    @Order(1)
    fun `neither counts nor shows the line of an ingestion that was stored`() {
        assertEquals(200, send(Envelopes.heartRate(samsungUid = "uid-stored")).status)
        val line = lineWith("ingestion=")

        assertEquals(emptyList<Map<String, String>>(), queries.counted(COUNTS, line))
        assertFalse(queries.shows(LOGS, line), line)
    }

    @Test
    @Order(2)
    fun `counts a refused batch by its code and status, and shows its line`() {
        api.post(body = """{"nonsense":true}""".toByteArray(), token = token)
        val line = lineWith("ingestion refused")

        assertEquals(listOf(mapOf("code" to "invalid_request", "status" to "400")), queries.counted(COUNTS, line))
        assertTrue(queries.shows(LOGS, line), line)
    }

    @Test
    @Order(3)
    fun `counts an unavailable ingestion by its code and SQL state, and shows its line`() {
        postgres.stop()
        assertEquals(503, send(Envelopes.heartRate(samsungUid = "uid-after-loss")).status)
        val line = lineWith("ingestion unavailable")

        assertEquals(
            listOf(mapOf("code" to "ingestion_temporarily_unavailable", "sqlState" to line.substringAfter("sqlState=").trim())),
            queries.counted(COUNTS, line),
        )
        assertTrue(queries.shows(LOGS, line), line)
    }

    private fun lineWith(text: String): String = api.logs().lineSequence().last { it.contains(text) }

    private companion object {
        const val COUNTS = "Recusas e indisponibilidades por código"
        const val LOGS = "Linhas de recusa, indisponibilidade e startup"
    }
}
