package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.Snapshots.Quantity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The activity summary as an Observed Snapshot of a local hour: the four totals Samsung Health merges
 * across the phone and the wearables, each kept with the unit it was measured in, and exposed by hour
 * through the read model. See ADR 0025.
 */
@Tag("integration")
class ActivitySummarySnapshotTest : IngestionApiTest() {

    @Test
    fun `exposes the hour's totals through the typed view of the current activity summary`() {
        val response = sendActivity(Snapshots.activitySummary(start = "2026-08-01T10:00:00", end = "2026-08-01T11:00:00"))

        assertEquals(listOf(emptyList<String>()), codes(response.body))
        val hour = current("2026-08-01T10:00:00")!!
        assertEquals("42.5", hour["active_calories_burned_kilocalories"])
        assertEquals("118.25", hour["calories_burned_kilocalories"])
        assertEquals("754.321", hour["active_time_seconds"])
        assertEquals("1310.7", hour["distance_meters"])
        assertEquals("2026-08-01 11:00:00", hour["window_end"])
        assertEquals("f", hour["partial"])
        assertEquals("platform", hour["source_scope"])
        assertEquals("samsung-health-activity-summary/1", hour["mapper_version"])
    }

    /** A daily total is a plain sum over the hours, which only holds if a missing total stays null. */
    @Test
    fun `keeps a total the source did not report as null, never as zero`() {
        sendActivity(
            Snapshots.activitySummary(
                start = "2026-08-02T10:00:00",
                end = "2026-08-02T11:00:00",
                content = Snapshots.activityTotals(distance = null, activeTime = null),
            ),
        )

        val hour = current("2026-08-02T10:00:00")!!
        assertNull(hour["distance_meters"])
        assertNull(hour["active_time_seconds"])
        assertEquals("118.25", hour["calories_burned_kilocalories"])
    }

    @Test
    fun `takes an hour observed as empty out of the typed view`() {
        val start = "2026-08-03T10:00:00"
        val end = "2026-08-03T11:00:00"
        sendActivity(Snapshots.activitySummary(start = start, end = end, observedAt = "2026-08-03T14:00:00Z"))
        sendActivity(Snapshots.activitySummary(start = start, end = end, observedAt = "2026-08-03T15:00:00Z", content = null))

        assertNull(current(start))
    }

    @Test
    fun `rejects an hour whose totals are missing, out of range or in another unit`() {
        val response = sendActivity(
            Snapshots.activitySummary(content = JsonObject(emptyMap())),
            Snapshots.activitySummary(content = Snapshots.activityTotals(distance = Quantity("1.3", "km"))),
            Snapshots.activitySummary(content = Snapshots.activityTotals(activeTime = Quantity("3601", "s"))),
            Snapshots.activitySummary(content = Snapshots.activityTotals(caloriesBurned = Quantity("-1", "kcal"))),
            Snapshots.activitySummary(content = textTotal()),
            Snapshots.activitySummary(start = "2026-08-04T10:00:00", end = "2026-08-05T10:00:00"),
            Snapshots.activitySummary(mapperVersion = "samsung-health-steps/1"),
        )

        assertEquals(
            listOf(
                listOf("invalid_payload"),
                listOf("invalid_unit"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                listOf("invalid_identity"),
                listOf("unsupported_mapper"),
            ),
            codes(response.body),
        )
    }

    private fun textTotal(): JsonObject = buildJsonObject {
        putJsonObject("distance") {
            put("value", "far")
            put("unit", "m")
        }
    }

    private fun sendActivity(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Snapshots.batch(snapshotType = Snapshots.ACTIVITY_SUMMARY, items = items.toList()), token)
            .also { assertEquals(200, it.status, it.body) }

    private fun current(windowStart: String): Map<String, String?>? = api.query { connection ->
        connection.prepareStatement(
            "select * from read_model.current_hourly_activity_summary where window_start = cast(? as timestamp)",
        ).use { statement ->
            statement.setString(1, windowStart)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null
                else (1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getString(it) }
            }
        }
    }
}
