package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The synchronization as the API saw it, readable by the read account: every Ingestion with the Health
 * Category of its batch, its items by result, its inventory and the removals it concluded, and every
 * rejection code it recorded. No health content and no Ingestion Device reach these views.
 */
@Tag("integration")
class OperationalReadModelTest : IngestionApiTest() {

    @Test
    fun `records the health category of a batch of health records with its items by result`() {
        val first = api.postBatch(
            Envelopes.batch(
                items = listOf(
                    Envelopes.heartRate(samsungUid = "uid-operational"),
                    Envelopes.heartRate(samsungUid = "uid-operational-rejected", mapperVersion = "heart_rate/999"),
                ),
            ),
            token,
        )
        val resent = send(Envelopes.heartRate(samsungUid = "uid-operational"))

        assertEquals(
            listOf(Envelopes.RECORD_TYPE, "1", "2", "1", "0", "1", null, null),
            outcomeOf(ingestionId(first.body)),
        )
        assertEquals(
            listOf(Envelopes.RECORD_TYPE, "1", "1", "0", "1", "0", null, null),
            outcomeOf(ingestionId(resent.body)),
        )
        assertEquals(Envelopes.RECORD_TYPE, categoryStoredFor(ingestionId(first.body)))
    }

    @Test
    fun `records the health category of a batch whose every item was rejected`() {
        val response = api.postBatch(
            Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-all-rejected", mapperVersion = "heart_rate/999"))),
            token,
        )

        assertEquals(listOf(Envelopes.RECORD_TYPE, "1", "1", "0", "0", "1", null, null), outcomeOf(ingestionId(response.body)))
        assertEquals(
            listOf(Triple(0, "unsupported_mapper", Envelopes.RECORD_TYPE)),
            rejectionsOf(ingestionId(response.body)),
        )
    }

    @Test
    fun `records the snapshot type of a batch of observed snapshots`() {
        val response = api.postBatch(
            Snapshots.batch(items = listOf(Snapshots.steps(start = "2026-08-20T09:00:00", end = "2026-08-20T10:00:00"))),
            token,
        )

        assertEquals(listOf(Snapshots.STEPS, "3", "1", "1", "0", "0", null, null), outcomeOf(ingestionId(response.body)))
    }

    @Test
    fun `exposes the inventory of an ingestion by status with the removals it concluded`() {
        send(Envelopes.heartRate(samsungUid = "uid-operational-gone", start = "2026-08-21T12:00:00Z", end = "2026-08-21T12:00:00Z"))

        val concluded = api.postBatch(
            Envelopes.inventoryBatch(emptyList(), "2026-08-21T00:00:00", "2026-08-22T00:00:00", "2026-08-23T10:00:00Z"),
            token,
        )
        val incomplete = api.postBatch(
            Envelopes.inventoryBatch(listOf("uid-never-stored"), "2026-08-22T00:00:00", "2026-08-23T00:00:00", "2026-08-23T10:00:00Z"),
            token,
        )

        assertEquals(
            listOf(Envelopes.RECORD_TYPE, "2", "0", "0", "0", "0", "concluded", "1"),
            outcomeOf(ingestionId(concluded.body)),
        )
        assertEquals(
            listOf(Envelopes.RECORD_TYPE, "2", "0", "0", "0", "0", "incomplete", "0"),
            outcomeOf(ingestionId(incomplete.body)),
        )
    }

    @Test
    fun `exposes one row for every rejection code of every rejected item`() {
        val response = api.postBatch(
            Envelopes.batch(
                items = listOf(
                    Envelopes.heartRate(samsungUid = "uid-operational-codes"),
                    Envelopes.heartRate(samsungUid = "", mapperVersion = "heart_rate/999"),
                    Envelopes.heartRate(samsungUid = "uid-operational-offset", offset = "not-an-offset"),
                ),
            ),
            token,
        )
        val expected = codes(response.body).flatMapIndexed { position, codes ->
            codes.map { code -> Triple(position, code, Envelopes.RECORD_TYPE) }
        }.sortedWith(byPositionAndCode)

        assertTrue(expected.size > 2, "the scenario should reject an item for more than one reason")
        assertEquals(expected, rejectionsOf(ingestionId(response.body)))
    }

    @Test
    fun `exposes neither health content nor the ingestion device`() {
        assertEquals(
            listOf(
                "ingestion_id",
                "received_at",
                "health_category",
                "contract_version",
                "item_count",
                "accepted_count",
                "already_present_count",
                "rejected_count",
                "inventory_status",
                "concluded_removal_count",
            ),
            columnsOf("ingestion_outcome"),
        )
        assertEquals(
            listOf("ingestion_id", "received_at", "health_category", "contract_version", "position", "rejection_code"),
            columnsOf("ingestion_rejection"),
        )
    }

    private fun outcomeOf(ingestionId: String): List<String?> = api.query { connection ->
        connection.prepareStatement(
            """
            select health_category, contract_version, item_count, accepted_count, already_present_count,
                   rejected_count, inventory_status, concluded_removal_count
            from read_model.ingestion_outcome
            where ingestion_id = cast(? as uuid)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ingestionId)
            statement.executeQuery().use { row ->
                check(row.next()) { "no outcome for ingestion $ingestionId" }
                (1..8).map { row.getString(it) }.also { check(!row.next()) { "more than one outcome row" } }
            }
        }
    }

    private fun rejectionsOf(ingestionId: String): List<Triple<Int, String, String?>> = api.query { connection ->
        connection.prepareStatement(
            """
            select position, rejection_code, health_category
            from read_model.ingestion_rejection
            where ingestion_id = cast(? as uuid)
            order by position, rejection_code
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ingestionId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(Triple(rows.getInt(1), rows.getString(2), rows.getString(3))) }
            }
        }
    }.sortedWith(byPositionAndCode)

    private fun categoryStoredFor(ingestionId: String): String? = api.query { connection ->
        connection.prepareStatement("select health_category from ingestion where id = cast(? as uuid)").use { statement ->
            statement.setString(1, ingestionId)
            statement.executeQuery().use { row ->
                row.next()
                row.getString(1)
            }
        }
    }

    private fun columnsOf(view: String): List<String> = api.query { connection ->
        connection.prepareStatement(
            """
            select column_name from information_schema.columns
            where table_schema = 'read_model' and table_name = ?
            order by ordinal_position
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, view)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private val byPositionAndCode = compareBy<Triple<Int, String, String?>>({ it.first }, { it.second })
}
