package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.HealthCategory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/** Where the import of a Health Category stands. */
enum class ImportPhase {
    /** The initial load was never started; the Data Owner starts it explicitly. */
    NOT_STARTED,

    INITIAL_LOAD,

    /** The accessible history was walked; each run now reads forward from where it stopped. */
    INCREMENTAL,
}

/** How the last synchronization of a Health Category ended. */
enum class SyncOutcome {
    SUCCEEDED,
    WAITING_PERMISSION,
    SAMSUNG_UNAVAILABLE,
    NOT_CONFIGURED,
    INGESTION_UNAVAILABLE,
    CONTRACT_INCOMPATIBLE,
    OUTBOX_FULL,

    /** The stored cursor could not say where the import stood, so the whole history is read again. */
    CURSOR_UNRECOVERABLE,
}

/**
 * Samsung Health did not exist before this, so nothing accessible can be older. It bounds the initial
 * load instead of leaving it open at the far end, where a filter has no natural floor.
 */
val HISTORY_FLOOR: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

/** The stretch of local time the initial load has to walk, fixed when it starts. */
data class InitialLoadWindow(val start: LocalDateTime, val end: LocalDateTime) {

    fun progressOf(readFrom: LocalDateTime): Float {
        val total = Duration.between(start, end).seconds
        if (total <= 0) return 1f
        val walked = Duration.between(start, readFrom).seconds
        return (walked.toFloat() / total).coerceIn(0f, 1f)
    }
}

/**
 * How far the import of one Health Category has read, and how its last run ended.
 *
 * Every category carries its own, so a category that cannot be read does not hold the others back.
 *
 * [readFrom] is the inclusive lower bound of the next read rather than an opaque page token: a token
 * belongs to a read that is already running and cannot survive the process, while a local time can be
 * asked for again after a restart. Re-reading the record on the boundary is what that costs, and the
 * ingestion is idempotent under exactly that.
 */
data class SyncCursor(
    val category: HealthCategory,
    val phase: ImportPhase = ImportPhase.NOT_STARTED,
    val readFrom: LocalDateTime = HISTORY_FLOOR,
    val initialLoad: InitialLoadWindow? = null,
    /**
     * The inclusive lower bound of the next changes read, or null while nothing was imported for the
     * changes of it to mean anything.
     *
     * It is an instant, not a local time: a change is dated by when the source changed the record, not
     * by when the record itself happened.
     */
    val changesFrom: Instant? = null,
    /** When the overlap re-read last pulled [readFrom] back, or null while it never has. */
    val lastOverlapAt: Instant? = null,
    /**
     * Why this cursor cannot say where the import stands, or null when it can.
     *
     * A stored cursor no build can interpret is kept as this instead of being given a default position:
     * defaulting forward would skip a stretch of history nothing would ever read again, and defaulting
     * back would claim a position that was never reached. Both are silent, and the run that meets one
     * re-reads the whole accessible history instead.
     */
    val unrecoverable: String? = null,
    val importedRecords: Long = 0,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastOutcome: SyncOutcome? = null,
) {

    /**
     * [at] fixes where the changes read begins, before the walk itself: a record edited while the walk
     * is passing over it would otherwise be missed by the walk and by every changes read after it.
     */
    fun startingInitialLoad(now: LocalDateTime, at: Instant): SyncCursor = copy(
        phase = ImportPhase.INITIAL_LOAD,
        readFrom = HISTORY_FLOOR,
        initialLoad = InitialLoadWindow(HISTORY_FLOOR, now),
        changesFrom = at,
        lastOverlapAt = null,
        unrecoverable = null,
        importedRecords = 0,
    )

    fun advancedTo(readFrom: LocalDateTime, records: Int): SyncCursor = copy(
        readFrom = readFrom,
        importedRecords = importedRecords + records,
    )

    /** [at] counts as an overlap: the walk that just ended covered the window one would re-read. */
    fun withInitialLoadComplete(at: Instant): SyncCursor =
        if (phase != ImportPhase.INITIAL_LOAD) this
        else copy(
            phase = ImportPhase.INCREMENTAL,
            readFrom = maxOf(readFrom, initialLoad?.end ?: readFrom),
            lastOverlapAt = at,
        )

    /** Where the next changes read starts; the change on the boundary is read again. */
    fun changesReadTo(at: Instant): SyncCursor = copy(changesFrom = at)

    /**
     * Pulls the next read back over the overlap window, so that a change made inside it is read again
     * even where the changes feed never reported it.
     *
     * It never reaches behind the stretch the initial load fixed: outside it there is nothing to re-read.
     */
    fun withOverlap(window: Duration, at: Instant): SyncCursor = copy(
        readFrom = maxOf(readFrom.minus(window), initialLoad?.start ?: HISTORY_FLOOR),
        lastOverlapAt = at,
    )

    /**
     * Whether the overlap re-read is due, which is what keeps it daily while the run is hourly.
     *
     * Only an incremental cursor takes one: an initial load is still walking the window a re-read
     * would cover. A cursor that never recorded an overlap is due at once, because nothing says the
     * window it would cover was ever read.
     */
    fun overlapDue(at: Instant, every: Duration): Boolean =
        phase == ImportPhase.INCREMENTAL && (lastOverlapAt == null || !at.isBefore(lastOverlapAt.plus(every)))

    fun attempted(at: Instant, outcome: SyncOutcome): SyncCursor = copy(
        lastAttemptAt = at,
        lastSuccessAt = if (outcome == SyncOutcome.SUCCEEDED) at else lastSuccessAt,
        lastOutcome = outcome,
    )
}
