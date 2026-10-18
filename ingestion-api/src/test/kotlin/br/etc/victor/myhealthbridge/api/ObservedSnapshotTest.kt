package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Observed Snapshots as the ingestion keeps them: identified by their type and Snapshot Window, dated by
 * when the application read them, and stored only when they differ from the observation before them in
 * observation time. See ADR 0025.
 */
@Tag("integration")
class ObservedSnapshotTest : IngestionApiTest() {

    @Test
    fun `stores a snapshot observation once and answers an identical resend as already present`() {
        val hour = Snapshots.steps(start = "2026-08-01T10:00:00", end = "2026-08-01T11:00:00", steps = 512)

        val first = sendSnapshots(hour)
        val resent = sendSnapshots(hour)

        assertEquals(200, first.status, first.body)
        assertEquals(listOf("accepted"), statuses(first.body))
        assertEquals(listOf("already_present"), statuses(resent.body))
        assertEquals(listOf("512"), stepsObservedAt("2026-08-01T10:00:00"))
    }

    /** The comparison is with the predecessor, not with every observation ever held. */
    @Test
    fun `stores a value that returns to one seen before, A then B then A`() {
        val start = "2026-08-02T10:00:00"
        val end = "2026-08-02T11:00:00"

        val answers = listOf(
            Snapshots.steps(start = start, end = end, observedAt = "2026-08-02T14:00:00Z", steps = 100),
            Snapshots.steps(start = start, end = end, observedAt = "2026-08-02T15:00:00Z", steps = 100),
            Snapshots.steps(start = start, end = end, observedAt = "2026-08-02T16:00:00Z", steps = 250),
            Snapshots.steps(start = start, end = end, observedAt = "2026-08-02T17:00:00Z", steps = 100),
        ).map { statuses(sendSnapshots(it).body).single() }

        assertEquals(listOf("accepted", "already_present", "accepted", "accepted"), answers)
        assertEquals(listOf("100", "250", "100"), stepsObservedAt(start))
        assertEquals("100", current(start)!!["steps"])
    }

    /** Arriving late is compared with the predecessor in observation time and never becomes current. */
    @Test
    fun `compares an observation that arrives late with its predecessor in observation time`() {
        val start = "2026-08-03T10:00:00"
        val end = "2026-08-03T11:00:00"
        sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-03T14:00:00Z", steps = 100))
        sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-03T18:00:00Z", steps = 300))

        val sameAsItsPredecessor =
            sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-03T15:00:00Z", steps = 100))
        val differentFromItsPredecessor =
            sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-03T16:00:00Z", steps = 200))

        assertEquals(listOf("already_present"), statuses(sameAsItsPredecessor.body))
        assertEquals(listOf("accepted"), statuses(differentFromItsPredecessor.body))
        assertEquals(listOf("100", "200", "300"), stepsObservedAt(start))
        assertEquals("300", current(start)!!["steps"])
    }

    /** Only an empty observation lets a total deleted at the source stop being current. */
    @Test
    fun `keeps an empty window as current and takes it out of the typed view`() {
        val start = "2026-08-04T10:00:00"
        val end = "2026-08-04T11:00:00"
        sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-04T14:00:00Z", steps = 80))

        val emptied = sendSnapshots(
            Snapshots.steps(start = start, end = end, observedAt = "2026-08-04T15:00:00Z", steps = null),
        )
        val emptiedAgain = sendSnapshots(
            Snapshots.steps(start = start, end = end, observedAt = "2026-08-04T16:00:00Z", steps = null, explicitEmpty = true),
        )

        assertEquals(listOf("accepted"), statuses(emptied.body))
        assertEquals(listOf("already_present"), statuses(emptiedAgain.body), "an explicit null is the same empty window")
        assertEquals(listOf("80", null), stepsObservedAt(start))
        val generic = currentObservedSnapshot(start)!!
        assertEquals("t", generic["empty"])
        assertNull(generic["content"])
        assertNull(current(start), "an hour observed as empty is not an hour of steps")
    }

