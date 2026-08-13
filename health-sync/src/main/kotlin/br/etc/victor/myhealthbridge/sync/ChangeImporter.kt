package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import java.time.Clock

/**
 * Reads what changed at the source into the outbox, for one Health Category.
 *
 * It is what makes a later edit reach the Current Health Record and a Source Removal reach it at all:
 * the history walk is filtered by the record's own time, so it never returns to a record it already
 * passed, and no time range read can report an absence.
 *
 * The cursor moves only over changes already staged, exactly as the history walk does.
 */
class ChangeImporter(
    private val source: HealthRecordSource,
    private val store: SyncStore,
    private val policy: SyncPolicy,
    private val clock: Clock,
) {

    /** [resumeFrom] continues a walk this importer paused, which the cursor alone cannot express. */
    suspend fun import(capability: HealthCapability, resumeFrom: String? = null): ImportResult {
        if (!capability.supportsChanges) return ImportResult.Completed
        val cursor = store.cursor(capability.category) ?: return ImportResult.Completed
        // Until the accessible history has been walked once there is nothing here to reflect a change on.
        if (cursor.phase != ImportPhase.INCREMENTAL) return ImportResult.Completed
        val since = cursor.changesFrom ?: return ImportResult.Completed

        var read = cursor
        var pageToken = resumeFrom
        var staged = 0

        while (true) {
            if (store.pendingCount() >= policy.maxOutboxItems) return ImportResult.Paused(pageToken, staged)

            val page = when (val outcome = source.readChanges(capability, since, pageToken)) {
                is SamsungHealthOutcome.Failed -> return ImportResult.Failed(outcome.availability)
                is SamsungHealthOutcome.Observed -> outcome.value
            }

            if (page.changes.isNotEmpty()) {
                read = read.changesReadTo(page.changes.maxOf { it.changedAt })
                store.acceptPage(page.changes.map { stage(capability, it) }, read)
                staged += page.changes.size
            }

            pageToken = page.nextPageToken ?: break
        }

        return ImportResult.Completed
    }

    private fun stage(capability: HealthCapability, change: SourceChange): NewOutboxItem = when (change) {
        is SourceChange.Upserted -> capability.staged(
            uid = change.record.uid,
            envelope = capability.mapper.map(change.record),
            at = clock.instant(),
        )

        is SourceChange.Removed -> capability.staged(
            uid = change.uid,
            envelope = capability.mapper.removalOf(change.uid, change.changedAt),
            at = clock.instant(),
        )
    }
}
