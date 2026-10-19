package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.LocalInterval
import br.etc.victor.myhealthbridge.contract.SnapshotBatch
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** The goal in force on one local day, rendered from synthetic aggregates. Every goal is invented. */
class GoalMapperTest {

    private val day = SnapshotWindow(
        start = LocalDateTime.parse("2026-08-10T00:00:00"),
        end = LocalDateTime.parse("2026-08-11T00:00:00"),
    )

    private val readAt = ZonedInstant("2026-08-10T18:20:00Z", "-03:00")

    @Test
    fun `renders the goal of the day as the snapshot's content, asked over every source`() {
        val observation = GoalMappers.STEPS.map(day, number("6000"), readAt)

        assertEquals(LocalInterval("2026-08-10T00:00:00", "2026-08-11T00:00:00"), observation.window)
        assertEquals(readAt, observation.observedAt)
        assertEquals(SourceScope.ALL_SOURCES, observation.sourceScope)
        assertEquals("samsung-health-steps-goal/2", observation.mapperVersion)
        assertEquals(JsonObject(mapOf("steps" to JsonPrimitive(6000.toBigDecimal()))), observation.content)
    }

    /** Samsung Health documents none of the units, so the content states them, as an hour of activity does. */
    @Test
    fun `renders each goal of one quantity with its unit`() {
        val rendered = listOf(
            GoalMappers.ACTIVE_CALORIES_BURNED to number("450"),
            GoalMappers.ACTIVE_TIME to number("5400.000"),
            GoalMappers.NUTRITION to number("2100.0"),
            GoalMappers.WATER_INTAKE to number("2500.0"),
        ).map { (mapper, goal) -> mapper.version to mapper.map(day, goal, readAt).content }

        assertEquals(
            listOf(
                "samsung-health-active-calories-burned-goal/2" to quantity("activeCaloriesBurned", "450", "kcal"),
                "samsung-health-active-time-goal/2" to quantity("activeTime", "5400.000", "s"),
                "samsung-health-nutrition-goal/2" to quantity("calories", "2100.0", "kcal"),
                "samsung-health-water-intake-goal/2" to quantity("amount", "2500.0", "mL"),
            ),
            rendered,
        )
    }

    @Test
    fun `renders the bed and wake up times of the sleep goal`() {
        val goal = SourceValue.Nested(mapOf("bed_time" to SourceValue.Text("23:15"), "wake_up_time" to SourceValue.Text("06:45")))

        val observation = GoalMappers.SLEEP.map(day, goal, readAt)

        assertEquals("samsung-health-sleep-goal/2", observation.mapperVersion)
        assertEquals(SourceScope.ALL_SOURCES, observation.sourceScope)
        assertEquals(
            JsonObject(mapOf("bedTime" to JsonPrimitive("23:15"), "wakeUpTime" to JsonPrimitive("06:45"))),
            observation.content,
        )
    }

    /** A day Samsung Health holds no goal for is observed as empty, never as an invented zero. */
    @Test
    fun `renders a day without a goal as an empty observation`() {
        assertNull(GoalMappers.STEPS.map(day, aggregate = null, readAt).content)
        assertNull(GoalMappers.SLEEP.map(day, aggregate = null, readAt).content)
    }

    /** Content the API will refuse, so it becomes a Mapping Pendency instead of a guessed goal. */
    @Test
    fun `renders an aggregate of an unexpected shape as content without the goal`() {
        assertEquals(JsonObject(emptyMap()), GoalMappers.WATER_INTAKE.map(day, SourceValue.Text("much"), readAt).content)
        assertEquals(JsonObject(emptyMap()), GoalMappers.SLEEP.map(day, number("23"), readAt).content)
    }

    /**
     * The exact document a batch of step goals is sent as. The same literal is posted at
     * `AppSnapshotReadModelTest` in `ingestion-api`, against a real API and a real PostgreSQL, so that
     * what the mapper emits is what the read model was proven to show.
     */
    @Test
    fun `pins the version 3 batch a step goal is sent in`() {
        val batch = SnapshotBatch(
            snapshotType = "steps_goal",
            items = listOf(
                GoalMappers.STEPS.map(day, number("6000"), readAt),
                GoalMappers.STEPS.map(
                    SnapshotWindow(LocalDateTime.parse("2026-08-11T00:00:00"), LocalDateTime.parse("2026-08-12T00:00:00")),
                    aggregate = null,
                    readAt,
                ),
            ),
        )

        assertEquals(GOLDEN, IngestionContract.json.encodeToString(SnapshotBatch.serializer(), batch))
    }

    private fun quantity(name: String, value: String, unit: String) = JsonObject(
        mapOf(name to JsonObject(mapOf("value" to JsonPrimitive(value.toBigDecimal()), "unit" to JsonPrimitive(unit)))),
    )

    private companion object {
        const val GOLDEN =
            """{"snapshotType":"steps_goal","items":[{"window":{"start":"2026-08-10T00:00:00","end":"2026-08-11T00:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"all_sources","mapperVersion":"samsung-health-steps-goal/2","content":{"steps":6000}},{"window":{"start":"2026-08-11T00:00:00","end":"2026-08-12T00:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"all_sources","mapperVersion":"samsung-health-steps-goal/2"}],"contractVersion":3}"""
    }
}
