package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.InventoryResult
import br.etc.victor.myhealthbridge.contract.InventoryStatus
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.LocalInterval
import br.etc.victor.myhealthbridge.contract.RecordInventory
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What the application declares it saw, so that the API can conclude a Source Removal nobody reported.
 *
 * Leaving a record out of a declared stretch is a removal, so every test here is about what a stretch
 * may claim: only what was read in full, and only once every observation inside it was settled.
 */
class DeclaredInventoryTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private fun importer(source: FakeRecordSource, policy: SyncPolicy = SyncPolicy()) = HistoryImporter(
        source = source,
        store = store,
        maintenance = maintenanceService(maintenance, clock),
        policy = policy,
        clock = clock,
    )

    private suspend fun startInitialLoad() = store.writeCursor(
        SyncCursor(heartRate.category).startingInitialLoad(WINDOW_END, Instant.parse("2026-08-11T00:00:00Z")),
    )

    /** A reading whose local start, at -03:00, is [localStart] on the 10th. */
    private fun at(uid: String, localStart: String) =
        sourceRecord(uid = uid, start = LocalDateTime.parse("2026-08-10T$localStart").toInstant(ZoneOffset.ofHours(-3)))

    @Test
    fun `declares each page over the stretch it closes, and the last one up to the end of the window`() = runTest {
        startInitialLoad()

        importer(
            FakeRecordSource(
                listOf(
                    page(listOf(at("a", "10:00"), at("b", "11:00")), nextPageToken = "next"),
                    page(listOf(at("c", "12:00"))),
                ),
            ),
        ).import(heartRate)

        assertEquals(
            listOf(
                inventory(HISTORY_FLOOR, "2026-08-10T11:00", "a", "b"),
                inventory("2026-08-10T11:00", WINDOW_END, "b", "c"),
            ),
            store.declared.map { it.inventory },
        )
    }

    /**
     * Records sharing a local time can span pages, so the stretch ending there is never declared by a
     * page that may not have seen all of them: the next page that moves past it declares them together.
     */
    @Test
    fun `never declares a stretch whose last local time a later page may still hold`() = runTest {
        startInitialLoad()

        importer(
            FakeRecordSource(
                listOf(
                    page(listOf(at("a", "10:00"), at("b", "10:00")), nextPageToken = "p2"),
                    page(listOf(at("c", "10:00")), nextPageToken = "p3"),
                    page(listOf(at("d", "11:00"))),
                ),
            ),
        ).import(heartRate)

        assertEquals(
            listOf(
                inventory(HISTORY_FLOOR, "2026-08-10T10:00", "a", "b"),
                inventory("2026-08-10T10:00", WINDOW_END, "a", "b", "c", "d"),
            ),
            store.declared.map { it.inventory },
        )
    }

    @Test
    fun `keeps what it read at the boundary across a pause, so the resumed page still declares it`() = runTest {
        startInitialLoad()
        val source = FakeRecordSource(
            listOf(
                page(listOf(at("a", "10:00"), at("b", "10:00")), nextPageToken = "p2"),
                page(listOf(at("c", "11:00"))),
            ),
        )
        val paused = importer(source, SyncPolicy(maxOutboxItems = 2)).import(heartRate) as ImportResult.Paused
        store.confirm(store.staged.map { it.id })

        importer(source, SyncPolicy(maxOutboxItems = 2)).import(heartRate, paused.pageToken)

        assertEquals(
            inventory("2026-08-10T10:00", WINDOW_END, "a", "b", "c"),
            store.declared.last().inventory,
        )
    }

    /** Nothing read over a stretch read in full is a declaration too: everything the API holds there is gone. */
    @Test
    fun `declares a window that holds nothing at all`() = runTest {
        startInitialLoad()

        importer(FakeRecordSource(listOf(page(emptyList())))).import(heartRate)

        assertEquals(listOf(inventory(HISTORY_FLOOR, WINDOW_END)), store.declared.map { it.inventory })
    }

    @Test
    fun `delivers an inventory only after the observations read with it, in a batch of its own`() = runTest {
        startInitialLoad()
        importer(FakeRecordSource(listOf(page(listOf(at("a", "10:00")))))).import(heartRate)
        val client = answering(InventoryStatus.CONCLUDED)

        val result = sender(client).drain(heartRate)

        assertSame(SendResult.Drained, result)
        assertEquals(listOf(listOf("a"), emptyList()), client.batches.map { batch -> batch.items.map { it.samsungUid } })
        assertEquals(listOf(null, inventory(HISTORY_FLOOR, WINDOW_END, "a")), client.batches.map { it.inventory })
        assertEquals(IngestionContract.INVENTORY_VERSION, client.batches.last().contractVersion)
        assertTrue(store.declared.isEmpty())
    }

    /**
     * A record the API rejected never reaches it, so an inventory naming it is answered as incomplete.
     * Sending it again would get the same answer forever; the next read of the stretch declares it anew.
     */
    @Test
    fun `drops an inventory the API could not conclude from instead of sending it again`() = runTest {
        startInitialLoad()
        importer(FakeRecordSource(listOf(page(listOf(at("a", "10:00")))))).import(heartRate)
        val client = FakeIngestionClient { batch ->
            if (batch.inventory != null) SendOutcome.Delivered(emptyList(), InventoryResult(InventoryStatus.INCOMPLETE))
            else SendOutcome.Delivered(listOf(ItemResult(0, ItemStatus.REJECTED, listOf(RejectionCode.INVALID_UNIT))))
        }

        val result = sender(client).drain(heartRate)

        assertSame(SendResult.Drained, result)
        assertTrue(store.declared.isEmpty())
    }

    @Test
    fun `keeps an inventory the API never answered`() = runTest {
        startInitialLoad()
        importer(FakeRecordSource(listOf(page(listOf(at("a", "10:00")))))).import(heartRate)
        val client = FakeIngestionClient { batch ->
            if (batch.inventory != null) SendOutcome.Unreachable
            else SendOutcome.Delivered(listOf(ItemResult(0, ItemStatus.ACCEPTED)))
        }

        val result = sender(client).drain(heartRate)

        assertEquals(SendResult.Halted(SyncOutcome.INGESTION_UNAVAILABLE), result)
        assertEquals(1, store.declared.size)
    }

    private fun sender(client: FakeIngestionClient) =
        OutboxSender(store, FakeEndpointStore(), client, maintenanceService(maintenance, clock), SyncPolicy())

    private fun answering(status: InventoryStatus) = FakeIngestionClient { batch ->
        SendOutcome.Delivered(
            batch.items.indices.map { ItemResult(it, ItemStatus.ACCEPTED) },
            batch.inventory?.let { InventoryResult(status) },
        )
    }

    private fun inventory(from: Any, until: Any, vararg uids: String) = RecordInventory(
        interval = LocalInterval(start = from.toString(), end = until.toString()),
        readAt = clock.instant().toString(),
        samsungUids = uids.toList(),
    )

    private companion object {
        val WINDOW_END: LocalDateTime = LocalDateTime.of(2026, 8, 11, 9, 0)
    }
}
