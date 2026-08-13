package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import java.time.Clock
import java.time.LocalDateTime

sealed interface ImportResult {

    /** The window holds nothing more; an initial load that reaches this becomes incremental. */
    data object Completed : ImportResult

    /** The outbox reached its limit, so reading stopped instead of buffering more. */
    data object Paused : ImportResult

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

    suspend fun import(capability: HealthCapability): ImportResult {
        var cursor = store.cursor(capability.category) ?: return ImportResult.Completed
        if (cursor.phase == ImportPhase.NOT_STARTED) return ImportResult.Completed

        val window = ReadWindow(cursor.readFrom, windowEnd(cursor))
        var pageToken: String? = null

        while (true) {
            if (store.pendingCount() >= policy.maxOutboxItems) return ImportResult.Paused

            val page = when (val outcome = source.readPage(capability, window, pageToken)) {
                is SamsungHealthOutcome.Failed -> return ImportResult.Failed(outcome.availability)
                is SamsungHealthOutcome.Observed -> outcome.value
            }

            if (page.records.isNotEmpty()) {
                cursor = cursor.advancedTo(lastLocalStart(page.records), page.records.size)
                store.acceptPage(page.records.map { stage(capability, it) }, cursor)
            }

            pageToken = page.nextPageToken ?: break
        }

        if (cursor.phase == ImportPhase.INITIAL_LOAD) {
            cursor = cursor.withInitialLoadComplete()
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

    private fun stage(capability: HealthCapability, record: SourceRecord): NewOutboxItem = NewOutboxItem(
        category = capability.category,
        recordType = capability.recordType,
        samsungUid = record.uid,
        envelopeJson = IngestionContract.json.encodeToString(
            HealthRecordEnvelope.serializer(),
            capability.mapper.map(record),
        ),
        enqueuedAt = clock.instant(),
    )

    private fun lastLocalStart(records: List<SourceRecord>): LocalDateTime =
        records.maxOf { LocalDateTime.ofInstant(it.startTime, it.zoneOffset) }
}
