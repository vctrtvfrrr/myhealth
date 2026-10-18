package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import java.time.Clock
import java.time.LocalDateTime

/**
 * Reads the aggregates of one snapshot capability into the outbox, window by window, as Observed
 * Snapshots dated by the moment they were read.
 *
 * Every window from the cursor up to the one still open is observed, a window Samsung Health aggregated
 * nothing over included: it is sent as an empty observation, because only that lets a total deleted at
 * the source stop being current. Re-reading costs nothing the API keeps, since it stores an observation
 * only when it differs from the one before it. See ADR 0025.
 *
 * The cursor never passes the window that was open when it was read, so the next run reads it again once
 * it has closed; reading from there up to now is the whole recovery a snapshot has, together with the
 * overlap re-read that pulls the cursor back a week every day.
 */
class SnapshotImporter(
    private val source: HealthRecordSource,
    private val store: SyncStore,
    private val policy: SyncPolicy,
    private val clock: Clock,
) {

    suspend fun import(capability: SnapshotCapability): ImportResult {
        var cursor = store.cursor(capability.category) ?: return ImportResult.Completed
        if (cursor.phase == ImportPhase.NOT_STARTED) return ImportResult.Completed

        val unit = capability.window
        var staged = 0

        while (true) {
            if (store.pendingCount() >= policy.maxOutboxItems) return ImportResult.Paused(pageToken = null, staged)

            val readAt = clock.instant()
            val now = LocalDateTime.ofInstant(readAt, clock.zone)
            val until = unit.ceiling(readUntil(cursor, now))
            val from = unit.floor(cursor.readFrom)
            if (!from.isBefore(until)) break

            val to = minOf(unit.advance(from, capability.windowsPerRead.toLong()), until)
            val aggregates = when (val outcome = source.readAggregates(capability, AggregateWindow(from, to))) {
                is SamsungHealthOutcome.Failed -> return ImportResult.Failed(outcome.availability)
                is SamsungHealthOutcome.Observed -> outcome.value.associate { it.start to it.value }
            }

            val observedAt = zoned(readAt, clock.zone.rules.getOffset(readAt))
            val items = unit.windowsIn(from, to).map { window ->
                capability.staged(capability.mapper.map(window, aggregates[window.start], observedAt), readAt)
            }
            cursor = cursor.advancedTo(minOf(to, unit.floor(now)), items.size)
            store.acceptPage(items, cursor)
            staged += items.size

            if (!to.isBefore(until)) break
        }

        if (cursor.phase == ImportPhase.INITIAL_LOAD) {
            val loaded = cursor.withInitialLoadComplete(clock.instant())
            // The initial load ends at the moment it began, which is rarely where a window starts.
            store.writeCursor(loaded.copy(readFrom = unit.floor(loaded.readFrom)))
        }
        return ImportResult.Completed
    }

    /** The initial load stops at the boundary it was given, as a history walk does. */
    private fun readUntil(cursor: SyncCursor, now: LocalDateTime): LocalDateTime = when (cursor.phase) {
        ImportPhase.INITIAL_LOAD -> cursor.initialLoad?.end ?: now
        else -> now
    }
}
