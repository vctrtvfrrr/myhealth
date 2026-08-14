package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
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
 * What the synchronization reports to the maintenance channel.
 *
 * Every condition here is one only a change to the code resolves; the ones that resolve themselves,
 * such as an outbox at its limit, are deliberately absent.
 */
class SyncMaintenanceTest {

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val endpoints = FakeEndpointStore()
    private val maintenance = FakeMaintenance()

    /** Answers what it is told to, so that a grant can be observed and then taken away. */
    private class MutableGateway(var granted: Set<HealthCategory>, var available: Boolean = true) :
        SamsungHealthGateway {

        var availability: SamsungHealthAvailability = SamsungHealthAvailability.TemporarilyUnavailable("fake")

        override suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>> =
            if (available) SamsungHealthOutcome.Observed(granted) else SamsungHealthOutcome.Failed(availability)

        override suspend fun requestReadPermissions(categories: Set<HealthCategory>) =
            SamsungHealthOutcome.Observed(Unit)
    }

    private val gateway = MutableGateway(setOf(HealthCategory.HEART_RATE))

    private val permissions = HealthPermissionsService(gateway, InMemoryPermissionHistory(), clock)

    private fun service(
        source: FakeRecordSource = FakeRecordSource(listOf(page(listOf(sourceRecord())))),
        client: FakeIngestionClient = FakeIngestionClient { batch ->
            SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.ACCEPTED) })
        },
    ): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = permissions,
            importer = HistoryImporter(source, store, channel, policy, clock),
            changes = ChangeImporter(source, store, channel, policy, clock),
            sender = OutboxSender(store, endpoints, client, channel, policy),
            store = store,
            maintenance = channel,
            policy = policy,
            clock = clock,
        )
    }

    private fun rejectingClient(vararg codes: RejectionCode) = FakeIngestionClient { batch ->
        SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.REJECTED, codes.toList()) })
    }

    @Test
    fun `reports a record the API could not turn into an Observed Record Version`() = runTest {
        val service = service(client = rejectingClient(RejectionCode.INVALID_PAYLOAD, RejectionCode.INVALID_UNIT))
        service.startInitialLoad()

        service.sync()

        assertEquals(
            listOf(
                IncidentIdentity(
                    code = MaintenanceCode.UNMAPPABLE_RECORD,
                    category = HealthCategory.HEART_RATE,
                    detail = "invalid_payload,invalid_unit",
                ),
            ),
            maintenance.reported,
        )
    }

    @Test
    fun `reports a contract neither side can agree on, without naming a category`() = runTest {
        val service = service(client = FakeIngestionClient { SendOutcome.Refused(BatchErrorCode.CONTRACT_VERSION_TOO_OLD) })
        service.startInitialLoad()

        service.sync()

        assertEquals(listOf(IncidentIdentity(MaintenanceCode.CONTRACT_INCOMPATIBLE)), maintenance.reported)
    }

    @Test
    fun `reports a read permission that was granted and then taken away`() = runTest {
        service().sync()
        gateway.granted = emptySet()

        service().sync()

        assertEquals(
            listOf(IncidentIdentity(MaintenanceCode.PERMISSION_REVOKED, HealthCategory.HEART_RATE)),
            maintenance.reported,
        )
    }

    /** A category nobody ever granted is waiting on the Data Owner, and the code has nothing to fix. */
    @Test
    fun `says nothing about a permission that was never granted`() = runTest {
        gateway.granted = emptySet()

        service().sync()

        assertTrue(maintenance.reported.isEmpty())
    }

    @Test
    fun `reports a stored position this build cannot interpret`() = runTest {
        store.writeCursor(SyncCursor(HealthCategory.HEART_RATE, unrecoverable = "unreadable_sync_cursor_row"))

        service().sync()

        assertEquals(
            listOf(IncidentIdentity(MaintenanceCode.UNRECOVERABLE_CURSOR, HealthCategory.HEART_RATE)),
            maintenance.reported,
        )
    }

    @Test
    fun `reports a Samsung Health this build cannot support`() = runTest {
        gateway.available = false
        gateway.availability = SamsungHealthAvailability.Unsupported("fake")

        service().sync()

        assertEquals(listOf(IncidentIdentity(MaintenanceCode.UNSUPPORTED_PLATFORM)), maintenance.reported)
        assertEquals(1, maintenance.posted.size, "a platform this build cannot serve is told at once")
    }

    @Test
    fun `holds a transient failure of the ingestion API instead of notifying it`() = runTest {
        val service = service(client = FakeIngestionClient { SendOutcome.Unreachable })
        service.startInitialLoad()

        service.sync()

        assertEquals(listOf(IncidentIdentity(MaintenanceCode.INGESTION_UNREACHABLE)), maintenance.reported)
        assertTrue(maintenance.posted.isEmpty(), "a day has not passed without a successful synchronization")
    }

    @Test
    fun `a synchronization that got through ends the transient incident`() = runTest {
        val failing = service(client = FakeIngestionClient { SendOutcome.Unreachable })
        failing.startInitialLoad()
        failing.sync()

        service().sync()

        assertTrue(maintenance.reported.isEmpty())
        assertEquals(
            listOf(IncidentIdentity(MaintenanceCode.INGESTION_UNREACHABLE)),
            maintenance.withdrawn,
        )
    }

    /**
     * The whole point of an incident naming only what the code defines: a diagnostic that quoted the
     * reading, the device it came from or the token that delivered it would be another copy of the
     * Personal Health History, kept where nothing protects it.
     */
    @Test
    fun `reports nothing that came from a Health Record`() = runTest {
        val service = service(client = rejectingClient(RejectionCode.INVALID_PAYLOAD))
        service.startInitialLoad()

        service.sync()

        val rendered = maintenance.reported.joinToString(" ") { it.key }
        listOf("72", "uid-1", "device-1", "com.example.shealth", "client-1", "token").forEach {
            assertFalse(rendered.contains(it), "the diagnostic quoted $it")
        }
    }
}
