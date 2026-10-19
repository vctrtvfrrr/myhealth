package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** The stretch of local time one kind of Observed Snapshot summarizes, which is part of its identity. */
enum class SnapshotWindow {
    LOCAL_HOUR {
        override fun fits(start: LocalDateTime, end: LocalDateTime): Boolean =
            start == start.truncatedTo(ChronoUnit.HOURS) && Duration.between(start, end) == Duration.ofHours(1)
    },

    LOCAL_DAY {
        override fun fits(start: LocalDateTime, end: LocalDateTime): Boolean =
            start == start.truncatedTo(ChronoUnit.DAYS) && end == start.plusDays(1)
    },
    ;

    abstract fun fits(start: LocalDateTime, end: LocalDateTime): Boolean
}

/**
 * The kinds of Observed Snapshot this API knows how to keep, each with the window that identifies it and
 * the content shapes it validates, selected by snapshot type and mapper version together.
 *
 * Adding a kind is adding an entry here and its views: the storage, the observation rule and the
 * projection are the same for every kind.
 */
object SnapshotKinds {

    /** A null [window] is a kind that summarizes no stretch of time, identified by its type alone. */
    class Kind(
        val window: SnapshotWindow?,
        val contents: Map<String, (JsonObject) -> List<RejectionCode>>,
    )

    private val kinds: Map<String, Kind> = mapOf(
        "steps" to Kind(SnapshotWindow.LOCAL_HOUR, mapOf("samsung-health-steps/1" to ::validateSteps)),
        "activity_summary" to Kind(
            SnapshotWindow.LOCAL_HOUR,
            mapOf("samsung-health-activity-summary/1" to ActivitySummaryContent::validate),
        ),
        "user_profile" to Kind(window = null, mapOf("samsung-health-user-profile/1" to UserProfileContent::validate)),
    ) + DailyGoals.kinds

    fun of(snapshotType: String): Kind? = kinds[snapshotType]

    /** Far beyond what anyone walks in an hour, so it catches mapper bugs rather than marathons. */
    private val PLAUSIBLE_STEPS_PER_HOUR = BigDecimal.ZERO..BigDecimal(100_000)

    /** A whole count, because the read model casts it to an integer and a fraction is no step. */
    private fun validateSteps(content: JsonObject): List<RejectionCode> {
        val steps = (content["steps"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.let { runCatching { BigDecimal(it.content) }.getOrNull() }
        val whole = steps != null && steps.stripTrailingZeros().scale() <= 0 && steps in PLAUSIBLE_STEPS_PER_HOUR
        return if (whole) emptyList() else listOf(RejectionCode.INVALID_PAYLOAD)
    }
}
