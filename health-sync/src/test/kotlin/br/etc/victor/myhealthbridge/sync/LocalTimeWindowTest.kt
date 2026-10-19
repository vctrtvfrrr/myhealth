package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The app runs the service on UTC and reads the history on the phone's local time, which is the time
 * Samsung Health filters on. A phone behind UTC must not see a window that ends before it starts.
 */
class LocalTimeWindowTest {

    private val now = Instant.parse("2026-10-19T20:35:00Z")
    private val utc = Clock.fixed(now, ZoneOffset.UTC)
    private val local = Clock.fixed(now, ZoneOffset.ofHours(-3))

    private val store = FakeSyncStore()
    private val source = FakeRecordSource(listOf(page(emptyList())))

    private fun service(): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(FakeMaintenance(), utc)
        val client = FakeIngestionClient { batch ->
            SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.ACCEPTED) })
        }
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(HealthCategory.HEART_RATE)),
                store = InMemoryPermissionHistory(),
                clock = utc,
            ),
            importer = HistoryImporter(source, store, channel, policy, local),
            changes = ChangeImporter(source, store, channel, policy, utc),
            snapshots = SnapshotImporter(source, store, policy, local),
            sender = OutboxSender(store, FakeEndpointStore(), client, channel, policy),
            store = store,
            maintenance = channel,
            policy = policy,
            clock = utc,
            uncataloged = nothingUncataloged,
        )
    }

    @Test
    fun `the incremental read after an initial load never ends before it starts`() = runTest {
        val service = service()

        service.startInitialLoad()
        service.sync()
        service.sync()

        val inverted = source.windows.filter { it.from.isAfter(it.to) }
        assertTrue(inverted.isEmpty(), "windows that end before they start: $inverted")
    }

    @Test
    fun `the incremental read after a reconciliation never ends before it starts`() = runTest {
        val service = service()

        service.reconcile()
        service.sync()
        service.sync()

        val inverted = source.windows.filter { it.from.isAfter(it.to) }
        assertTrue(inverted.isEmpty(), "windows that end before they start: $inverted")
    }

    @Test
    fun `the initial load closes at the phone's local time`() = runTest {
        service().startInitialLoad()

        val initialLoad = store.cursor(HealthCategory.HEART_RATE)!!.initialLoad!!

        assertEquals(LocalDateTime.parse("2026-10-19T17:35:00"), initialLoad.end)
    }
}
