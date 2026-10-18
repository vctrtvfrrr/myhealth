package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Steps from Samsung Health to the ingestion API as Observed Snapshots: read by local hour through the
 * same Outbox and Sync Cursor the Health Records use, every hour without an aggregate sent as an empty
 * observation, and every run re-reading from the last closed hour. See ADR 0025.
 */
class StepsSyncTest {

    /** 09:20 where the phone is, three hours behind UTC. */
    private val now = Instant.parse("2026-08-11T12:20:00Z")
    private val zone = ZoneOffset.ofHours(-3)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val steps = HealthCapabilities.of(HealthCategory.STEPS) as SnapshotCapability

    private fun service(
        source: FakeRecordSource,
        client: FakeIngestionClient = FakeIngestionClient { error("no Health Record is read here") },
        clock: Clock = Clock.fixed(now, zone),
        granted: Set<HealthCategory> = setOf(HealthCategory.STEPS),
    ): SyncService {
        val policy = SyncPolicy()
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
        )
    }

    private fun counting(vararg counts: Pair<String, String>) = FakeRecordSource(
        pages = emptyList(),
        aggregates = { _, _ ->
            SamsungHealthOutcome.Observed(counts.map { (start, total) -> SourceAggregate(LocalDateTime.parse(start), number(total)) })
        },
    )

    private suspend fun incrementalFrom(readFrom: String, lastOverlapAt: Instant = now) = store.writeCursor(
        SyncCursor(
            category = HealthCategory.STEPS,
            phase = ImportPhase.INCREMENTAL,
            readFrom = LocalDateTime.parse(readFrom),
            lastOverlapAt = lastOverlapAt,
        ),
    )

    private suspend fun cursor() = store.cursor(HealthCategory.STEPS)!!

    private fun FakeIngestionClient.observed(): List<SnapshotObservation> = snapshotBatches.flatMap { it.items }

    private fun SnapshotObservation.steps(): String? = content?.get("steps")?.jsonPrimitive?.content

    @Test
    fun `sends every hour from the last closed one up to now, an hour without steps as an empty observation`() =
        runTest {
            incrementalFrom("2026-08-11T06:00:00")
            val source = counting("2026-08-11T06:00:00" to "300", "2026-08-11T08:00:00" to "1200")
            val client = FakeIngestionClient { error("no Health Record is read here") }

            service(source, client).sync()

            assertEquals(
                listOf(AggregateWindow(LocalDateTime.parse("2026-08-11T06:00:00"), LocalDateTime.parse("2026-08-11T10:00:00"))),
                source.aggregateWindows,
            )
            val batch = client.snapshotBatches.single()
            assertEquals("steps", batch.snapshotType)
            assertEquals(3, batch.contractVersion)
            assertEquals(
                listOf("2026-08-11T06:00:00", "2026-08-11T07:00:00", "2026-08-11T08:00:00", "2026-08-11T09:00:00"),
                batch.items.map { it.window!!.start },
            )
            assertEquals(listOf("300", null, "1200", null), batch.items.map { it.steps() })
            assertEquals(setOf(ZonedInstant("2026-08-11T12:20:00Z", "-03:00")), batch.items.map { it.observedAt }.toSet())
            assertTrue(store.staged.isEmpty())
            assertEquals(SyncOutcome.SUCCEEDED, cursor().lastOutcome)
        }

    /** The hour still open when it was read is the last one closed by the next run, so it is read again. */
    @Test
    fun `starts the next run at the hour that was still open`() = runTest {
        incrementalFrom("2026-08-11T06:00:00")
        service(counting()).sync()
        assertEquals(LocalDateTime.parse("2026-08-11T09:00:00"), cursor().readFrom)

        val later = counting()
        service(later, clock = Clock.fixed(Instant.parse("2026-08-11T14:05:00Z"), zone)).sync()

        assertEquals(
            listOf(AggregateWindow(LocalDateTime.parse("2026-08-11T09:00:00"), LocalDateTime.parse("2026-08-11T12:00:00"))),
            later.aggregateWindows,
        )
    }

    @Test
    fun `re-reads the last seven days in the daily overlap`() = runTest {
        incrementalFrom("2026-08-11T09:00:00", lastOverlapAt = now.minus(Duration.ofDays(2)))
        val source = counting()

        service(source).sync()

        assertEquals(LocalDateTime.parse("2026-08-04T09:00:00"), source.aggregateWindows.first().from)
        assertEquals(now, cursor().lastOverlapAt)
    }

    /**
     * The initial load starts at the history floor every Health Record starts at, the start of 2025 when
     * the Data Owner's history begins, one month of hours per read, and stops reading while the outbox is
     * full, exactly as a history walk does.
     */
    @Test
    fun `walks the accessible history from the floor one month of hours per read`() = runTest {
        val clock = Clock.fixed(now, zone)
        store.writeCursor(SyncCursor(HealthCategory.STEPS).startingInitialLoad(LocalDateTime.now(clock), now))
        val source = counting("2025-01-01T05:00:00" to "42")
        val importer = SnapshotImporter(source, store, SyncPolicy(maxOutboxItems = 1), clock)

        val result = importer.import(steps)

        val floor = LocalDateTime.parse("2025-01-01T00:00:00")
        assertEquals(listOf(AggregateWindow(floor, floor.plusDays(31))), source.aggregateWindows)
        assertEquals(ImportResult.Paused(pageToken = null, staged = 31 * 24), result)
        assertEquals(31 * 24, store.staged.size)
        assertEquals(floor.plusDays(31), cursor().readFrom)
        assertEquals(ImportPhase.INITIAL_LOAD, cursor().phase)
    }

    @Test
    fun `becomes incremental at the hour that was open when the initial load ends`() = runTest {
        val clock = Clock.fixed(now, zone)
        store.writeCursor(
            SyncCursor(HealthCategory.STEPS).startingInitialLoad(LocalDateTime.now(clock), now)
                .copy(readFrom = LocalDateTime.parse("2026-08-11T07:00:00")),
        )

        val result = SnapshotImporter(counting(), store, SyncPolicy(), clock).import(steps)

        assertEquals(ImportResult.Completed, result)
        assertEquals(ImportPhase.INCREMENTAL, cursor().phase)
        assertEquals(LocalDateTime.parse("2026-08-11T09:00:00"), cursor().readFrom)
    }

    @Test
    fun `keeps a step snapshot the api rejects as a mapping pendency of the steps`() = runTest {
        incrementalFrom("2026-08-11T09:00:00")
        val client = FakeIngestionClient(
            answer = { error("no Health Record is read here") },
            snapshotAnswer = { batch ->
                SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, ItemStatus.REJECTED, listOf(RejectionCode.INVALID_PAYLOAD)) })
            },
        )

        service(counting("2026-08-11T09:00:00" to "10"), client).sync()

        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), store.staged.single().codes)
        assertTrue(maintenance.reported.any { it.code == MaintenanceCode.UNMAPPABLE_RECORD && it.category == HealthCategory.STEPS })
    }

    @Test
    fun `leaves the cursor where it was when Samsung Health cannot aggregate`() = runTest {
        incrementalFrom("2026-08-11T06:00:00")
        val failing = FakeRecordSource(
            pages = emptyList(),
            aggregates = { _, _ -> SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("busy")) },
        )

        service(failing).sync()

        assertEquals(LocalDateTime.parse("2026-08-11T06:00:00"), cursor().readFrom)
        assertEquals(SyncOutcome.SAMSUNG_UNAVAILABLE, cursor().lastOutcome)
    }

    @Test
    fun `reads no steps without their read permission`() = runTest {
        incrementalFrom("2026-08-11T06:00:00")
        val source = counting()

        service(source, granted = emptySet()).sync()

        assertTrue(source.aggregateWindows.isEmpty())
        assertEquals(SyncOutcome.WAITING_PERMISSION, cursor().lastOutcome)
    }

    @Test
    fun `starts the initial load of the steps with every other granted category`() = runTest {
        service(counting(), granted = setOf(HealthCategory.STEPS)).startInitialLoad()

        assertEquals(ImportPhase.INITIAL_LOAD, cursor().phase)
        assertEquals(HISTORY_FLOOR, cursor().readFrom)
    }
}
