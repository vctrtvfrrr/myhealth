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

/** A goal Samsung Health holds as one count, preserved as the number it is. */
class CountGoalMapper(version: String, private val name: String) : GoalMapper(version) {

    override fun JsonObjectBuilder.putGoal(aggregate: SourceValue) {
        if (aggregate is SourceValue.Number) put(name, JsonPrimitive(aggregate.value))
    }
}

/**
 * A goal Samsung Health holds as one quantity, preserved as a `{value, unit}` the way an hour of activity
 * is. Samsung Health documents none of the units: [unit] is the one it uses everywhere else.
 */
class QuantityGoalMapper(version: String, private val name: String, private val unit: String) : GoalMapper(version) {

    override fun JsonObjectBuilder.putGoal(aggregate: SourceValue) {
        if (aggregate !is SourceValue.Number) return
        put(
            name,
            buildJsonObject {
                put("value", JsonPrimitive(aggregate.value))
                put("unit", JsonPrimitive(unit))
            },
        )
    }
}

/** The sleep goal: the local times to go to bed and to wake up, which Samsung Health aggregates apart. */
object SleepGoalMapper : GoalMapper("samsung-health-sleep-goal/2") {

    /** The names the adapter hands each time over under, inside a [SourceValue.Nested]. */
    const val BED_TIME = "bed_time"
    const val WAKE_UP_TIME = "wake_up_time"

    private val names = mapOf(BED_TIME to "bedTime", WAKE_UP_TIME to "wakeUpTime")

    override fun JsonObjectBuilder.putGoal(aggregate: SourceValue) {
        if (aggregate !is SourceValue.Nested) return
        names.forEach { (field, name) ->
            (aggregate.fields[field] as? SourceValue.Text)?.let { put(name, JsonPrimitive(it.value)) }
        }
    }
}

object GoalMappers {
    val ACTIVE_CALORIES_BURNED =
        QuantityGoalMapper("samsung-health-active-calories-burned-goal/2", "activeCaloriesBurned", "kcal")

    /** Already in seconds, as the adapter preserves every duration. */
    val ACTIVE_TIME = QuantityGoalMapper("samsung-health-active-time-goal/2", "activeTime", "s")

    val NUTRITION = QuantityGoalMapper("samsung-health-nutrition-goal/2", "calories", "kcal")

    val SLEEP: GoalMapper = SleepGoalMapper

    val STEPS = CountGoalMapper("samsung-health-steps-goal/2", "steps")

    val WATER_INTAKE = QuantityGoalMapper("samsung-health-water-intake-goal/2", "amount", "mL")
}
