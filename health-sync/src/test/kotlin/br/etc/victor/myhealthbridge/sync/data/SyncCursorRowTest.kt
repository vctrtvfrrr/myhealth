package br.etc.victor.myhealthbridge.sync.data

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.ImportPhase
import br.etc.victor.myhealthbridge.sync.SyncOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * How a stored row becomes a Sync Cursor, and what happens to one that cannot be read.
 *
 * A default position is the thing this must never produce: reading on from a guessed place leaves a
 * stretch of history nobody ever reads again, and nothing would report that it happened.
 */
class SyncCursorRowTest {

    @Test
    fun `reads back the position a run left`() {
        val cursor = row().toCursor()!!

        assertEquals(HealthCategory.HEART_RATE, cursor.category)
        assertEquals(ImportPhase.INCREMENTAL, cursor.phase)
        assertEquals(LocalDateTime.of(2026, 8, 11, 12, 0), cursor.readFrom)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), cursor.changesFrom)
        assertEquals(Instant.ofEpochMilli(1_700_000_100_000), cursor.lastOverlapAt)
        assertEquals(SyncOutcome.SUCCEEDED, cursor.lastOutcome)
        assertNull(cursor.unrecoverable)
    }

    @Test
    fun `keeps a row it cannot interpret as an unrecoverable cursor`() {
        assertNotNull(row(readFrom = "not-a-local-time").toCursor()!!.unrecoverable)
        assertNotNull(row(phase = "SOMETHING_ELSE").toCursor()!!.unrecoverable)
        assertNotNull(row(initialLoadStart = "not-a-local-time").toCursor()!!.unrecoverable)
    }

    /** A past outcome says nothing about where the import stands, so it never costs the position. */
    @Test
    fun `drops an outcome it does not know instead of losing the position`() {
        val cursor = row(lastOutcome = "SOMETHING_ELSE").toCursor()!!

        assertNull(cursor.lastOutcome)
        assertNull(cursor.unrecoverable)
        assertEquals(LocalDateTime.of(2026, 8, 11, 12, 0), cursor.readFrom)
    }

    @Test
    fun `skips a row naming a category this build does not catalog`() {
        assertNull(row(categoryId = "unknown_category").toCursor())
    }

    private fun row(
        categoryId: String = HealthCategory.HEART_RATE.id,
        phase: String = ImportPhase.INCREMENTAL.name,
        readFrom: String = "2026-08-11T12:00",
        initialLoadStart: String? = "2000-01-01T00:00",
        lastOutcome: String? = SyncOutcome.SUCCEEDED.name,
    ) = SyncCursorEntity(
        categoryId = categoryId,
        phase = phase,
        readFrom = readFrom,
        initialLoadStart = initialLoadStart,
        initialLoadEnd = "2026-08-11T12:00",
        changesFrom = 1_700_000_000_000,
        lastOverlapAt = 1_700_000_100_000,
        importedRecords = 7,
        lastAttemptAt = null,
        lastSuccessAt = null,
        lastOutcome = lastOutcome,
    )
}
