package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class HistoryImporterTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC)
    private val store = FakeSyncStore()

    private fun importer(vararg pages: SamsungHealthOutcome<RecordPage>, source: FakeRecordSource? = null) =
        HistoryImporter(
            source = source ?: FakeRecordSource(pages.toList()),
            store = store,
            policy = SyncPolicy(maxOutboxItems = 3),
            clock = clock,
        )

    private suspend fun startInitialLoad() = store.writeCursor(
        SyncCursor(heartRate.category).startingInitialLoad(LocalDateTime.of(2026, 8, 11, 9, 0), Instant.parse("2026-08-11T00:00:00Z")),
    )

    @Test
    fun `imports nothing until the initial load is started`() = runTest {
        store.writeCursor(SyncCursor(heartRate.category))

        val result = importer(page(listOf(sourceRecord()))).import(heartRate)

        assertSame(ImportResult.Completed, result)
        assertTrue(store.staged.isEmpty())
    }

    @Test
    fun `walks every page of the window and stages what it reads`() = runTest {
        startInitialLoad()

        val result = importer(
            page(listOf(sourceRecord(uid = "a"), sourceRecord(uid = "b")), nextPageToken = "next"),
            page(listOf(sourceRecord(uid = "c"))),
        ).import(heartRate)

        assertSame(ImportResult.Completed, result)
        assertEquals(listOf("a", "b", "c"), store.staged.map { it.item.samsungUid })
        assertEquals(3, store.cursor(heartRate.category)!!.importedRecords)
    }

    /** One page staged, then the run cut short before the window was walked. */
    private suspend fun runCutShort() {
        startInitialLoad()
        importer(
            page(listOf(sourceRecord(uid = "a", start = Instant.parse("2026-08-10T21:59:00Z"))), nextPageToken = "next"),
            SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("timeout")),
        ).import(heartRate)
    }

    @Test
    fun `moves the cursor only over a page it already staged`() = runTest {
        runCutShort()

        // The store keeps a page and its cursor in one write, so a cursor that moved is a page staged.
        assertEquals(1, store.acceptedPages)
        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 59), store.cursor(heartRate.category)!!.readFrom)
    }

    @Test
    fun `resumes from the last staged page after the run is cut short`() = runTest {
        runCutShort()

        val source = FakeRecordSource(listOf(page(emptyList())))
        importer(source = source).import(heartRate)

        assertEquals(LocalDateTime.of(2026, 8, 10, 18, 59), source.windows.last().from)
    }

    @Test
    fun `becomes incremental once the initial load window is walked`() = runTest {
        startInitialLoad()

        importer(page(listOf(sourceRecord()))).import(heartRate)

        val cursor = store.cursor(heartRate.category)!!
        assertEquals(ImportPhase.INCREMENTAL, cursor.phase)
        assertEquals(LocalDateTime.of(2026, 8, 11, 9, 0), cursor.readFrom)
    }

    @Test
    fun `stops reading while the outbox is at its limit, saying where it stopped`() = runTest {
        startInitialLoad()

        val result = importer(
            page(listOf(sourceRecord(uid = "a"), sourceRecord(uid = "b"), sourceRecord(uid = "c")), nextPageToken = "next"),
            page(listOf(sourceRecord(uid = "d"))),
        ).import(heartRate)

        assertEquals(ImportResult.Paused(pageToken = "next", staged = 3), result)
        assertEquals(3, store.staged.size)
    }

    @Test
    fun `reports staging nothing when the outbox was already full`() = runTest {
        startInitialLoad()
        store.acceptPage(
            List(3) { foreignItem("other-$it") },
            store.cursor(heartRate.category)!!,
        )

        val result = importer(page(listOf(sourceRecord()))).import(heartRate)

        assertEquals(ImportResult.Paused(pageToken = null, staged = 0), result)
    }

    @Test
    fun `crosses a group of records sharing one local time that outgrows the outbox`() = runTest {
        startInitialLoad()
        val tied = Instant.parse("2026-08-10T21:59:00Z")
        val source = FakeRecordSource(
            listOf(
                page(listOf(sourceRecord(uid = "a", start = tied), sourceRecord(uid = "b", start = tied)), "p2"),
                page(listOf(sourceRecord(uid = "c", start = tied), sourceRecord(uid = "d", start = tied)), "p3"),
                page(listOf(sourceRecord(uid = "e", start = tied))),
            ),
        )

        val paused = importer(source = source).import(heartRate)
        store.confirm(store.staged.map { it.id })
        val resumed = importer(source = source).import(heartRate, (paused as ImportResult.Paused).pageToken)

        assertSame(ImportResult.Completed, resumed)
        assertEquals("p3", source.tokens.last())
        assertEquals(listOf("e"), store.staged.map { it.item.samsungUid })
    }

    @Test
    fun `reports a Samsung Health failure without moving the cursor`() = runTest {
        startInitialLoad()
        val unavailable = SamsungHealthAvailability.TemporarilyUnavailable("timeout")

        val result = importer(SamsungHealthOutcome.Failed(unavailable)).import(heartRate)

        assertEquals(ImportResult.Failed(unavailable), result)
        assertEquals(HISTORY_FLOOR, store.cursor(heartRate.category)!!.readFrom)
    }
}
