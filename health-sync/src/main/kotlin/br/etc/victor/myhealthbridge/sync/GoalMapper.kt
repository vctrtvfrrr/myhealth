package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Turns the goal Samsung Health held in force on one local day into an observation of it.
 *
 * Samsung Health repeats a goal on every day it stays unchanged, and keeps the goal of a past day as it
 * was then, so the history of a goal is read back day by day like any other window. A goal belongs to
 * the Data Owner rather than to an app or a device, so it is asked over every source.
 *
 * Anything but the expected shape is emitted without the goal rather than guessed at, so the API rejects
 * it and it stays a Mapping Pendency.
 */
abstract class GoalMapper(override val version: String) : SnapshotMapper {

    override val sourceScope: SourceScope = SourceScope.ALL_SOURCES

    override fun map(window: SnapshotWindow, aggregate: SourceValue?, observedAt: ZonedInstant): SnapshotObservation =
        SnapshotObservation(
            window = window.wire(),
            observedAt = observedAt,
            sourceScope = sourceScope,
            mapperVersion = version,
            content = aggregate?.let { buildJsonObject { putGoal(it) } },
        )

    protected abstract fun JsonObjectBuilder.putGoal(aggregate: SourceValue)
}

/** A goal Samsung Health holds as one number, preserved under the name it gives that number. */
class SingleValueGoalMapper(version: String, private val field: String) : GoalMapper(version) {

    override fun JsonObjectBuilder.putGoal(aggregate: SourceValue) {
        if (aggregate is SourceValue.Number) put(field, JsonPrimitive(aggregate.value))
    }
}

/** The sleep goal: the local times to go to bed and to wake up, which Samsung Health aggregates apart. */
object SleepGoalMapper : GoalMapper("samsung-health-sleep-goal/1") {

    const val BED_TIME = "bed_time"
    const val WAKE_UP_TIME = "wake_up_time"

    override fun JsonObjectBuilder.putGoal(aggregate: SourceValue) {
        if (aggregate !is SourceValue.Nested) return
        listOf(BED_TIME, WAKE_UP_TIME).forEach { field ->
            (aggregate.fields[field] as? SourceValue.Text)?.let { put(field, JsonPrimitive(it.value)) }
        }
    }
}

object GoalMappers {
    val ACTIVE_CALORIES_BURNED = SingleValueGoalMapper("samsung-health-active-calories-burned-goal/1", "calories")

    /** In seconds, as every duration is preserved. */
    val ACTIVE_TIME = SingleValueGoalMapper("samsung-health-active-time-goal/1", "active_time")

    val NUTRITION = SingleValueGoalMapper("samsung-health-nutrition-goal/1", "calories")

    val SLEEP: GoalMapper = SleepGoalMapper

    val STEPS = SingleValueGoalMapper("samsung-health-steps-goal/1", "steps")

    val WATER_INTAKE = SingleValueGoalMapper("samsung-health-water-intake-goal/1", "amount")
}
