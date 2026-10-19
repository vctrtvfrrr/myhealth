package br.etc.victor.myhealthbridge.sync.ui

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.ImportPhase
import br.etc.victor.myhealthbridge.sync.OutboxSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncUiStateTest {

    private fun stateWithoutCursors() = SyncUiState.of(endpoint = null, outbox = OutboxSize(), cursors = emptyList())

    @Test
    fun `shows every cataloged category, in display order`() {
        assertEquals(HealthCategory.entries, stateWithoutCursors().categories.map { it.category })
    }

    @Test
    fun `tells a synchronized category from a gap and from a permission carried by another`() {
        val coverage = stateWithoutCursors().categories.associate { it.category to it.coverage }

        assertEquals(SyncCoverage.SYNCHRONIZED, coverage[HealthCategory.HEART_RATE])
        assertEquals(SyncCoverage.SYNCHRONIZED, coverage[HealthCategory.STEPS])
        assertEquals(SyncCoverage.CARRIED, coverage[HealthCategory.EXERCISE_LOCATION])
        assertEquals(SyncCoverage.NOT_IMPLEMENTED, coverage[HealthCategory.BLOOD_PRESSURE])
        assertEquals(SyncCoverage.SYNCHRONIZED, coverage[HealthCategory.USER_PROFILE])
    }

    /** A gap has no history to walk, so it must not keep asking for an initial load nobody can start. */
    @Test
    fun `asks for an initial load only while a synchronized category has not started one`() {
        assertTrue(stateWithoutCursors().initialLoadPending)

        val started = stateWithoutCursors().categories.map {
            if (it.coverage == SyncCoverage.SYNCHRONIZED) it.copy(phase = ImportPhase.INCREMENTAL) else it
        }

        assertFalse(SyncUiState(categories = started).initialLoadPending)
    }
}
