package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService
import java.time.Clock

/**
 * Reads what changed at the source into the outbox, for one Health Category.
 *
 * It is what makes a later edit reach the Current Health Record and a Source Removal reach it at all:
 * the history walk is filtered by the record's own time, so it never returns to a record it already
 * passed, and no time range read can report an absence.
 *
 * The interval it reads is fixed before anything inside it is read, and the cursor moves only once all
 * of it is staged. The feed exposes no ordering to ask for, so a page's largest change time bounds
 * nothing: advancing over it would step past an older change a later page still held, and a process
 * death in between would lose that change for good. Repeating the whole interval is the cost, and the
 * ingestion is idempotent under exactly it.
 */
class ChangeImporter(
    private val source: HealthRecordSource,
    private val store: SyncStore,
    private val maintenance: MaintenanceService,
    private val policy: SyncPolicy,
    private val clock: Clock,
) {

    /** [resumeFrom] continues a walk this importer paused, which the cursor alone cannot express. */
    suspend fun import(capability: HealthCapability, resumeFrom: String? = null): ImportResult {
        if (!capability.supportsChanges) return ImportResult.Completed
        var cursor = store.cursor(capability.category) ?: return ImportResult.Completed
        // Until the accessible history has been walked once there is nothing here to reflect a change on.
        if (cursor.phase != ImportPhase.INCREMENTAL) return ImportResult.Completed
        val from = cursor.changesFrom ?: return ImportResult.Completed

        // Durable, so that a walk interrupted by process death repeats the interval it had fixed rather
        // than one that starts where the reading happened to stop.
        if (cursor.changesUntil == null) {
            cursor = cursor.openingChangesWalk(clock.instant())
            store.writeCursor(cursor)
        }
        val window = ChangeWindow(from, cursor.changesUntil ?: return ImportResult.Completed)

        var pageToken = resumeFrom
        var staged = 0

        while (true) {
            if (store.pendingCount() >= policy.maxOutboxItems) return ImportResult.Paused(pageToken, staged)

            val page = when (val outcome = source.readChanges(capability, window, pageToken)) {
                is SamsungHealthOutcome.Failed -> return ImportResult.Failed(outcome.availability)
                is SamsungHealthOutcome.Observed -> outcome.value
            }

            if (page.changes.isNotEmpty()) {
                store.acceptPage(page.changes.map { stage(capability, it) }, cursor)
                staged += page.changes.size
            }

            pageToken = page.nextPageToken ?: break
        }

        store.writeCursor(cursor.withChangesWalkComplete())
        return ImportResult.Completed
    }

    private suspend fun stage(capability: HealthCapability, change: SourceChange): NewOutboxItem = when (change) {
        is SourceChange.Upserted -> {
            maintenance.reportUnknownEnums(capability, change.record)
            capability.staged(
                uid = change.record.uid,
                envelope = capability.mapper.map(change.record),
                at = clock.instant(),
            )
        }

        is SourceChange.Removed -> capability.staged(
            uid = change.uid,
            envelope = capability.mapper.removalOf(change.uid, change.changedAt),
            at = clock.instant(),
        )
    }
}
