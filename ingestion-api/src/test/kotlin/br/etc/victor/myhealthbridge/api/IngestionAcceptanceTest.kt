package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the Data Owner is promised: an accepted observation is preserved, a retry never duplicates it,
 * and a genuinely different observation of the same Health Record becomes another version.
 */
@Tag("integration")
class IngestionAcceptanceTest : IngestionApiTest() {

    @Test
    fun `preserves an accepted observation with its identity, digest and canonical envelope`() {
        val uid = "uid-preserved"

        val response = send(Envelopes.heartRate(samsungUid = uid))

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))

        val version = versionsOf(uid).single()
        assertEquals("heart_rate", version.get("record_type"))
        assertEquals("present", version.get("state"))
        assertEquals("-03:00", version.get("observed_at_offset"))
        assertEquals("samsung-health-heart-rate/1", version.get("mapper_version"))
        assertEquals(64, version.get("digest").length)

        val envelope = version.envelope()
        assertEquals(uid, envelope["samsungUid"]!!.jsonPrimitive.content)
        assertEquals("heart_rate", envelope["recordType"]!!.jsonPrimitive.content)
        assertEquals(
            "72",
            envelope["state"]!!.jsonObject["normalizedPayload"]!!
                .jsonObject["heartRate"]!!.jsonObject["value"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `records the ingestion and one result per submitted position`() {
        val response = send(Envelopes.heartRate(samsungUid = "uid-positions"), Envelopes.removal("uid-positions-2"))
        val ingestion = ingestionId(response.body)

        api.query { connection ->
            connection.prepareStatement(
                "select contract_version, item_count from ingestion where id = cast(? as uuid)",
            ).use { statement ->
                statement.setString(1, ingestion)
                statement.executeQuery().use { row ->
                    assertTrue(row.next(), "the ingestion was not recorded")
                    assertEquals(1, row.getInt("contract_version"))
                    assertEquals(2, row.getInt("item_count"))
                }
            }

            connection.prepareStatement(
                """
                select position, status, observed_record_version_id
                from ingestion_item where ingestion_id = cast(? as uuid) order by position
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ingestion)
                statement.executeQuery().use { row ->
                    repeat(2) { position ->
                        assertTrue(row.next(), "position $position was not recorded")
                        assertEquals(position, row.getInt("position"))
                        assertEquals("accepted", row.getString("status"))
                        assertNotEquals(0L, row.getLong("observed_record_version_id"))
                    }
                }
            }
        }
    }

    @Test
    fun `creates a new ingestion for every request, including a retry`() {
        val item = Envelopes.heartRate(samsungUid = "uid-retried")

        val first = ingestionId(send(item).body)
        val second = ingestionId(send(item).body)

        assertNotEquals(first, second)
    }

    @Test
    fun `answers already present when the same batch is sent again after a restart`() {
        val item = Envelopes.heartRate(samsungUid = "uid-restart")
        assertEquals(listOf("accepted"), statuses(send(item).body))

        api.restart()

        assertEquals(listOf("already_present"), statuses(send(item).body))
        assertEquals(1, versionsOf("uid-restart").size)
    }

    @Test
    fun `accepts the first occurrence inside a batch and reports the rest as already present`() {
        val item = Envelopes.heartRate(samsungUid = "uid-in-batch")

        val response = send(item, item, item)

        assertEquals(listOf("accepted", "already_present", "already_present"), statuses(response.body))
        assertEquals(1, versionsOf("uid-in-batch").size)
    }

    @Test
    fun `lets only one of two concurrent requests insert the same version`() {
        val body = Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-concurrent")))

        val pool = Executors.newFixedThreadPool(2)
        val answers = try {
            pool.invokeAll(List(2) { Callable { api.postBatch(body, token) } }).map { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdown()
        }

        assertEquals(listOf(200, 200), answers.map { it.status })
        assertEquals(listOf("accepted", "already_present"), answers.flatMap { statuses(it.body) }.sorted())
        assertEquals(1, versionsOf("uid-concurrent").size)
    }

    @Test
    fun `keeps a changed observation of the same health record as another version`() {
        val uid = "uid-versions"
        send(Envelopes.heartRate(samsungUid = uid))

        val variants = listOf(
            Envelopes.heartRate(samsungUid = uid, beatsPerMinute = "81"),
            Envelopes.heartRate(samsungUid = uid, sourceApp = "com.example.other"),
            Envelopes.heartRate(samsungUid = uid, observedOffset = "+02:00"),
            Envelopes.removal(samsungUid = uid),
        )
        variants.forEach { assertEquals(listOf("accepted"), statuses(send(it).body)) }

        assertEquals(1 + variants.size, versionsOf(uid).size)
        assertEquals(
            1,
            countOf("select count(*) from health_record_identity where samsung_uid = 'uid-versions'"),
            "a changed observation must not create another Health Record Identity",
        )
    }

    @Test
    fun `preserves a removal with and without its period, and never its content`() {
        val uid = "uid-removed"
        send(Envelopes.removal(samsungUid = uid, withPeriod = true))
        send(Envelopes.removal(samsungUid = uid))

        val removals = versionsOf(uid)
        assertEquals(2, removals.size)
        assertTrue(removals.all { it.get("state") == "removed" })
        assertTrue(removals.any { it.getOrNull("period_start") != null })
        assertTrue(removals.any { it.getOrNull("period_start") == null })
        assertTrue(
            removals.none { it.get("envelope").contains("Payload") },
            "a removal must carry no payload at all",
        )
    }

    @Test
    fun `stores nothing of a rejected item beyond its codes`() {
        val response = send(Envelopes.heartRate(samsungUid = "uid-rejected", unit = "bpm"))

        assertEquals(listOf("rejected"), statuses(response.body))
        assertEquals(listOf(listOf("invalid_unit")), codes(response.body))
        assertTrue(versionsOf("uid-rejected").isEmpty())
        assertEquals(
            0,
            countOf("select count(*) from health_record_identity where samsung_uid = 'uid-rejected'"),
            "a rejected item must not leave its Samsung UID behind",
        )
        assertTrue(
            countOf("select count(*) from ingestion_item where 'invalid_unit' = any (rejection_codes)") >= 1,
            "the rejection codes were not recorded",
        )
    }
}
