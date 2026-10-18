package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A Source Removal concluded from what the application declares it saw, rather than from a removal the
 * changes feed reported: the only way a record removed while nobody was watching ever leaves the
 * Current Health Record.
 *
 * Every scenario declares a day of its own, because the database is shared by the whole class and an
 * inventory concludes over everything the API holds inside its interval.
 */
@Tag("integration")
class DeclaredAbsenceTest : IngestionApiTest() {

    @Test
    fun `concludes the removal of a known health record the inventory of its interval leaves out`() {
        send(heartRate("uid-kept", "2026-08-10T12:00:00Z"), heartRate("uid-gone", "2026-08-10T12:00:00Z"))

        val response = declare(listOf("uid-kept"), "2026-08-10")

        assertEquals(200, response.status, response.body)
        assertEquals("concluded" to 1, inventoryOf(response.body))
        assertEquals(listOf("present", "removed"), versionsOf("uid-gone").map { it.get("state") })
        assertEquals(listOf("present"), versionsOf("uid-kept").map { it.get("state") })
        assertEquals("removed", currentStateOf("uid-gone"))
        assertEquals("present", currentStateOf("uid-kept"))
    }

    /** The case no re-read can recover: the device kept nothing, and the record was gone before it looked. */
    @Test
    fun `concludes a removal that happened while the application held no local state`() {
        val before = api.provision("phone-before-reinstall")
        api.postBatch(Envelopes.batch(items = listOf(heartRate("uid-before-reinstall", "2026-08-01T12:00:00Z"))), before)
        val reinstalled = api.provision("phone-after-reinstall")

        val response = api.postBatch(Envelopes.inventoryBatch(emptyList(), "2026-08-01T00:00:00", "2026-08-02T00:00:00"), reinstalled)

        assertEquals("concluded" to 1, inventoryOf(response.body))
        assertEquals("removed", currentStateOf("uid-before-reinstall"))
    }

    /**
     * The interval is local time, the clock a history read is filtered by: 23:30 at -03:00 on the 3rd is
     * inside the 3rd although it is already the 4th in UTC, and 23:30 on the 2nd is outside it.
     */
    @Test
    fun `concludes nothing about a health record whose local start falls outside the interval`() {
        send(heartRate("uid-late-local", "2026-08-04T02:30:00Z"), heartRate("uid-day-before", "2026-08-03T02:30:00Z"))

        val response = declare(emptyList(), "2026-08-03")

        assertEquals("concluded" to 1, inventoryOf(response.body))
        assertEquals("removed", currentStateOf("uid-late-local"))
        assertEquals("present", currentStateOf("uid-day-before"))
    }

    /** A record the API learned of after the stretch was read, through the changes feed, was not there to be seen. */
    @Test
    fun `concludes nothing about a health record observed after the read of the interval began`() {
        send(heartRate("uid-created-during-read", "2026-08-04T12:00:00Z", observedAt = "2026-08-12T10:05:00Z"))

        val response = declare(emptyList(), "2026-08-04", readAt = "2026-08-12T10:00:00Z")

        assertEquals("concluded" to 0, inventoryOf(response.body))
        assertEquals("present", currentStateOf("uid-created-during-read"))
    }

    @Test
    fun `refuses to conclude anything from an inventory naming a health record it never stored`() {
        send(heartRate("uid-partial-known", "2026-08-05T12:00:00Z"))

        val response = declare(listOf("uid-never-delivered"), "2026-08-05")

        assertEquals(200, response.status, response.body)
        assertEquals("incomplete" to 0, inventoryOf(response.body))
        assertEquals(listOf("present"), versionsOf("uid-partial-known").map { it.get("state") })
    }

    @Test
    fun `stores no second version when the same inventory is delivered again`() {
        send(heartRate("uid-twice", "2026-08-06T12:00:00Z"))
        declare(emptyList(), "2026-08-06")

        val repeated = declare(emptyList(), "2026-08-06")

        assertEquals("concluded" to 0, inventoryOf(repeated.body))
        assertEquals(listOf("present", "removed"), versionsOf("uid-twice").map { it.get("state") })
    }

    @Test
    fun `records the removal as a version without content, dated like the version it ends`() {
        val observed = heartRate("uid-evidence", "2026-08-07T12:00:00Z", observedAt = "2026-08-07T22:00:00Z")
        send(observed)

        val ingestion = ingestionId(declare(emptyList(), "2026-08-07").body)

        val (present, removal) = versionsOf("uid-evidence")
        assertEquals(observed["state"], present.envelope()["state"], "the preserved version is untouched")
        val state = removal.envelope()["state"] as JsonObject
        assertEquals("removed", state["kind"]!!.jsonPrimitive.content)
        assertEquals(setOf("kind", "period"), state.keys, "a removal carries no biometric content")
        assertEquals("declared_absence", removal.envelope()["concludedBy"]!!.jsonPrimitive.content)
        assertEquals("2026-08-07T22:00:00Z", instantOf(removal.envelope()["observedAt"]))
        assertEquals(1, countOf("select count(*) from concluded_removal where ingestion_id = '$ingestion'"))
    }

