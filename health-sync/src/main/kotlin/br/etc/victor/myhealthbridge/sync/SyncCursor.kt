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
    val importedRecords: Long = 0,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastOutcome: SyncOutcome? = null,
) {

    fun startingInitialLoad(now: LocalDateTime): SyncCursor = copy(
        phase = ImportPhase.INITIAL_LOAD,
        readFrom = HISTORY_FLOOR,
        initialLoad = InitialLoadWindow(HISTORY_FLOOR, now),
        importedRecords = 0,
    )

    fun advancedTo(readFrom: LocalDateTime, records: Int): SyncCursor = copy(
        readFrom = readFrom,
        importedRecords = importedRecords + records,
    )

    fun withInitialLoadComplete(): SyncCursor =
        if (phase != ImportPhase.INITIAL_LOAD) this
        else copy(phase = ImportPhase.INCREMENTAL, readFrom = maxOf(readFrom, initialLoad?.end ?: readFrom))

    fun attempted(at: Instant, outcome: SyncOutcome): SyncCursor = copy(
        lastAttemptAt = at,
        lastSuccessAt = if (outcome == SyncOutcome.SUCCEEDED) at else lastSuccessAt,
        lastOutcome = outcome,
    )
}