    @Test
    fun `tells a partial window from a closed one in local time`() {
        sendSnapshots(
            Snapshots.steps(
                start = "2026-08-05T14:00:00",
                end = "2026-08-05T15:00:00",
                observedAt = "2026-08-05T17:30:00Z",
                observedOffset = "-03:00",
                steps = 40,
            ),
            Snapshots.steps(
                start = "2026-08-05T13:00:00",
                end = "2026-08-05T14:00:00",
                observedAt = "2026-08-05T17:30:00Z",
                observedOffset = "-03:00",
                steps = 900,
            ),
        )

        assertEquals("t", current("2026-08-05T14:00:00")!!["partial"], "read at 14:30 local, before the hour ended")
        assertEquals("f", current("2026-08-05T13:00:00")!!["partial"])
        val generic = currentObservedSnapshot("2026-08-05T14:00:00")!!
        assertEquals("steps", generic["snapshot_type"])
        assertEquals("2026-08-05 15:00:00", generic["window_end"])
        assertEquals("platform", generic["source_scope"])
        assertEquals("""{"steps": 40}""", generic["content"])
    }

    @Test
    fun `identifies a window by its local time, however its seconds were written`() {
        sendSnapshots(Snapshots.steps(start = "2026-08-06T10:00:00", end = "2026-08-06T11:00:00", steps = 7))

        val resent = sendSnapshots(Snapshots.steps(start = "2026-08-06T10:00", end = "2026-08-06T11:00", steps = 7))

        assertEquals(listOf("already_present"), statuses(resent.body))
    }

    @Test
    fun `rejects an item whose window, scope, time, mapper or content cannot be a step snapshot`() {
        val response = sendSnapshots(
            Snapshots.steps(start = "2026-08-07T10:30:00", end = "2026-08-07T11:30:00"),
            Snapshots.steps(start = "2026-08-07T10:00:00", end = "2026-08-07T12:00:00"),
            Snapshots.steps(sourceScope = "watch"),
            Snapshots.steps(observedOffset = "Z"),
            Snapshots.steps(mapperVersion = "samsung-health-steps/99"),
            Snapshots.steps(steps = -1),
            Snapshots.steps(start = "2026-08-07T13:00:00", end = "2026-08-07T14:00:00"),
        )

        assertEquals(200, response.status, response.body)
        assertEquals(
            listOf(
                listOf("invalid_identity"),
                listOf("invalid_identity"),
                listOf("invalid_provenance"),
                listOf("invalid_offset"),
                listOf("unsupported_mapper"),
                listOf("invalid_payload"),
                emptyList(),
            ),
            codes(response.body),
        )
        assertEquals(listOf("1234"), stepsObservedAt("2026-08-07T13:00:00"))
    }

    @Test
    fun `rejects every item of a snapshot type this api does not keep`() {
        val response = api.postBatch(Snapshots.batch(snapshotType = "floors", items = listOf(Snapshots.steps())), token)

        assertEquals(listOf(listOf("unsupported_record_type")), codes(response.body))
    }

    @Test
    fun `refuses a snapshot batch that also names a record type, or carries nothing`() {
        val both = buildJsonObject {
            put("contractVersion", 3)
            put("snapshotType", "steps")
            put("recordType", "heart_rate")
            put("items", JsonArray(listOf(Snapshots.steps())))
        }.toString()

        assertEquals("invalid_batch", problemCode(api.postBatch(both, token).body))
        assertEquals("invalid_batch", problemCode(api.postBatch(Snapshots.batch(items = emptyList()), token).body))
    }

    /** Versions 1 and 2 stay what they were: a snapshot type is an unknown property there. */
    @Test
    fun `reads a version 2 batch as a batch of health records whatever it names`() {
        val response = api.postBatch(Snapshots.batch(contractVersion = 2, items = listOf(Snapshots.steps())), token)

        assertEquals(422, response.status)
        assertEquals("invalid_batch", problemCode(response.body))
    }