    /** A removal concluded before the API learned that the record moved out of the interval is undone by the move. */
    @Test
    fun `makes a later edit at the source current again over a removal concluded from absence`() {
        send(heartRate("uid-moved", "2026-08-08T12:00:00Z", observedAt = "2026-08-08T22:00:00Z"))
        declare(emptyList(), "2026-08-08")

        send(heartRate("uid-moved", "2026-08-09T12:00:00Z", observedAt = "2026-08-09T22:00:00Z"))

        assertEquals("present", currentStateOf("uid-moved"))
    }

    @Test
    fun `answers an inventory it cannot read without refusing the observations beside it`() {
        val response = api.postBatch(
            Envelopes.inventoryBatch(
                samsungUids = emptyList(),
                from = "2026-07-02T00:00:00",
                until = "2026-07-01T00:00:00",
                items = listOf(heartRate("uid-beside-invalid", "2026-07-01T12:00:00Z")),
            ),
            token,
        )

        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals("invalid" to 0, inventoryOf(response.body))
    }

    @Test
    fun `publishes the range up to version 3 and keeps version 1 accepted, concluding nothing for it`() {
        send(heartRate("uid-old-client", "2026-07-02T12:00:00Z"))

        val range = Json.parseToJsonElement(api.get("/ingestion-contract").body).jsonObject
        val response = api.postBatch(
            Envelopes.inventoryBatch(
                samsungUids = emptyList(),
                from = "2026-07-02T00:00:00",
                until = "2026-07-03T00:00:00",
                contractVersion = 1,
                items = listOf(heartRate("uid-old-client-2", "2026-07-02T12:00:00Z")),
            ),
            token,
        )

        assertEquals("1", range["minimumVersion"]!!.jsonPrimitive.content)
        assertEquals("3", range["maximumVersion"]!!.jsonPrimitive.content)
        assertEquals(200, response.status, response.body)
        assertEquals(listOf("accepted"), statuses(response.body))
        assertNull(Json.parseToJsonElement(response.body).jsonObject["inventory"])
        assertEquals("present", currentStateOf("uid-old-client"))
    }

    @Test
    fun `still refuses an earlier version batch that carries nothing but an inventory`() {
        val response = api.postBatch(
            Envelopes.inventoryBatch(emptyList(), "2026-07-03T00:00:00", "2026-07-04T00:00:00", contractVersion = 1),
            token,
        )

        assertEquals(422, response.status)
        assertEquals("invalid_batch", problemCode(response.body))
    }

    /** A point measurement at [start], recorded at -03:00. */
    private fun heartRate(samsungUid: String, start: String, observedAt: String = "2026-08-10T22:00:00Z") =
        Envelopes.heartRate(samsungUid = samsungUid, start = start, end = start, observedAt = observedAt)

    /** Declares a whole local [day]. */
    private fun declare(
        samsungUids: List<String>,
        day: String,
        readAt: String = "2026-08-12T10:00:00Z",
    ): IngestionApi.Response {
        val from = java.time.LocalDate.parse(day)
        return api.postBatch(
            Envelopes.inventoryBatch(
                samsungUids = samsungUids,
                from = from.atStartOfDay().toString() + ":00",
                until = from.plusDays(1).atStartOfDay().toString() + ":00",
                readAt = readAt,
            ),
            token,
        )
    }

    private fun inventoryOf(body: String): Pair<String, Int> {
        val inventory = Json.parseToJsonElement(body).jsonObject["inventory"] as JsonObject
        return inventory["status"]!!.jsonPrimitive.content to inventory["removals"]!!.jsonPrimitive.content.toInt()
    }

    private fun instantOf(zoned: kotlinx.serialization.json.JsonElement?): String =
        (zoned as JsonObject)["instant"]!!.jsonPrimitive.content

    private fun currentStateOf(samsungUid: String): String = api.query { connection ->
        connection.prepareStatement(
            """
            select v.state from current_health_record c
            join observed_record_version v on v.id = c.observed_record_version_id
            join health_record_identity i on i.id = c.health_record_identity_id
            where i.samsung_uid = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "$samsungUid is not projected" }
                rows.getString(1)
            }
        }
    }
}
