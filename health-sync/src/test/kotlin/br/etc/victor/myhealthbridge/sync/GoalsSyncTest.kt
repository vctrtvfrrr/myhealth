package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Goals from Samsung Health to the ingestion API as Observed Snapshots of a local day: the goal in force
 * on each day, read back to the history floor and re-read from the last closed day on every run, a day
 * without a goal sent as an empty observation. See ADR 0025.
 */
class GoalsSyncTest {

    /** 09:20 where the phone is, three hours behind UTC. */
    private val now = Instant.parse("2026-08-11T12:20:00Z")
    private val zone = ZoneOffset.ofHours(-3)
    private val store = FakeSyncStore()

    private val stepsGoal = HealthCapabilities.of(HealthCategory.STEPS_GOAL) as SnapshotCapability

    private fun service(source: FakeRecordSource, client: FakeIngestionClient = noRecords()): SyncService {
        val clock = Clock.fixed(now, zone)
        val policy = SyncPolicy()
        val channel = maintenanceService(FakeMaintenance(), clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(HealthCategory.STEPS_GOAL)),
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

    private fun noRecords() = FakeIngestionClient { error("no Health Record is read here") }

    private fun goals(vararg goals: Pair<String, String>) = FakeRecordSource(
        pages = emptyList(),
        aggregates = { _, _ ->
            SamsungHealthOutcome.Observed(goals.map { (day, goal) -> SourceAggregate(LocalDateTime.parse(day), number(goal)) })
        },
    )

    private suspend fun incrementalFrom(readFrom: String, lastOverlapAt: Instant = now) = store.writeCursor(
        SyncCursor(
            category = HealthCategory.STEPS_GOAL,
            phase = ImportPhase.INCREMENTAL,
            readFrom = LocalDateTime.parse(readFrom),
            lastOverlapAt = lastOverlapAt,
        ),
    )

    private suspend fun cursor() = store.cursor(HealthCategory.STEPS_GOAL)!!

    private fun SnapshotObservation.steps(): String? = content?.get("steps")?.jsonPrimitive?.content

    @Test
    fun `sends every day from the last closed one up to today, a day without a goal as an empty observation`() =
        runTest {
            incrementalFrom("2026-08-08T00:00:00")
            val source = goals("2026-08-08T00:00:00" to "6000", "2026-08-10T00:00:00" to "8000")
            val client = noRecords()

            service(source, client).sync()

            assertEquals(
                listOf(AggregateWindow(LocalDateTime.parse("2026-08-08T00:00:00"), LocalDateTime.parse("2026-08-12T00:00:00"))),
                source.aggregateWindows,
            )
            val batch = client.snapshotBatches.single()
            assertEquals("steps_goal", batch.snapshotType)
            assertEquals(
                listOf("2026-08-08T00:00:00", "2026-08-09T00:00:00", "2026-08-10T00:00:00", "2026-08-11T00:00:00"),
                batch.items.map { it.window!!.start },
            )
            assertEquals(listOf("6000", null, "8000", null), batch.items.map { it.steps() })
            assertEquals(LocalDateTime.parse("2026-08-11T00:00:00"), cursor().readFrom, "today is read again tomorrow")
        }

    @Test
    fun `re-reads the last seven days in the daily overlap`() = runTest {
        incrementalFrom("2026-08-11T00:00:00", lastOverlapAt = now.minus(Duration.ofDays(2)))
        val source = goals()

        service(source).sync()

        assertEquals(LocalDateTime.parse("2026-08-04T00:00:00"), source.aggregateWindows.first().from)
    }

    /** The goal in force on each past day is recoverable, so the history of goals starts at the floor. */
    @Test
    fun `walks the accessible history from the floor one year of days per read`() = runTest {
        val clock = Clock.fixed(now, zone)
        store.writeCursor(SyncCursor(HealthCategory.STEPS_GOAL).startingInitialLoad(LocalDateTime.now(clock), now))
        val source = goals("2025-01-01T00:00:00" to "5000")

        val result = SnapshotImporter(source, store, SyncPolicy(), clock).import(stepsGoal)

        assertEquals(ImportResult.Completed, result)
        assertEquals(
            listOf(
                AggregateWindow(HISTORY_FLOOR, LocalDateTime.parse("2026-01-02T00:00:00")),
                AggregateWindow(LocalDateTime.parse("2026-01-02T00:00:00"), LocalDateTime.parse("2026-08-12T00:00:00")),
            ),
            source.aggregateWindows,
        )
        assertEquals(588, store.staged.size, "one observation per day from 2025-01-01 to 2026-08-11")
        assertEquals(LocalDateTime.parse("2026-08-11T00:00:00"), cursor().readFrom)
    }
}