    @Test
    fun `preserves a property a newer client added, inside what makes two observations the same`() {
        val start = "2026-08-08T10:00:00"
        val plain = Snapshots.steps(start = start, end = "2026-08-08T11:00:00", steps = 5)
        sendSnapshots(plain)

        val extended = sendSnapshots(JsonObject(plain + ("futureField" to JsonPrimitive("x"))))

        assertEquals(listOf("accepted"), statuses(extended.body))
        assertEquals(2, stepsObservedAt(start).size)
    }

    @Test
    fun `rebuilds the current observed snapshots from the preserved observations, identically`() {
        val start = "2026-08-09T10:00:00"
        val end = "2026-08-09T11:00:00"
        sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-09T14:00:00Z", steps = 1))
        sendSnapshots(Snapshots.steps(start = start, end = end, observedAt = "2026-08-09T15:00:00Z", steps = 2))
        val incremental = projection()

        val output = api.administer("projection", "rebuild")

        assertEquals(incremental.size, projectedSnapshots(output))
        assertEquals(incremental, projection())
        assertEquals("2", current(start)!!["steps"])
    }

    @Test
    fun `refuses to update or delete a snapshot observation`() {
        sendSnapshots(Snapshots.steps(start = "2026-08-11T10:00:00", end = "2026-08-11T11:00:00"))

        listOf(
            "update snapshot_observation set empty = true",
            "delete from snapshot_observation",
            "truncate snapshot_observation cascade",
        ).forEach { mutation ->
            val refused = api.query { connection ->
                runCatching { connection.createStatement().use { it.execute(mutation) } }.exceptionOrNull()
            }
            assertTrue(refused?.message.orEmpty().contains("immutable"), "the database allowed: $mutation")
        }
    }

    /** A step total is not a Health Record, so nothing about it reaches their tables or views. */
    @Test
    fun `presents no snapshot as a health record`() {
        sendSnapshots(Snapshots.steps(start = "2026-08-12T10:00:00", end = "2026-08-12T11:00:00"))

        assertEquals(0, countOf("select count(*) from health_record_identity where record_type = 'steps'"))
        assertEquals(0, countOf("select count(*) from observed_record_version where record_type = 'steps'"))
    }

    private fun sendSnapshots(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Snapshots.batch(items = items.toList()), token)

    private fun projectedSnapshots(output: String): Int = output.lineSequence()
        .first { it.startsWith(ProjectionAdmin.SNAPSHOTS_PROJECTED_PREFIX) }
        .removePrefix(ProjectionAdmin.SNAPSHOTS_PROJECTED_PREFIX)
        .trim()
        .toInt()

    private fun projection(): List<Pair<Long, Long>> = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select observed_snapshot_id, snapshot_observation_id from current_observed_snapshot order by 1",
            ).use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1) to rows.getLong(2)) }
            }
        }
    }

    private fun current(windowStart: String): Map<String, String?>? =
        row("select * from read_model.current_hourly_steps where window_start = cast(? as timestamp)", windowStart)

    private fun currentObservedSnapshot(windowStart: String): Map<String, String?>? = row(
        "select * from read_model.current_observed_snapshot where snapshot_type = 'steps' and window_start = cast(? as timestamp)",
        windowStart,
    )

    private fun row(sql: String, parameter: String): Map<String, String?>? = api.query { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, parameter)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null
                else (1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getString(it) }
            }
        }
    }

    /** The step counts stored for one window, in observation time, an empty observation as null. */
    private fun stepsObservedAt(windowStart: String): List<String?> = api.query { connection ->
        connection.prepareStatement(
            """
            select o.observation -> 'content' ->> 'steps'
            from snapshot_observation o
            join observed_snapshot s on s.id = o.observed_snapshot_id
            where s.snapshot_type = 'steps' and s.window_start = cast(? as timestamp)
            order by o.observed_at, o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, windowStart)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }
}
