package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Sleep through the same pipeline heart rate and exercise proved, from Samsung Health to the ingestion
 * API: its own record type, its stages carried inside the envelope that holds them, and every path a
 * night can take — the paginated history, a run cut short, a reconciliation, and the changes feed with
 * its edits and Source Removals.
 */
class SleepSyncTest {

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val changedAt = Instant.parse("2026-08-11T11:00:00Z")

    private val staged = sleepRecord(
        uid = "uid-staged",
        sessions = listOf(
            sleepSession(stages = listOf(sleepStage(), sleepStage(start = "2026-08-10T02:40:00Z", stage = "DEEP"))),
        ),
    )

    private val unstaged = sleepRecord(
        uid = "uid-unstaged",
        start = Instant.parse("2026-08-10T15:00:00Z"),
        end = Instant.parse("2026-08-10T15:40:00Z"),
        duration = "2400.000",
        score = null,
        sessions = listOf(
            sleepSession(start = "2026-08-10T15:00:00Z", end = "2026-08-10T15:40:00Z", duration = "2400.000"),
        ),
    )

    private fun service(source: FakeRecordSource, client: FakeIngestionClient = answering(ItemStatus.ACCEPTED)): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(HealthCategory.SLEEP)),
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

    private fun answering(status: ItemStatus) = FakeIngestionClient { batch ->
        SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, status) })
    }

    private suspend fun cursor() = store.cursor(HealthCategory.SLEEP)!!

    private fun FakeIngestionClient.delivered(): List<HealthRecordEnvelope> = batches.flatMap { it.items }

    private fun HealthRecordEnvelope.stages() =
        (state as RecordState.Present).normalizedPayload.getValue("sleep").jsonObject
            .getValue("sessions").jsonArray.single().jsonObject["stages"]?.jsonArray

    /** A history already walked and delivered, which is the only state the changes feed is read in. */
    private suspend fun imported() {
        val service = service(FakeRecordSource(listOf(page(listOf(staged, unstaged)))))
        service.startInitialLoad()
        service.sync()
    }

    @Test
    fun `carries every page of the sleep history to the ingestion API, stages inside their night`() = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(
            FakeRecordSource(listOf(page(listOf(staged), nextPageToken = "next"), page(listOf(unstaged)))),
            client,
        )
        service.startInitialLoad()

        service.sync()

        assertEquals(setOf("sleep"), client.batches.map { it.recordType }.toSet())
        assertEquals(listOf("uid-staged", "uid-unstaged"), client.delivered().map { it.samsungUid })
        assertEquals(2, client.delivered().first().stages()!!.size)
        assertEquals(null, client.delivered().last().stages())
        assertTrue(store.staged.isEmpty())
        assertEquals(ImportPhase.INCREMENTAL, cursor().phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor().lastOutcome)
    }

    @Test
    fun `resumes a sleep history cut short from the last night it staged`() = runTest {
        service(
            FakeRecordSource(
                listOf(
                    page(listOf(staged), nextPageToken = "next"),
                    SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("timeout")),
                ),
            ),
        ).also { it.startInitialLoad() }.sync()
        assertEquals(ImportPhase.INITIAL_LOAD, cursor().phase)

        val source = FakeRecordSource(listOf(page(listOf(unstaged))))
        val client = answering(ItemStatus.ACCEPTED)
        service(source, client).sync()

        // The staged night began at 02:00 UTC, 23:00 of the day before at the offset it was recorded in.
        assertEquals(LocalDateTime.of(2026, 8, 9, 23, 0), source.windows.first().from)
        assertEquals(listOf("uid-staged", "uid-unstaged"), client.delivered().map { it.samsungUid })
        assertEquals(ImportPhase.INCREMENTAL, cursor().phase)
    }

    @Test
    fun `reconciles the sleep history with nothing left behind when the API already holds it`() = runTest {
        imported()

        val client = answering(ItemStatus.ALREADY_PRESENT)
        val service = service(FakeRecordSource(listOf(page(listOf(staged, unstaged)))), client)
        service.reconcile()
        service.sync()

        assertEquals(listOf("uid-staged", "uid-unstaged"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor().lastOutcome)
    }

    @Test
    fun `delivers an edited night and a removed one from the changes feed`() = runTest {
        imported()
        val restaged = sleepRecord(
            uid = "uid-staged",
            updateTime = changedAt,
            sessions = listOf(sleepSession(stages = listOf(sleepStage(stage = "REM")))),
        )

        val client = answering(ItemStatus.ACCEPTED)
        service(
            FakeRecordSource(
                pages = emptyList(),
                changePages = listOf(
                    changePage(
                        listOf(SourceChange.Upserted(changedAt, restaged), SourceChange.Removed(changedAt, "uid-unstaged")),
                    ),
                ),
            ),
            client,
        ).sync()

        val (edited, removed) = client.delivered()
        assertEquals("uid-staged", edited.samsungUid)
        assertEquals(changedAt.toString(), edited.observedAt.instant)
        assertEquals("REM", edited.stages()!!.single().jsonObject.getValue("stage").jsonPrimitive.content)
        assertEquals("uid-unstaged", removed.samsungUid)
        assertEquals(RecordState.Removed(period = null), removed.state)
        assertEquals(setOf("sleep"), client.batches.map { it.recordType }.toSet())
    }
}
