package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What the changes feed adds to the import: an edit made after a record was already imported, and a
 * Source Removal, which no time range read can report at all.
 */
class ChangeImporterTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC)
    private val store = FakeSyncStore()

    private val changedAt = Instant.parse("2026-08-12T09:00:00Z")

    private fun importer(vararg pages: SamsungHealthOutcome<ChangePage>, source: FakeRecordSource? = null) =
        ChangeImporter(
            source = source ?: FakeRecordSource(pages = emptyList(), changePages = pages.toList()),
            store = store,
            maintenance = maintenanceService(FakeMaintenance(), clock),
            policy = SyncPolicy(maxOutboxItems = 3),
            clock = clock,
        )

    /** A category whose accessible history was walked, which is the only state changes are read in. */
    private suspend fun incremental() = store.writeCursor(
        SyncCursor(heartRate.category)
            .startingInitialLoad(LocalDateTime.of(2026, 8, 11, 9, 0), Instant.parse("2026-08-11T00:00:00Z"))
            .withInitialLoadComplete(Instant.parse("2026-08-11T09:00:00Z")),
    )

    private fun stagedEnvelope(index: Int = 0): HealthRecordEnvelope = IngestionContract.json.decodeFromString(
        HealthRecordEnvelope.serializer(),
        store.staged[index].item.envelopeJson,
    )

    @Test
    fun `reads no changes until the accessible history has been walked`() = runTest {
        store.writeCursor(SyncCursor(heartRate.category).startingInitialLoad(LocalDateTime.now(clock), clock.instant()))

        val result = importer(changePage(listOf(SourceChange.Removed(changedAt, "uid-1")))).import(heartRate)

        assertSame(ImportResult.Completed, result)
        assertTrue(store.staged.isEmpty())
    }

    @Test
    fun `stages an edited record as another observation of the same Health Record`() = runTest {
        incremental()
        val edited = sourceRecord(uid = "uid-1", updateTime = changedAt, fields = mapOf("heart_rate" to number("81.0")))

        val result = importer(changePage(listOf(SourceChange.Upserted(changedAt, edited)))).import(heartRate)

        assertSame(ImportResult.Completed, result)
        assertEquals("uid-1", store.staged.single().item.samsungUid)
        // The identity is unchanged and the observation is dated by the edit, which is what makes it
        // the newer version of the same record rather than a second record.
        assertEquals(changedAt.toString(), stagedEnvelope().observedAt.instant)
        assertTrue(stagedEnvelope().state is RecordState.Present)
    }

    @Test
    fun `stages a Source Removal as an observation without biometric content`() = runTest {
        incremental()

        importer(changePage(listOf(SourceChange.Removed(changedAt, "uid-gone")))).import(heartRate)

        val envelope = stagedEnvelope()
        assertEquals("uid-gone", envelope.samsungUid)
        assertEquals(RecordState.Removed(period = null), envelope.state)
        assertEquals(changedAt.toString(), envelope.observedAt.instant)
        // A removal says the record is gone, never who removed it.
        assertEquals(SourceIdentity.Unknown, envelope.sourceProvenance.sourceApp)
        assertEquals(SourceIdentity.Unknown, envelope.sourceProvenance.sourceDevice)
    }

    /**
     * The feed exposes no ordering to ask for, so a page that happens to hold the latest change proves
     * nothing about what a later page still holds. Advancing over it would drop the older change for
     * good, and this is the case that would do it.
     */
    @Test
    fun `keeps the whole interval to read again when a run is cut short mid walk`() = runTest {
        incremental()
        val older = Instant.parse("2026-08-11T10:00:00Z")
        val source = FakeRecordSource(
            pages = emptyList(),
            changePages = listOf(
                changePage(listOf(SourceChange.Removed(changedAt, "uid-late")), nextPageToken = "next"),
                SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("timeout")),
            ),
        )

        val result = importer(source = source).import(heartRate)

        assertEquals(ImportResult.Failed(SamsungHealthAvailability.TemporarilyUnavailable("timeout")), result)
        // The page it did read is kept, and the position it read from did not move over it.
        assertEquals(1, store.acceptedPages)
        val cursor = store.cursor(heartRate.category)!!
        assertEquals(Instant.parse("2026-08-11T00:00:00Z"), cursor.changesFrom)

        // The next run repeats the same interval, which is what lets it reach the older change that the
        // unread page held.
        store.confirm(store.staged.map { it.id })
        val resumed = FakeRecordSource(
            pages = emptyList(),
            changePages = listOf(changePage(listOf(SourceChange.Removed(older, "uid-early")))),
        )
        importer(source = resumed).import(heartRate)

        assertEquals(cursor.changesUntil, resumed.changeWindows.first().to)
        assertEquals(Instant.parse("2026-08-11T00:00:00Z"), resumed.changeWindows.first().from)
        assertEquals(listOf("uid-early"), store.staged.map { it.item.samsungUid })
    }

    @Test
    fun `reads the next run from where the walk it completed ended`() = runTest {
        incremental()
        importer(changePage(listOf(SourceChange.Removed(changedAt, "uid-1")))).import(heartRate)
        store.confirm(store.staged.map { it.id })

        val source = FakeRecordSource(pages = emptyList())
        importer(source = source).import(heartRate)

        // The top of the interval that completed, not the largest change time inside it.
        assertEquals(clock.instant(), source.changeWindows.last().from)
        assertNull(store.cursor(heartRate.category)!!.changesUntil)
    }

    @Test
    fun `continues inside the same interval after the outbox drains`() = runTest {
        incremental()
        val changes = (1..3).map { SourceChange.Removed(changedAt, "uid-$it") }
        val source = FakeRecordSource(
            pages = emptyList(),
            changePages = listOf(
                changePage(changes, nextPageToken = "next"),
                changePage(listOf(SourceChange.Removed(changedAt, "uid-4"))),
            ),
        )
        val paused = importer(source = source).import(heartRate)
        store.confirm(store.staged.map { it.id })

        val resumed = importer(source = source).import(heartRate, (paused as ImportResult.Paused).pageToken)

        assertSame(ImportResult.Completed, resumed)
        assertEquals(listOf("uid-4"), store.staged.map { it.item.samsungUid })
        // One interval throughout, closed only once all of it was staged.
        assertEquals(1, source.changeWindows.map { it.to }.distinct().size)
        assertEquals(clock.instant(), store.cursor(heartRate.category)!!.changesFrom)
    }

    @Test
    fun `stops reading while the outbox is at its limit, saying where it stopped`() = runTest {
        incremental()
        val changes = (1..3).map { SourceChange.Removed(changedAt, "uid-$it") }

        val result = importer(
            changePage(changes, nextPageToken = "next"),
            changePage(listOf(SourceChange.Removed(changedAt, "uid-4"))),
        ).import(heartRate)

        assertEquals(ImportResult.Paused(pageToken = "next", staged = 3), result)
    }
}
