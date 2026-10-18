package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthGateway
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.maintenance.IncidentIdentity
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * A device token the API refuses does not resolve itself, unlike an endpoint that was never
 * configured: it is told apart from that and reported until a delivery is accepted again.
 */
class RefusedDeviceTokenTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val gateway = object : SamsungHealthGateway {
        override suspend fun grantedReadCategories() =
            SamsungHealthOutcome.Observed(setOf(HealthCategory.HEART_RATE))

        override suspend fun requestReadPermissions(categories: Set<HealthCategory>) =
            SamsungHealthOutcome.Observed(Unit)
    }

    private val source = FakeRecordSource(listOf(page(listOf(sourceRecord()))))

    private var refusing = true

    private val client = FakeIngestionClient { batch ->
        if (refusing) {
            SendOutcome.Refused(BatchErrorCode.INVALID_DEVICE_TOKEN)
        } else {
            SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.ACCEPTED) })
        }
    }

    private fun service(endpoints: IngestionEndpointStore = FakeEndpointStore()): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(gateway, InMemoryPermissionHistory(), clock),
            importer = HistoryImporter(source, store, channel, policy, clock),
            changes = ChangeImporter(source, store, channel, policy, clock),
            snapshots = SnapshotImporter(source, store, policy, clock),
            sender = OutboxSender(store, endpoints, client, channel, policy),
            store = store,
            maintenance = channel,
            policy = policy,
            clock = clock,
        )
    }

    @Test
    fun `records a refused token as its own outcome, apart from a missing endpoint`() = runTest {
        service().sync(SyncRun.INITIAL_LOAD)

        assertEquals(SyncOutcome.DEVICE_TOKEN_REFUSED, store.cursor(HealthCategory.HEART_RATE)!!.lastOutcome)
    }

    @Test
    fun `reports a refused token as a Maintenance Incident told at once, without the token`() = runTest {
        service(FakeEndpointStore(IngestionEndpoint("https://api.invalid", "secret-token"))).sync(SyncRun.INITIAL_LOAD)

        assertEquals(listOf(IncidentIdentity(MaintenanceCode.DEVICE_TOKEN_REFUSED)), maintenance.reported)
        assertEquals(1, maintenance.posted.size, "a refused token does not pass on its own, so it is not held as transient")
        val rendered = maintenance.reported.joinToString(" ") { it.key }
        assertFalse(rendered.contains("secret-token"), "the incident quoted the token")
    }

    @Test
    fun `ends the incident once a delivery is accepted`() = runTest {
        service().sync(SyncRun.INITIAL_LOAD)
        refusing = false

        service().sync(SyncRun.INCREMENTAL)

        assertTrue(maintenance.reported.isEmpty())
        assertEquals(listOf(IncidentIdentity(MaintenanceCode.DEVICE_TOKEN_REFUSED)), maintenance.withdrawn)
    }

    /** An endpoint never configured is waiting on the Data Owner, not on the code. */
    @Test
    fun `raises no incident while the endpoint is not configured`() = runTest {
        service(FakeEndpointStore(endpoint = null)).sync(SyncRun.INITIAL_LOAD)

        assertEquals(SyncOutcome.NOT_CONFIGURED, store.cursor(HealthCategory.HEART_RATE)!!.lastOutcome)
        assertTrue(maintenance.reported.isEmpty())
        assertTrue(maintenance.posted.isEmpty())
    }
}
