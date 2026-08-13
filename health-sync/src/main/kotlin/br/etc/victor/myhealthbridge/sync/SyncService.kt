package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.CheckResult
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.PermissionState
import java.time.Clock
import java.time.LocalDateTime

/** What a synchronization run is asked to do before it delivers, beyond reading on. */
enum class SyncRun {
    INCREMENTAL,

    /** Begins the walk of the accessible history where it was never begun. */
    INITIAL_LOAD,

    /** Begins it again everywhere, trusting no cursor. */
    FULL_RECONCILIATION,
}

/**
 * One synchronization run over every cataloged capability.
 *
 * A run reads a category twice: forward from its cursor, and then over what the source reports as
 * changed. The second read is what reflects a later edit and a Source Removal, neither of which a
 * forward walk can see, and the overlap re-read covers what the changes feed itself may have missed.
 *
 * Categories advance independently: each one records how its own run ended, and a category that
 * cannot be read leaves the others untouched. Whether the run was asked for by the hourly schedule or
 * by the Data Owner makes no difference here — the same idempotent pipeline serves both.
 */
class SyncService(
    private val permissions: HealthPermissionsService,
    private val importer: HistoryImporter,
    private val changes: ChangeImporter,
    private val sender: OutboxSender,
    private val store: SyncStore,
    private val policy: SyncPolicy,
    private val clock: Clock,
) {

    /**
     * Starts the initial load of every capability whose read permission is granted.
     *
     * A category already importing is left alone: this begins the walk of the accessible history, it
     * does not restart one.
     */
    suspend fun startInitialLoad() = walkAccessibleHistory { it.phase == ImportPhase.NOT_STARTED }

    /**
     * Re-reads the whole accessible history of every capability whose read permission is granted.
     *
     * This is what the Data Owner asks for after losing local state or suspecting a divergence: it
     * trusts no cursor. Asking for it costs a re-read and nothing else, because the same records render
     * identically and the ingestion answers `already_present` for every one it already holds.
     */
    suspend fun reconcile() = walkAccessibleHistory { true }

    private suspend fun walkAccessibleHistory(restart: (SyncCursor) -> Boolean) {
        val states = grantedStates() ?: return
        val now = LocalDateTime.now(clock)
        val at = clock.instant()

        HealthCapabilities.entries.forEach { capability ->
            if (states[capability.category] != PermissionState.GRANTED) return@forEach
            val cursor = cursorOf(capability.category)
            if (!restart(cursor)) return@forEach
            store.writeCursor(cursor.startingInitialLoad(now, at))
        }
    }

    suspend fun sync() {
        val attemptedAt = clock.instant()
        val states = grantedStates()

        HealthCapabilities.entries.forEach { capability ->
            val outcome = run(capability, states)
            // Read again: the import moved the cursor, and this write only records how the run ended.
            store.writeCursor(cursorOf(capability.category).attempted(attemptedAt, outcome))
        }
    }

    private suspend fun run(
        capability: HealthCapability,
        states: Map<HealthCategory, PermissionState>?,
    ): SyncOutcome {
        // Ahead of everything else, and of the permission this category may not even have: an
        // unrecoverable cursor is local, and leaving one in place would let a later write bury it.
        if (restartAfterUnrecoverableCursor(capability)) return SyncOutcome.CURSOR_UNRECOVERABLE
        if (states == null) return SyncOutcome.SAMSUNG_UNAVAILABLE
        if (states[capability.category] != PermissionState.GRANTED) return SyncOutcome.WAITING_PERMISSION

        takeOverlapReread(capability)

        deliverWhileReading(capability) { importer.import(capability, it) }?.let { return it }
        deliverWhileReading(capability) { changes.import(capability, it) }?.let { return it }

        (sender.drain(capability) as? SendResult.Halted)?.let { return it.outcome }
        return SyncOutcome.SUCCEEDED
    }

    /** Null once the read has nothing left; an outcome names why it stopped before that. */
    private suspend fun deliverWhileReading(
        capability: HealthCapability,
        read: suspend (resumeFrom: String?) -> ImportResult,
    ): SyncOutcome? {
        var resumeFrom: String? = null

        while (true) {
            // Delivering first is what makes room: a read stops while the outbox is full, and only a
            // confirmed item frees a slot.
            (sender.drain(capability) as? SendResult.Halted)?.let { return it.outcome }

            when (val imported = read(resumeFrom)) {
                is ImportResult.Failed -> return SyncOutcome.SAMSUNG_UNAVAILABLE
                ImportResult.Completed -> return null
                is ImportResult.Paused -> {
                    // The outbox bounds the device as a whole, so it can be full of another record
                    // type this drain does not touch. A pause that staged nothing is that case, and
                    // reading again would spin instead of waiting for the next run.
                    if (imported.staged == 0) return SyncOutcome.OUTBOX_FULL
                    resumeFrom = imported.pageToken
                }
            }
        }
    }

    /**
     * Answers a cursor that cannot say where the import stands with a re-read of the whole accessible
     * history, and ends the run there so that the Data Owner is told the position was lost.
     *
     * The re-read is durable before this returns, so the next run performs it whether or not anyone is
     * watching. Reading on from a guessed position instead would leave a gap nothing ever reports.
     */
    private suspend fun restartAfterUnrecoverableCursor(capability: HealthCapability): Boolean {
        val cursor = cursorOf(capability.category)
        if (cursor.unrecoverable == null) return false
        store.writeCursor(cursor.startingInitialLoad(LocalDateTime.now(clock), clock.instant()))
        return true
    }

    /**
     * Pulls the read back over the overlap window when one is due.
     *
     * It is what keeps a cursor from being the only thing standing between the Personal Health History
     * and a change nobody reported: the window is walked again on its own, day after day.
     */
    private suspend fun takeOverlapReread(capability: HealthCapability) {
        val at = clock.instant()
        val cursor = cursorOf(capability.category)
        if (!cursor.overlapDue(at, policy.overlapEvery)) return
        store.writeCursor(cursor.withOverlap(policy.overlapWindow, at))
    }

    /** Null when Samsung Health could not answer, so no Permission State can be claimed. */
    private suspend fun grantedStates(): Map<HealthCategory, PermissionState>? =
        when (val check = permissions.check()) {
            is CheckResult.Unavailable -> null
            is CheckResult.Observed -> check.observation.states
        }

    private suspend fun cursorOf(category: HealthCategory): SyncCursor =
        store.cursor(category) ?: SyncCursor(category).also { store.writeCursor(it) }
}
