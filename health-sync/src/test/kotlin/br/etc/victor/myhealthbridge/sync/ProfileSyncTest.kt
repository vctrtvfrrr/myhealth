package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The profile from Samsung Health to the ingestion API as the one Observed Snapshot without a Snapshot
 * Window: read whole on every run, through the same Outbox and Sync Cursor as every other category, and
 * left to the API to keep only when it changed. See ADR 0025.
 */
class ProfileSyncTest {

    /** 09:20 where the phone is, three hours behind UTC. */
    private val now = Instant.parse("2026-08-11T12:20:00Z")
    private val zone = ZoneOffset.ofHours(-3)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private fun service(
        source: FakeRecordSource,
        client: FakeIngestionClient = FakeIngestionClient { error("no Health Record is read here") },
        clock: Clock = Clock.fixed(now, zone),
        granted: Set<HealthCategory> = setOf(HealthCategory.USER_PROFILE),
        policy: SyncPolicy = SyncPolicy(),
    ): SyncService {
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(granted),
                store = InMemoryPermissionHistory(),
                clock = clock,
            ),
            importer = HistoryImporter(source, store, channel, policy, clock),
            changes = ChangeImporter(source, store, channel, policy, clock),
            snapshots = SnapshotImporter(source, store, policy, clock),
            sender = OutboxSender(store, FakeEndpointStore(), client, channel, policy),
            store = store,
            maintenance = channel,
            policy = policy,
            clock = clock,
            uncataloged = nothingUncataloged,
        )
    }

    private fun holding(profile: Map<String, SourceValue>?) = FakeRecordSource(
        pages = emptyList(),
        profile = { SamsungHealthOutcome.Observed(profile) },
    )

    private suspend fun incremental() = store.writeCursor(
        SyncCursor(category = HealthCategory.USER_PROFILE, phase = ImportPhase.INCREMENTAL, lastOverlapAt = now),
    )

    private suspend fun cursor() = store.cursor(HealthCategory.USER_PROFILE)!!

    private fun FakeIngestionClient.observed(): List<SnapshotObservation> = snapshotBatches.flatMap { it.items }

    @Test
    fun `sends the whole profile as one observation without a window`() = runTest {
        incremental()
        val client = FakeIngestionClient { error("no Health Record is read here") }

        service(holding(syntheticProfile()), client).sync()

        val batch = client.snapshotBatches.single()
        assertEquals("user_profile", batch.snapshotType)
        val observation = batch.items.single()
        assertNull(observation.window)
        assertEquals(ZonedInstant("2026-08-11T12:20:00Z", "-03:00"), observation.observedAt)
        assertEquals(SourceScope.ALL_SOURCES, observation.sourceScope)
        assertEquals("Synthetic", observation.content!!["nickname"]!!.jsonPrimitive.content)
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor().lastOutcome)
    }

    /** The source offers no past and no changes feed, so the profile is read again on every run. */
    @Test
    fun `reads the profile again on every run, leaving to the api whether it changed`() = runTest {
        incremental()
        val source = holding(syntheticProfile())
        val client = FakeIngestionClient { error("no Health Record is read here") }

        service(source, client).sync()
        service(source, client, clock = Clock.fixed(now.plusSeconds(3600), zone)).sync()

        assertEquals(2, source.profileReads)
        assertEquals(
            listOf("2026-08-11T12:20:00Z", "2026-08-11T13:20:00Z"),
            client.observed().map { it.observedAt.instant },
        )
    }

    /** No profile is what the source currently says, and only an empty observation stops the last one being current. */
    @Test
    fun `sends a missing profile as an empty observation`() = runTest {
        incremental()
        val client = FakeIngestionClient { error("no Health Record is read here") }

        service(holding(profile = null), client).sync()

        assertNull(client.observed().single().content)
    }

    /** There is no history to walk: the first read is where the profile's history begins. */
    @Test
    fun `completes the initial load of the profile with its first read`() = runTest {
        val source = holding(syntheticProfile())
        val client = FakeIngestionClient { error("no Health Record is read here") }

        service(source, client).sync(SyncRun.INITIAL_LOAD)

        assertEquals(1, client.observed().size)
        assertEquals(ImportPhase.INCREMENTAL, cursor().phase)
    }

    @Test
    fun `reads no profile before the import was started`() = runTest {
        val source = holding(syntheticProfile())

        service(source).sync()

        assertEquals(0, source.profileReads)
        assertEquals(ImportPhase.NOT_STARTED, cursor().phase)
    }

    @Test
    fun `reads no profile without its read permission`() = runTest {
        incremental()
        val source = holding(syntheticProfile())

        service(source, granted = emptySet()).sync()

        assertEquals(0, source.profileReads)
        assertEquals(SyncOutcome.WAITING_PERMISSION, cursor().lastOutcome)
    }

    @Test
    fun `stages nothing when Samsung Health cannot read the profile`() = runTest {
        incremental()
        val failing = FakeRecordSource(
            pages = emptyList(),
            profile = { SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("busy")) },
        )

        service(failing).sync()

        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SAMSUNG_UNAVAILABLE, cursor().lastOutcome)
    }

    @Test
    fun `waits for room in the outbox before reading the profile`() = runTest {
        incremental()
        store.acceptPage(listOf(foreignItem("2026-08-11T08:00:00")), cursor())
        val source = holding(syntheticProfile())

        service(source, policy = SyncPolicy(maxOutboxItems = 1)).sync()

        assertEquals(0, source.profileReads)
        assertEquals(SyncOutcome.OUTBOX_FULL, cursor().lastOutcome)
    }

    @Test
    fun `starts the initial load of the profile with every other granted category`() = runTest {
        service(holding(syntheticProfile())).startInitialLoad()

        assertEquals(ImportPhase.INITIAL_LOAD, cursor().phase)
        assertEquals(LocalDateTime.parse("2026-08-11T09:20:00"), cursor().initialLoad!!.end)
    }
}
