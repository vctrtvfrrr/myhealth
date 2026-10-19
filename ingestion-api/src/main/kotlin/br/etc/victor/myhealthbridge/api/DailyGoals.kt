package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalTime

/**
 * The goals Samsung Health holds in force on each local day, one kind of Observed Snapshot per goal.
 *
 * The bounds are far beyond any goal Samsung Health lets the Data Owner set, so they catch mapper bugs
 * rather than ambitions.
 */
internal object DailyGoals {

    private val PLAUSIBLE_STEPS = BigDecimal.ZERO..BigDecimal(1_000_000)
    private val PLAUSIBLE_CALORIES = BigDecimal.ZERO..BigDecimal(20_000)
    private val PLAUSIBLE_SECONDS_A_DAY = BigDecimal.ZERO..BigDecimal(24 * 60 * 60)
    private val PLAUSIBLE_MILLILITRES = BigDecimal.ZERO..BigDecimal(20_000)

    val kinds: Map<String, SnapshotKinds.Kind> = mapOf(
        "active_calories_burned_goal" to daily(
            "samsung-health-active-calories-burned-goal/1",
            number("calories", PLAUSIBLE_CALORIES),
        ),
        "active_time_goal" to daily("samsung-health-active-time-goal/1", number("active_time", PLAUSIBLE_SECONDS_A_DAY)),
        "nutrition_goal" to daily("samsung-health-nutrition-goal/1", number("calories", PLAUSIBLE_CALORIES)),
        "sleep_goal" to daily("samsung-health-sleep-goal/1", ::validateSleep),
        "steps_goal" to daily("samsung-health-steps-goal/1", number("steps", PLAUSIBLE_STEPS, whole = true)),
        "water_intake_goal" to daily("samsung-health-water-intake-goal/1", number("amount", PLAUSIBLE_MILLILITRES)),
    )

    private fun daily(mapperVersion: String, rule: (JsonObject) -> List<RejectionCode>) =
        SnapshotKinds.Kind(SnapshotWindow.LOCAL_DAY, mapOf(mapperVersion to rule))

    /** A number written as a number, because the read model casts it and a text would have to be parsed. */
    private fun number(key: String, plausible: ClosedRange<BigDecimal>, whole: Boolean = false): (JsonObject) -> List<RejectionCode> =
        { content ->
            val value = (content[key] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.let { runCatching { BigDecimal(it.content) }.getOrNull() }
            val valid = value != null && value in plausible && (!whole || value.stripTrailingZeros().scale() <= 0)
            if (valid) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
        }

    /** Both local times, because the read model casts each of them to a time of day. */
    private fun validateSleep(content: JsonObject): List<RejectionCode> {
        val valid = listOf("bed_time", "wake_up_time").all { key ->
            (content[key] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.let { runCatching { LocalTime.parse(it.content) }.isSuccess } == true
        }
        return if (valid) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }
}
