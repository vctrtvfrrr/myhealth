package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The goals as the ingestion keeps them: one Observed Snapshot per goal and local day, the goal in force
 * on that day, typed by the read model and marked on the days it changed. See ADR 0025.
 */
@Tag("integration")
class DailyGoalTest : IngestionApiTest() {

    @Test
    fun `types the goal of each kind on its local day`() {
        send("active_calories_burned_goal", goal("samsung-health-active-calories-burned-goal/1") { put("calories", 450) })
        send("active_time_goal", goal("samsung-health-active-time-goal/1") { put("active_time", 5400.5) })
        send("nutrition_goal", goal("samsung-health-nutrition-goal/1") { put("calories", 2100) })
        send(
            "sleep_goal",
            goal("samsung-health-sleep-goal/1") {
                put("bed_time", "23:15")
                put("wake_up_time", "06:45:30")
            },
        )
        send(Snapshots.STEPS_GOAL, goal(Snapshots.STEPS_GOAL_MAPPER_VERSION) { put("steps", 6000) })
        send("water_intake_goal", goal("samsung-health-water-intake-goal/1") { put("amount", 2500) })

        assertEquals(
            listOf(
                "active_calories_burned_goal|2026-07-10|null|450|null|null|null|null|f",
                "active_time_goal|2026-07-10|null|null|01:30:00.5|null|null|null|f",
                "nutrition_goal|2026-07-10|null|2100|null|null|null|null|f",
                "sleep_goal|2026-07-10|null|null|null|null|23:15:00|06:45:30|f",
                "steps_goal|2026-07-10|6000|null|null|null|null|null|f",
                "water_intake_goal|2026-07-10|null|null|null|2500|null|null|f",
            ),
            rows(
                """
                select goal, day, steps, calories, active_time, amount, bed_time, wake_up_time, partial
                from read_model.current_daily_goal where day = '2026-07-10' order by goal
                """,
            ),
        )
    }

    /** Samsung Health repeats a goal on every day it stays in force, so a change is a day unlike the one before. */
    @Test
    fun `marks the days on which a goal changed, a day without a goal left out`() {
        send(
            Snapshots.STEPS_GOAL,
            Snapshots.goal(day = "2026-06-01", nextDay = "2026-06-02", content = steps(6000)),
            Snapshots.goal(day = "2026-06-02", nextDay = "2026-06-03", content = steps(6000)),
            Snapshots.goal(day = "2026-06-03", nextDay = "2026-06-04", content = steps(8000)),
            Snapshots.goal(day = "2026-06-04", nextDay = "2026-06-05", content = null),
            Snapshots.goal(day = "2026-06-05", nextDay = "2026-06-06", content = steps(8000)),
            Snapshots.goal(day = "2026-06-06", nextDay = "2026-06-07", content = steps(8000)),
        )

        assertEquals(
            listOf("2026-06-01|6000|t", "2026-06-02|6000|f", "2026-06-03|8000|t", "2026-06-05|8000|t", "2026-06-06|8000|f"),
            rows("select day, steps, changed from read_model.current_daily_goal where goal = 'steps_goal' and day < '2026-06-07' order by day"),
        )
        assertEquals(
            listOf("2026-06-01|6000", "2026-06-03|8000", "2026-06-05|8000"),
            rows("select day, steps from read_model.current_daily_goal where changed and goal = 'steps_goal' and day < '2026-06-07' order by day"),
        )
    }

    @Test
    fun `tells today's goal from the goal of a closed day`() {
        send(
            Snapshots.STEPS_GOAL,
            Snapshots.goal(day = "2026-09-10", nextDay = "2026-09-11", observedAt = "2026-09-10T21:00:00Z"),
            Snapshots.goal(day = "2026-09-09", nextDay = "2026-09-10", observedAt = "2026-09-10T21:00:00Z"),
        )

        assertEquals(
            listOf("2026-09-09|f", "2026-09-10|t"),
            rows("select day, partial from read_model.current_daily_goal where day > '2026-09-01' order by day"),
        )
    }

    @Test
    fun `rejects an item whose window is no local day or whose content cannot be its goal`() {
        val response = api.postBatch(
            Snapshots.batch(
                snapshotType = Snapshots.STEPS_GOAL,
                items = listOf(
                    JsonObject(
                        Snapshots.goal() + (
                            "window" to buildJsonObject {
                                put("start", "2026-08-10T01:00:00")
                                put("end", "2026-08-11T01:00:00")
                            }
                        ),
                    ),
                    Snapshots.goal(day = "2026-08-10", nextDay = "2026-08-12"),
                    Snapshots.goal(mapperVersion = Snapshots.STEPS_MAPPER_VERSION),
                    Snapshots.goal(content = buildJsonObject { put("steps", -1) }),
                    Snapshots.goal(content = buildJsonObject { put("steps", 6000.5) }),
                    Snapshots.goal(),
                ),
            ),
            token,
        )
        val sleep = api.postBatch(
            Snapshots.batch(
                snapshotType = "sleep_goal",
                items = listOf(
                    goal("samsung-health-sleep-goal/1") { put("bed_time", "23:15") },
                    goal("samsung-health-sleep-goal/1") {
                        put("bed_time", "25:00")
                        put("wake_up_time", "06:45")
                    },
                ),
            ),
            token,
        )
        val amounts = api.postBatch(
            Snapshots.batch(
                snapshotType = "water_intake_goal",
                items = listOf(goal("samsung-health-water-intake-goal/1") { put("amount", "2500") }),
            ),
            token,
        )

        assertEquals(
            listOf(
                listOf("invalid_identity"),
                listOf("invalid_identity"),
                listOf("unsupported_mapper"),
                listOf("invalid_payload"),
                listOf("invalid_payload"),
                emptyList(),
            ),
            codes(response.body),
        )
        assertEquals(listOf(listOf("invalid_payload"), listOf("invalid_payload")), codes(sleep.body))
        assertEquals(listOf(listOf("invalid_payload")), codes(amounts.body))
    }

    private fun steps(goal: Long): JsonObject = buildJsonObject { put("steps", goal) }

    private fun goal(mapperVersion: String, content: JsonObjectBuilder.() -> Unit) = Snapshots.goal(
        day = "2026-07-10",
        nextDay = "2026-07-11",
        observedAt = "2026-07-11T12:00:00Z",
        mapperVersion = mapperVersion,
        content = buildJsonObject(content),
    )

    private fun send(snapshotType: String, vararg items: JsonObject) {
        val response = api.postBatch(Snapshots.batch(snapshotType = snapshotType, items = items.toList()), token)
        assertEquals(200, response.status, response.body)
        assertEquals(items.map { "accepted" }, statuses(response.body), response.body)
    }

    private fun rows(sql: String): List<String> = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql.trimIndent()).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).joinToString("|") { rows.getString(it) ?: "null" })
                }
            }
        }
    }
}
