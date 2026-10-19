package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.LocalInterval
import br.etc.victor.myhealthbridge.contract.SnapshotBatch
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The activity summary of one local hour, rendered from synthetic aggregates. Every total is invented. */
class ActivitySummaryMapperTest {

    private val window = SnapshotWindow(
        start = LocalDateTime.parse("2026-08-10T14:00:00"),
        end = LocalDateTime.parse("2026-08-10T15:00:00"),
    )

    private val readAt = ZonedInstant("2026-08-10T18:20:00Z", "-03:00")

    @Test
    fun `renders the hour's four totals with their units`() {
        val observation = ActivitySummaryMapper.map(window, activity(), readAt)

        assertEquals(LocalInterval("2026-08-10T14:00:00", "2026-08-10T15:00:00"), observation.window)
        assertEquals(readAt, observation.observedAt)
        assertEquals(SourceScope.PLATFORM, observation.sourceScope)
        assertEquals("samsung-health-activity-summary/1", observation.mapperVersion)
        assertEquals(
            """{"activeCaloriesBurned":{"value":42.5,"unit":"kcal"},"caloriesBurned":{"value":118.25,"unit":"kcal"},"activeTime":{"value":754.321,"unit":"s"},"distance":{"value":1310.7,"unit":"m"}}""",
            observation.content.toString(),
        )
    }

    @Test
    fun `renders an hour without an aggregate as an empty observation`() {
        assertNull(ActivitySummaryMapper.map(window, aggregate = null, readAt).content)
    }

    /** A total the source did not report for the hour is absent, never an invented zero. */
    @Test
    fun `leaves out a total the source did not report`() {
        val observation = ActivitySummaryMapper.map(
            window,
            SourceValue.Nested(mapOf(ActivitySummaryMapper.TOTAL_CALORIES_BURNED to number("80"))),
            readAt,
        )

        assertEquals("""{"caloriesBurned":{"value":80,"unit":"kcal"}}""", observation.content.toString())
    }

    /** Content the API will refuse, so it becomes a Mapping Pendency instead of a guessed total. */
    @Test
    fun `renders an aggregate that holds no total as content without one`() {
        assertEquals(JsonObject(emptyMap()), ActivitySummaryMapper.map(window, number("12"), readAt).content)
        assertEquals(
            JsonObject(emptyMap()),
            ActivitySummaryMapper.map(
                window,
                SourceValue.Nested(mapOf(ActivitySummaryMapper.TOTAL_DISTANCE to SourceValue.Text("far"))),
                readAt,
            ).content,
        )
    }

    /**
     * The exact document a batch of activity summaries is sent as. The same literal is posted at
     * `AppSnapshotReadModelTest` in `ingestion-api`, against a real API and a real PostgreSQL.
     */
    @Test
    fun `pins the version 3 batch an activity summary is sent in`() {
        val batch = SnapshotBatch(
            snapshotType = "activity_summary",
            items = listOf(
                ActivitySummaryMapper.map(window, activity(), readAt),
                ActivitySummaryMapper.map(
                    SnapshotWindow(LocalDateTime.parse("2026-08-10T15:00:00"), LocalDateTime.parse("2026-08-10T16:00:00")),
                    aggregate = null,
                    readAt,
                ),
            ),
        )

        assertEquals(GOLDEN, IngestionContract.json.encodeToString(SnapshotBatch.serializer(), batch))
    }

    private companion object {
        const val GOLDEN =
            """{"snapshotType":"activity_summary","items":[{"window":{"start":"2026-08-10T14:00:00","end":"2026-08-10T15:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-activity-summary/1","content":{"activeCaloriesBurned":{"value":42.5,"unit":"kcal"},"caloriesBurned":{"value":118.25,"unit":"kcal"},"activeTime":{"value":754.321,"unit":"s"},"distance":{"value":1310.7,"unit":"m"}}},{"window":{"start":"2026-08-10T15:00:00","end":"2026-08-10T16:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-activity-summary/1"}],"contractVersion":3}"""
    }
}
