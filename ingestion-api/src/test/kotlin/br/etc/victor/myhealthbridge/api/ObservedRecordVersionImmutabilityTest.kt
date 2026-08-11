package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * The runtime role holds the DDL rights this deployment traded away least privilege for, so the
 * database itself has to refuse to rewrite history.
 */
@Tag("integration")
class ObservedRecordVersionImmutabilityTest : IngestionApiTest() {

    @Test
    fun `refuses to update or delete an observed record version, and keeps it intact`() {
        val uid = "uid-immutable"
        send(Envelopes.heartRate(samsungUid = uid))
        val stored = versionsOf(uid).single()

        listOf(
            "update observed_record_version set state = 'removed'",
            "delete from observed_record_version",
            "delete from observed_record_version where false",
            "truncate observed_record_version cascade",
        ).forEach { mutation -> assertTrue(fails(mutation), "the database allowed: $mutation") }

        val survivor = versionsOf(uid).single()
        assertEquals(stored.get("digest"), survivor.get("digest"))
        assertEquals(stored.get("envelope"), survivor.get("envelope"))
        assertEquals("present", survivor.get("state"))
    }

    private fun fails(sql: String): Boolean = api.query { connection ->
        runCatching { connection.createStatement().use { it.execute(sql) } }
            .exceptionOrNull()
            .let { it is SQLException && it.message.orEmpty().contains("immutable") }
    }
}
