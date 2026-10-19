package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.LocalInterval
import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The stretch of local time one Observed Snapshot summarizes, start included and end excluded. */
data class SnapshotWindow(val start: LocalDateTime, val end: LocalDateTime) {

    /** Written with its seconds, the one form the API identifies a window by. */
    fun wire(): LocalInterval = LocalInterval(
        start = start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        end = end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    )
}

/** How a kind of Observed Snapshot cuts local time into the windows that identify it. */
enum class SnapshotWindowUnit {
    LOCAL_HOUR {
        override fun floor(time: LocalDateTime): LocalDateTime = time.truncatedTo(ChronoUnit.HOURS)

        override fun advance(start: LocalDateTime, windows: Long): LocalDateTime = start.plusHours(windows)
    },

    LOCAL_DAY {
        override fun floor(time: LocalDateTime): LocalDateTime = time.truncatedTo(ChronoUnit.DAYS)

        override fun advance(start: LocalDateTime, windows: Long): LocalDateTime = start.plusDays(windows)
    },
    ;

    /** The start of the window [time] falls in. */
    abstract fun floor(time: LocalDateTime): LocalDateTime

    abstract fun advance(start: LocalDateTime, windows: Long): LocalDateTime

    /** The end of the window [time] falls in, or [time] itself when a window starts there. */
    fun ceiling(time: LocalDateTime): LocalDateTime = floor(time).let { if (it == time) it else advance(it, 1) }

    /** Every window from [from] up to [until], which are both window boundaries. */
    fun windowsIn(from: LocalDateTime, until: LocalDateTime): List<SnapshotWindow> =
        generateSequence(from) { advance(it, 1) }
            .takeWhile { it.isBefore(until) }
            .map { SnapshotWindow(it, advance(it, 1)) }
            .toList()
}

/**
 * Turns what Samsung Health aggregated over one window into an observation of an Observed Snapshot.
 *
 * As with a [RecordMapper], the version is part of the contract: the API validates the content by
 * snapshot type and mapper version together.
 */
interface SnapshotMapper {

    val version: String

    /** Which sources the aggregate is asked over, declared because the aggregate itself names none. */
    val sourceScope: SourceScope

    /** A null [aggregate] is a window with nothing in it, observed as empty. */
    fun map(window: SnapshotWindow, aggregate: SourceValue?, observedAt: ZonedInstant): SnapshotObservation
}

/** The one mapper of the steps Samsung Health counts in an hour. */
object StepsMapper : SnapshotMapper {

    override val version: String = "samsung-health-steps/1"

    override val sourceScope: SourceScope = SourceScope.PLATFORM

    override fun map(window: SnapshotWindow, aggregate: SourceValue?, observedAt: ZonedInstant): SnapshotObservation =
        SnapshotObservation(
            window = window.wire(),
            observedAt = observedAt,
            sourceScope = sourceScope,
            mapperVersion = version,
            content = aggregate?.let(::contentOf),
        )

    /**
     * Anything but a number is emitted without a count rather than guessed at, so the API rejects it and
     * it stays a Mapping Pendency.
     */
    private fun contentOf(aggregate: SourceValue): JsonObject = buildJsonObject {
        if (aggregate is SourceValue.Number) put("steps", JsonPrimitive(aggregate.value))
    }
}
