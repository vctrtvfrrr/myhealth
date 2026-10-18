package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.LocalInterval
import br.etc.victor.myhealthbridge.contract.RecordInventory
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

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
 *
 * Each page also declares the stretch of local time it closes, with every record read there, so that
 * the API can conclude the removal of a record it holds and the source no longer has. See ADR 0022.
 */
class HistoryImporter(
    private val source: HealthRecordSource,
    private val store: SyncStore,
    private val maintenance: MaintenanceService,
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

            val readAt = clock.instant()
            val page = when (val outcome = source.readPage(capability, window, pageToken)) {
                is SamsungHealthOutcome.Failed -> return ImportResult.Failed(outcome.availability)
                is SamsungHealthOutcome.Observed -> outcome.value
            }

            val inventory = inventoryOf(capability, cursor, page, window, readAt)
            if (page.records.isNotEmpty()) {
                val last = lastLocalStart(page.records)
                val atLast = page.records.filter { localStart(it) == last }.mapTo(HashSet()) { it.uid }
                val carry = if (last == cursor.readFrom) cursor.inventoryCarry + atLast else atLast
                cursor = cursor.advancedTo(last, page.records.size, carry)
                staged += page.records.size
            }
            if (page.records.isNotEmpty() || inventory != null) {
                store.acceptPage(page.records.map { stage(capability, it) }, cursor, inventory)
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

    private suspend fun stage(capability: HealthCapability, record: SourceRecord): NewOutboxItem {
        maintenance.reportUnknownEnums(capability, record)
        return capability.staged(
            uid = record.uid,
            envelope = capability.mapper.map(record),
            at = clock.instant(),
        )
    }

    /**
     * The stretch from where the page began up to where it ended, with every record read there.
     *
     * A page ends at its last local start, which is left out: later pages may still hold records that
     * share it. The page the window ends with reaches the end of the window instead, since nothing after
     * it is left to share anything. Every record of the page is declared, including those at its end,
     * because naming a record outside the stretch costs nothing and leaving one inside it out is a
     * removal; for that same reason the records already read at the start of the stretch are declared
     * from the cursor.
     */
    private fun inventoryOf(
        capability: HealthCapability,
        cursor: SyncCursor,
        page: RecordPage,
        window: ReadWindow,
        readAt: Instant,
    ): NewInventory? {
        val end = if (page.nextPageToken == null) window.to else page.records.maxOfOrNull(::localStart)
        if (end == null || !end.isAfter(cursor.readFrom)) return null

        return NewInventory(
            category = capability.category,
            recordType = capability.recordType,
            inventory = RecordInventory(
                interval = LocalInterval(start = cursor.readFrom.toString(), end = end.toString()),
                readAt = readAt.toString(),
                samsungUids = (cursor.inventoryCarry + page.records.map { it.uid }).sorted(),
            ),
        )
    }

    private fun lastLocalStart(records: List<SourceRecord>): LocalDateTime = records.maxOf(::localStart)

    /** UTC when the source recorded no local context, which is also how the mapper renders such a record. */
    private fun localStart(record: SourceRecord): LocalDateTime =
        LocalDateTime.ofInstant(record.startTime, record.zoneOffset ?: ZoneOffset.UTC)
}
