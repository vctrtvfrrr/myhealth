package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The far end of the snapshot verticals: the exact version 3 batch the Android application builds for
 * two hours of one kind, one of them empty, sent to a real API and showing up in the read model.
 *
 * The literal is pinned on the other side too, by `StepsMapperTest` in `health-sync`, so neither half can
 * drift without one of the two failing.
 */
@Tag("integration")
class AppSnapshotReadModelTest : IngestionApiTest() {

    @Test
    fun `a batch of step snapshots the application mapped becomes queryable through the read model`() {
        val response = api.postBatch(MAPPED_STEPS, token)

        assertEquals(200, response.status, response.body)
        assertEquals(listOf("accepted", "accepted"), statuses(response.body))
        assertEquals(
            listOf("2026-08-10 14:00:00|1234|f"),
            rows("select window_start, steps, partial from read_model.current_hourly_steps"),
        )
        assertEquals(
            listOf("2026-08-10 14:00:00|f", "2026-08-10 15:00:00|t"),
            rows("select window_start, empty from read_model.current_observed_snapshot where snapshot_type = 'steps' order by window_start"),
        )
    }

    /** Pinned on the other side by `ActivitySummaryMapperTest` in `health-sync`. */
    @Test
    fun `a batch of activity summaries the application mapped becomes queryable through the read model`() {
        val response = api.postBatch(MAPPED_ACTIVITY_SUMMARIES, token)

        assertEquals(200, response.status, response.body)
        assertEquals(listOf("accepted", "accepted"), statuses(response.body))
        assertEquals(
            listOf("2026-08-10 14:00:00|42.5|118.25|754.321|1310.7|f"),
            rows(
                """
                select window_start, active_calories_burned_kilocalories, calories_burned_kilocalories,
                    active_time_seconds, distance_meters, partial
                from read_model.current_hourly_activity_summary
                """.trimIndent(),
            ),
        )
    }

    /** The literal `ProfileMapperTest` pins in `health-sync`. Every value in it is invented. */
    @Test
    fun `a profile snapshot the application mapped becomes the current profile in the read model`() {
        val response = api.postBatch(MAPPED_PROFILE, token)

        assertEquals(200, response.status, response.body)
        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals(
            listOf("t|2000-01-01|170.5|65|GENDER_UNKNOWN|Synthetic"),
            rows("select current, date_of_birth, height_cm, weight_kg, gender, nickname from read_model.user_profile"),
        )
    }

    /** Pinned on the other side by `GoalMapperTest` in `health-sync`. */
    @Test
    fun `a batch of step goals the application mapped becomes queryable through the read model`() {
        val response = api.postBatch(MAPPED_STEPS_GOAL, token)

        assertEquals(200, response.status, response.body)
        assertEquals(listOf("accepted", "accepted"), statuses(response.body))
        assertEquals(
            listOf("steps_goal|2026-08-10|6000|t|t"),
            rows("select goal, day, steps, changed, partial from read_model.current_daily_goal"),
        )
    }

    private fun rows(sql: String): List<String> = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).joinToString("|") { rows.getString(it) })
                }
            }
        }
    }

    private companion object {
        const val MAPPED_STEPS_GOAL =
            """{"snapshotType":"steps_goal","items":[{"window":{"start":"2026-08-10T00:00:00","end":"2026-08-11T00:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"all_sources","mapperVersion":"samsung-health-steps-goal/1","content":{"steps":6000}},{"window":{"start":"2026-08-11T00:00:00","end":"2026-08-12T00:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"all_sources","mapperVersion":"samsung-health-steps-goal/1"}],"contractVersion":3}"""

        const val MAPPED_PROFILE =
            """{"snapshotType":"user_profile","items":[{"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"all_sources","mapperVersion":"samsung-health-user-profile/1","content":{"date_of_birth":"2000-01-01","height":170.5,"weight":65.0,"gender":"GENDER_UNKNOWN","nickname":"Synthetic"}}],"contractVersion":3}"""

        const val MAPPED_ACTIVITY_SUMMARIES =
            """{"snapshotType":"activity_summary","items":[{"window":{"start":"2026-08-10T14:00:00","end":"2026-08-10T15:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-activity-summary/1","content":{"activeCaloriesBurned":{"value":42.5,"unit":"kcal"},"caloriesBurned":{"value":118.25,"unit":"kcal"},"activeTime":{"value":754.321,"unit":"s"},"distance":{"value":1310.7,"unit":"m"}}},{"window":{"start":"2026-08-10T15:00:00","end":"2026-08-10T16:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-activity-summary/1"}],"contractVersion":3}"""

        const val MAPPED_STEPS =
            """{"snapshotType":"steps","items":[{"window":{"start":"2026-08-10T14:00:00","end":"2026-08-10T15:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-steps/1","content":{"steps":1234}},{"window":{"start":"2026-08-10T15:00:00","end":"2026-08-10T16:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-steps/1"}],"contractVersion":3}"""
    }
}
