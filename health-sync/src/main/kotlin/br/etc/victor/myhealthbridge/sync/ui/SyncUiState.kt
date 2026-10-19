package br.etc.victor.myhealthbridge.sync.ui

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.CatalogEntry
import br.etc.victor.myhealthbridge.sync.HealthCapabilities
import br.etc.victor.myhealthbridge.sync.HealthCapability
import br.etc.victor.myhealthbridge.sync.ImportPhase
import br.etc.victor.myhealthbridge.sync.IngestionEndpoint
import br.etc.victor.myhealthbridge.sync.OutboxSize
import br.etc.victor.myhealthbridge.sync.SyncCursor
import br.etc.victor.myhealthbridge.sync.SyncOutcome
import java.time.Instant

/** What this build does with a cataloged Health Category, as the capability catalog declares it. */
enum class SyncCoverage {
    SYNCHRONIZED,

    /** Read as part of another category, which is the one synchronized. */
    CARRIED,

    /** Cataloged but not read by this build: a gap shown as one. */
    NOT_IMPLEMENTED,
    ;

    companion object {
        fun of(entry: CatalogEntry): SyncCoverage = when {
            entry is HealthCapability -> SYNCHRONIZED
            entry.implemented -> CARRIED
            else -> NOT_IMPLEMENTED
        }
    }
}

/** How the synchronization of one Health Category stands, as the screen shows it. */
data class CategorySyncState(
    val category: HealthCategory,
    val coverage: SyncCoverage,
    val phase: ImportPhase,
    /** How much of the initial load window has been walked, or null outside an initial load. */
    val initialLoadProgress: Float?,
    val importedRecords: Long,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    /** When the last overlap re-read pulled this category's read back, or null while none has. */
    val lastOverlapAt: Instant?,
    val outcome: SyncOutcome?,
)

data class SyncUiState(
    val endpoint: IngestionEndpoint? = null,
    val outbox: OutboxSize = OutboxSize(),
    val categories: List<CategorySyncState> = emptyList(),
) {

    val configured: Boolean get() = endpoint != null

    val initialLoadPending: Boolean
        get() = categories.any { it.coverage == SyncCoverage.SYNCHRONIZED && it.phase == ImportPhase.NOT_STARTED }

    companion object {

        /**
         * Every entry of the capability catalog appears, with or without a cursor: a category that was
         * never synchronized, or that this build does not read, is a state the Data Owner has to see.
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
                categories = HealthCapabilities.entries.map { entry ->
                    stateOf(entry.category, SyncCoverage.of(entry), byCategory[entry.category])
                },
            )
        }

        private fun stateOf(category: HealthCategory, coverage: SyncCoverage, cursor: SyncCursor?): CategorySyncState =
            CategorySyncState(
                category = category,
                coverage = coverage,
                phase = cursor?.phase ?: ImportPhase.NOT_STARTED,
                initialLoadProgress = cursor
                    ?.takeIf { it.phase == ImportPhase.INITIAL_LOAD }
                    ?.let { it.initialLoad?.progressOf(it.readFrom) },
                importedRecords = cursor?.importedRecords ?: 0,
                lastAttemptAt = cursor?.lastAttemptAt,
                lastSuccessAt = cursor?.lastSuccessAt,
                lastOverlapAt = cursor?.lastOverlapAt,
                outcome = cursor?.lastOutcome,
            )
    }
}
