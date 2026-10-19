package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalTime

/**
 * The goals Samsung Health holds in force on each local day, one kind of Observed Snapshot per goal.
 *
 * Version 2 of each mapper states the unit of every quantity as a `{value, unit}`, the way an hour of
 * activity does. Version 1, which the first application to send goals used, sent bare numbers in the
 * same units and is still kept, since what it observed is still the goal of its day.
 *
 * The bounds are far beyond any goal Samsung Health lets the Data Owner set, so they catch mapper bugs
 * rather than ambitions.
 */
internal object DailyGoals {

    private val PLAUSIBLE_STEPS = BigDecimal.ZERO..BigDecimal(1_000_000)
    private val PLAUSIBLE_CALORIES = BigDecimal.ZERO..BigDecimal(20_000)
    private val PLAUSIBLE_SECONDS_A_DAY = BigDecimal.ZERO..BigDecimal(24 * 60 * 60)
    private val PLAUSIBLE_MILLILITRES = BigDecimal.ZERO..BigDecimal(20_000)

    private val steps = count("steps", PLAUSIBLE_STEPS)

    val kinds: Map<String, SnapshotKinds.Kind> = mapOf(
        "active_calories_burned_goal" to daily(
            "samsung-health-active-calories-burned-goal",
            v1 = bare("calories", PLAUSIBLE_CALORIES),
            v2 = quantity("activeCaloriesBurned", "kcal", PLAUSIBLE_CALORIES),
        ),
        "active_time_goal" to daily(
            "samsung-health-active-time-goal",
            v1 = bare("active_time", PLAUSIBLE_SECONDS_A_DAY),
            v2 = quantity("activeTime", "s", PLAUSIBLE_SECONDS_A_DAY),
        ),
        "nutrition_goal" to daily(
            "samsung-health-nutrition-goal",
            v1 = bare("calories", PLAUSIBLE_CALORIES),
            v2 = quantity("calories", "kcal", PLAUSIBLE_CALORIES),
        ),
        "sleep_goal" to daily(
            "samsung-health-sleep-goal",
            v1 = times("bed_time", "wake_up_time"),
            v2 = times("bedTime", "wakeUpTime"),
        ),
        "steps_goal" to daily("samsung-health-steps-goal", v1 = steps, v2 = steps),
        "water_intake_goal" to daily(
            "samsung-health-water-intake-goal",
            v1 = bare("amount", PLAUSIBLE_MILLILITRES),
            v2 = quantity("amount", "mL", PLAUSIBLE_MILLILITRES),
        ),
    )

    private fun daily(mapper: String, v1: (JsonObject) -> List<RejectionCode>, v2: (JsonObject) -> List<RejectionCode>) =
        SnapshotKinds.Kind(SnapshotWindow.LOCAL_DAY, mapOf("$mapper/1" to v1, "$mapper/2" to v2))

    private fun count(key: String, plausible: ClosedRange<BigDecimal>): (JsonObject) -> List<RejectionCode> = { content ->
        val value = numberOf(content[key])
        payload(value != null && value in plausible && value.stripTrailingZeros().scale() <= 0)
    }

    private fun bare(key: String, plausible: ClosedRange<BigDecimal>): (JsonObject) -> List<RejectionCode> = { content ->
        payload(numberOf(content[key])?.let { it in plausible } == true)
    }

    /** A `{value, unit}` in the one unit the read model casts it as. */
    private fun quantity(key: String, unit: String, plausible: ClosedRange<BigDecimal>): (JsonObject) -> List<RejectionCode> =
        { content ->
            val quantity = content[key] as? JsonObject
            if (quantity == null) {
                listOf(RejectionCode.INVALID_PAYLOAD)
            } else {
                payload(numberOf(quantity["value"])?.let { it in plausible } == true) +
                    listOfNotNull(RejectionCode.INVALID_UNIT.takeIf { textOf(quantity["unit"]) != unit })
            }
        }

    /** Both local times, because the read model casts each of them to a time of day. */
    private fun times(bedTime: String, wakeUpTime: String): (JsonObject) -> List<RejectionCode> = { content ->
        payload(
            listOf(bedTime, wakeUpTime).all { key ->
                textOf(content[key])?.let { runCatching { LocalTime.parse(it) }.isSuccess } == true
            },
        )
    }

    /** A number written as a number, because the read model casts it and a text would have to be parsed. */
    private fun numberOf(node: Any?): BigDecimal? = (node as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.let { runCatching { BigDecimal(it.content) }.getOrNull() }

    private fun textOf(node: Any?): String? = (node as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun payload(valid: Boolean): List<RejectionCode> =
        if (valid) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
}
