package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The activity summary from Samsung Health to the ingestion API as Observed Snapshots of a local hour,
 * on the same terms as the steps: from the history floor, every run re-reading from the last closed hour,
 * the overlap re-reading a week, and an hour without activity sent as an empty observation. See ADR 0025.
 */
class ActivitySummarySyncTest {

    /** 09:20 where the phone is, three hours behind UTC. */
    private val now = Instant.parse("2026-08-11T12:20:00Z")
    private val zone = ZoneOffset.ofHours(-3)
    private val clock = Clock.fixed(now, zone)
    private val store = FakeSyncStore()

    private val activitySummary = HealthCapabilities.of(HealthCategory.ACTIVITY_SUMMARY) as SnapshotCapability

    private fun service(source: FakeRecordSource, client: FakeIngestionClient): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(FakeMaintenance(), clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(HealthCategory.ACTIVITY_SUMMARY)),
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

    private fun active(vararg starts: String) = FakeRecordSource(
        pages = emptyList(),
        aggregates = { _, _ ->
            SamsungHealthOutcome.Observed(starts.map { SourceAggregate(LocalDateTime.parse(it), activity()) })
        },
    )

    private suspend fun incrementalFrom(readFrom: String, lastOverlapAt: Instant = now) = store.writeCursor(
        SyncCursor(
            category = HealthCategory.ACTIVITY_SUMMARY,
            phase = ImportPhase.INCREMENTAL,
            readFrom = LocalDateTime.parse(readFrom),
            lastOverlapAt = lastOverlapAt,
        ),
    )

    @Test
    fun `sends every hour from the last closed one up to now, an hour without activity as an empty observation`() =
        runTest {
            incrementalFrom("2026-08-11T07:00:00")
            val source = active("2026-08-11T08:00:00")
            val client = FakeIngestionClient { error("no Health Record is read here") }

            service(source, client).sync()

            assertEquals(
                listOf(AggregateWindow(LocalDateTime.parse("2026-08-11T07:00:00"), LocalDateTime.parse("2026-08-11T10:00:00"))),
                source.aggregateWindows,
            )
            val batch = client.snapshotBatches.single()
            assertEquals("activity_summary", batch.snapshotType)
            assertEquals(
                listOf("2026-08-11T07:00:00", "2026-08-11T08:00:00", "2026-08-11T09:00:00"),
                batch.items.map { it.window!!.start },
            )
            assertEquals(
                listOf(null, "1310.7", null),
                batch.items.map { it.content?.get("distance")?.jsonObject?.get("value")?.jsonPrimitive?.content },
            )
            assertEquals(setOf(SourceScope.PLATFORM), batch.items.map { it.sourceScope }.toSet())
            assertEquals(LocalDateTime.parse("2026-08-11T09:00:00"), store.cursor(HealthCategory.ACTIVITY_SUMMARY)!!.readFrom)
        }

    @Test
    fun `re-reads the last seven days in the daily overlap`() = runTest {
        incrementalFrom("2026-08-11T09:00:00", lastOverlapAt = now.minus(Duration.ofDays(2)))
        val source = active()

        service(source, FakeIngestionClient { error("no Health Record is read here") }).sync()

        assertEquals(LocalDateTime.parse("2026-08-04T09:00:00"), source.aggregateWindows.first().from)
    }

    @Test
    fun `walks the accessible history from the floor one month of hours per read`() = runTest {
        store.writeCursor(SyncCursor(HealthCategory.ACTIVITY_SUMMARY).startingInitialLoad(LocalDateTime.now(clock), now))
        val source = active()

        SnapshotImporter(source, store, SyncPolicy(maxOutboxItems = 1), clock).import(activitySummary)

        assertEquals(listOf(AggregateWindow(HISTORY_FLOOR, HISTORY_FLOOR.plusDays(31))), source.aggregateWindows)
        assertEquals(31 * 24, store.staged.size)
    }
}
