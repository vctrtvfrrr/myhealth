package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.CheckResult
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.PermissionState
import java.time.Clock
import java.time.LocalDateTime

/**
 * One synchronization run over every cataloged capability.
 *
 * Categories advance independently: each one records how its own run ended, and a category that
 * cannot be read leaves the others untouched. Whether the run was asked for by the hourly schedule or
 * by the Data Owner makes no difference here — the same idempotent pipeline serves both.
 */
class SyncService(
    private val permissions: HealthPermissionsService,
    private val importer: HistoryImporter,
    private val sender: OutboxSender,
    private val store: SyncStore,
    private val clock: Clock,
) {

    /**
     * Starts the initial load of every capability whose read permission is granted.
     *
     * A category already importing is left alone: this begins the walk of the accessible history, it
     * does not restart one.
     */
    suspend fun startInitialLoad() {
        val states = grantedStates() ?: return
        val now = LocalDateTime.now(clock)

        HealthCapabilities.entries.forEach { capability ->
            if (states[capability.category] != PermissionState.GRANTED) return@forEach
            val cursor = cursorOf(capability.category)
            if (cursor.phase != ImportPhase.NOT_STARTED) return@forEach
            store.writeCursor(cursor.startingInitialLoad(now))
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
        if (states == null) return SyncOutcome.SAMSUNG_UNAVAILABLE
        if (states[capability.category] != PermissionState.GRANTED) return SyncOutcome.WAITING_PERMISSION

        while (true) {
            // Delivering first is what makes room: the import stops reading while the outbox is full,
            // and only a confirmed item frees a slot.
            (sender.drain(capability) as? SendResult.Halted)?.let { return it.outcome }

            when (importer.import(capability)) {
                is ImportResult.Failed -> return SyncOutcome.SAMSUNG_UNAVAILABLE
                ImportResult.Completed -> break
                ImportResult.Paused -> Unit
            }
        }

        (sender.drain(capability) as? SendResult.Halted)?.let { return it.outcome }
        return SyncOutcome.SUCCEEDED
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
