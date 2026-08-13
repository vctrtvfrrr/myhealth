package br.etc.victor.myhealthbridge.sync.ui

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.HealthCapabilities
import br.etc.victor.myhealthbridge.sync.ImportPhase
import br.etc.victor.myhealthbridge.sync.IngestionEndpoint
import br.etc.victor.myhealthbridge.sync.OutboxSize
import br.etc.victor.myhealthbridge.sync.SyncCursor
import br.etc.victor.myhealthbridge.sync.SyncOutcome
import java.time.Instant

/** How the synchronization of one Health Category stands, as the screen shows it. */
data class CategorySyncState(
    val category: HealthCategory,
    val phase: ImportPhase,
    /** How much of the initial load window has been walked, or null outside an initial load. */
    val initialLoadProgress: Float?,
    val importedRecords: Long,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val outcome: SyncOutcome?,
)

data class SyncUiState(
    val endpoint: IngestionEndpoint? = null,
    val outbox: OutboxSize = OutboxSize(),
    val categories: List<CategorySyncState> = emptyList(),
) {

    val configured: Boolean get() = endpoint != null

    val initialLoadPending: Boolean get() = categories.any { it.phase == ImportPhase.NOT_STARTED }

    companion object {

        /**
         * Every cataloged capability appears, with or without a cursor: a category that was never
         * synchronized is a state the Data Owner has to be able to see.
         */
        fun of(
            endpoint: IngestionEndpoint?,
            outbox: OutboxSize,
            cursors: List<SyncCursor>,
        ): SyncUiState {
            val byCategory = cursors.associateBy { it.category }
            return SyncUiState(
                endpoint = endpoint,
                outbox = outbox,
                categories = HealthCapabilities.entries.map { capability ->
                    stateOf(capability.category, byCategory[capability.category])
                },
            )
        }

        private fun stateOf(category: HealthCategory, cursor: SyncCursor?): CategorySyncState =
            CategorySyncState(
                category = category,
                phase = cursor?.phase ?: ImportPhase.NOT_STARTED,
                initialLoadProgress = cursor
                    ?.takeIf { it.phase == ImportPhase.INITIAL_LOAD }
                    ?.let { it.initialLoad?.progressOf(it.readFrom) },
                importedRecords = cursor?.importedRecords ?: 0,
                lastAttemptAt = cursor?.lastAttemptAt,
                lastSuccessAt = cursor?.lastSuccessAt,
                outcome = cursor?.lastOutcome,
            )
    }
}
