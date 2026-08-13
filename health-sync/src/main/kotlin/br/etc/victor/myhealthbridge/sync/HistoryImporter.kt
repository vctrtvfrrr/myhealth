package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import java.time.Clock
import java.time.LocalDateTime

/** How a read into the outbox ended, whether it walked the history or the changes feed. */
sealed interface ImportResult {

    /** The read has nothing more; an initial load that reaches this becomes incremental. */
    data object Completed : ImportResult

    /**
     * The outbox reached its limit, so reading stopped instead of buffering more.
     *
     * [pageToken] is where the walk stopped, so that a caller which drained the outbox continues from
     * there rather than from the cursor. Records sharing the cursor's local time can span more pages
     * than the outbox holds, and restarting at that time would read the same first pages forever.
     *
     * [staged] is what this attempt actually put in the outbox. Zero means the room a drain was meant
     * to make never appeared, and reading again would only spin.
     */
    data class Paused(val pageToken: String?, val staged: Int) : ImportResult

    data class Failed(val availability: SamsungHealthAvailability) : ImportResult
}

/**
 * Walks the accessible history of one Health Category into the outbox, page by page.
 *
 * The cursor only ever moves over a page that is already staged, so an import interrupted by process
 * death or a reboot resumes from the last page it durably kept rather than from where it had read.
 */
class HistoryImporter(
    private val source: HealthRecordSource,
    private val store: SyncStore,
    private val policy: SyncPolicy,
    private val clock: Clock,
) {

    /** [resumeFrom] continues a walk this importer paused, which the cursor alone cannot express. */
    suspend fun import(capability: HealthCapability, resumeFrom: String? = null): ImportResult {
        var cursor = store.cursor(capability.category) ?: return ImportResult.Completed
        if (cursor.phase == ImportPhase.NOT_STARTED) return ImportResult.Completed

        val window = ReadWindow(cursor.readFrom, windowEnd(cursor))
        var pageToken = resumeFrom
        var staged = 0

        while (true) {
            if (store.pendingCount() >= policy.maxOutboxItems) return ImportResult.Paused(pageToken, staged)

            val page = when (val outcome = source.readPage(capability, window, pageToken)) {
                is SamsungHealthOutcome.Failed -> return ImportResult.Failed(outcome.availability)
                is SamsungHealthOutcome.Observed -> outcome.value
            }

            if (page.records.isNotEmpty()) {
                cursor = cursor.advancedTo(lastLocalStart(page.records), page.records.size)
                store.acceptPage(page.records.map { stage(capability, it) }, cursor)
                staged += page.records.size
            }

            pageToken = page.nextPageToken ?: break
        }

        if (cursor.phase == ImportPhase.INITIAL_LOAD) {
            cursor = cursor.withInitialLoadComplete(clock.instant())
            store.writeCursor(cursor)
        }
        return ImportResult.Completed
    }

    /**
     * The initial load stops at the boundary it was given, so its progress is measured against a
     * window that does not move while it is being walked.
     */
    private fun windowEnd(cursor: SyncCursor): LocalDateTime = when (cursor.phase) {
        ImportPhase.INITIAL_LOAD -> cursor.initialLoad?.end ?: LocalDateTime.now(clock)
        else -> LocalDateTime.now(clock)
    }

    private fun stage(capability: HealthCapability, record: SourceRecord): NewOutboxItem = capability.staged(
        uid = record.uid,
        envelope = capability.mapper.map(record),
        at = clock.instant(),
    )

    private fun lastLocalStart(records: List<SourceRecord>): LocalDateTime =
        records.maxOf { LocalDateTime.ofInstant(it.startTime, it.zoneOffset) }
}
