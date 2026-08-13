package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SyncServiceTest {

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val endpoints = FakeEndpointStore()

    private fun service(
        granted: Set<HealthCategory> = setOf(HealthCategory.HEART_RATE),
        available: Boolean = true,
        source: FakeRecordSource = FakeRecordSource(listOf(page(listOf(sourceRecord())))),
        client: FakeIngestionClient = acceptingClient(),
    ): SyncService {
        val policy = SyncPolicy()
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(granted, available),
                store = InMemoryPermissionHistory(),
                clock = clock,
            ),
            importer = HistoryImporter(source, store, policy, clock),
            sender = OutboxSender(store, endpoints, client, policy),
            store = store,
            clock = clock,
        )
    }

    private fun acceptingClient() = FakeIngestionClient { batch ->
        SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.ACCEPTED) })
    }

    private suspend fun cursor() = store.cursor(HealthCategory.HEART_RATE)

    @Test
    fun `starts the initial load only where the read permission is granted`() = runTest {
        service(granted = emptySet()).startInitialLoad()
        assertNull(cursor()?.initialLoad)

        service().startInitialLoad()
        assertEquals(ImportPhase.INITIAL_LOAD, cursor()!!.phase)
    }

    @Test
    fun `does not restart an import that is already walking the history`() = runTest {
        val service = service()
        service.startInitialLoad()
        val started = cursor()!!.initialLoad

        service.startInitialLoad()

        assertEquals(started, cursor()!!.initialLoad)
    }

    @Test
    fun `carries a record from Samsung Health to the ingestion API`() = runTest {
        val client = acceptingClient()
        val service = service(client = client)
        service.startInitialLoad()

        service.sync()

        assertEquals(listOf("uid-1"), client.batches.single().items.map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor()!!.lastOutcome)
        assertEquals(now, cursor()!!.lastSuccessAt)
        assertEquals(now, cursor()!!.lastAttemptAt)
    }

    @Test
    fun `records the attempt without a success when the API cannot be reached`() = runTest {
        val service = service(client = FakeIngestionClient { SendOutcome.Unreachable })
        service.startInitialLoad()

        service.sync()

        assertEquals(SyncOutcome.INGESTION_UNAVAILABLE, cursor()!!.lastOutcome)
        assertEquals(now, cursor()!!.lastAttemptAt)
        assertNull(cursor()!!.lastSuccessAt)
        assertEquals(1, store.staged.size)
    }

    /**
     * The outbox bounds the device, not one category, so it can be full of a record type this
     * capability never drains. Without the guard the run would drain nothing, pause, and repeat.
     */
    @Test
    fun `ends the run when the outbox is full of a record type it cannot deliver`() = runTest {
        val service = service(source = FakeRecordSource(listOf(page(listOf(sourceRecord())))))
        service.startInitialLoad()
        store.acceptPage(
            List(SyncPolicy().maxOutboxItems) { foreignItem("other-$it") },
            store.cursor(HealthCategory.HEART_RATE)!!,
        )

        service.sync()

        assertEquals(SyncOutcome.OUTBOX_FULL, cursor()!!.lastOutcome)
    }

    @Test
    fun `waits for the read permission instead of reading`() = runTest {
        val source = FakeRecordSource(listOf(page(listOf(sourceRecord()))))

        service(granted = emptySet(), source = source).sync()

        assertEquals(SyncOutcome.WAITING_PERMISSION, cursor()!!.lastOutcome)
        assertTrue(source.windows.isEmpty())
    }

    @Test
    fun `records that Samsung Health did not answer`() = runTest {
        service(available = false).sync()

        assertEquals(SyncOutcome.SAMSUNG_UNAVAILABLE, cursor()!!.lastOutcome)
    }
}
