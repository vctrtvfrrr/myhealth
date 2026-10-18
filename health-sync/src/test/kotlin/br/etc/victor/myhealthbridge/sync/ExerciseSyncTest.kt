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
 * Exercises through the same pipeline heart rate proved, from Samsung Health to the ingestion API.
 *
 * The pipeline is written once for every capability, so what this guards is that exercise is actually
 * walked by it: its own record type, its route carried inside the envelope that holds it, and every
 * path an exercise can take — the paginated history, a run cut short, a reconciliation, and the
 * changes feed with its edits and Source Removals.
 */
class ExerciseSyncTest {

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val changedAt = Instant.parse("2026-08-11T11:00:00Z")

    private val routed = exerciseRecord(
        uid = "uid-routed",
        sessions = listOf(session(route = listOf(location(), location(at = "2026-08-10T09:15:00Z")))),
    )

    private val indoor = exerciseRecord(
        uid = "uid-indoor",
        start = Instant.parse("2026-08-10T18:00:00Z"),
        end = Instant.parse("2026-08-10T18:45:00Z"),
        exerciseType = "TREADMILL",
    )

    private fun service(source: FakeRecordSource, client: FakeIngestionClient = answering(ItemStatus.ACCEPTED)): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(HealthCategory.EXERCISE)),
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

    private fun answering(status: ItemStatus) = FakeIngestionClient { batch ->
        SendOutcome.Delivered(batch.items.indices.map { ItemResult(it, status) })
    }

    private suspend fun cursor() = store.cursor(HealthCategory.EXERCISE)!!

    private fun FakeIngestionClient.delivered(): List<HealthRecordEnvelope> = batches.flatMap { it.items }

    private fun HealthRecordEnvelope.route() =
        (state as RecordState.Present).normalizedPayload.getValue("exercise").jsonObject["route"]?.jsonArray

    /** A history already walked and delivered, which is the only state the changes feed is read in. */
    private suspend fun imported() {
        val service = service(FakeRecordSource(listOf(page(listOf(routed, indoor)))))
        service.startInitialLoad()
        service.sync()
    }

    @Test
    fun `carries every page of the exercise history to the ingestion API, route inside its exercise`() = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(
            FakeRecordSource(listOf(page(listOf(routed), nextPageToken = "next"), page(listOf(indoor)))),
            client,
        )
        service.startInitialLoad()

        service.sync()

        assertEquals(setOf("exercise"), client.batches.map { it.recordType }.toSet())
        assertEquals(listOf("uid-routed", "uid-indoor"), client.delivered().map { it.samsungUid })
        assertEquals(2, client.delivered().first().route()!!.size)
        assertEquals(null, client.delivered().last().route())
        assertTrue(store.staged.isEmpty())
        assertEquals(ImportPhase.INCREMENTAL, cursor().phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor().lastOutcome)
    }

    @Test
    fun `resumes an exercise history cut short from the last exercise it staged`() = runTest {
        service(
            FakeRecordSource(
                listOf(
                    page(listOf(routed), nextPageToken = "next"),
                    SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("timeout")),
                ),
            ),
        ).also { it.startInitialLoad() }.sync()
        assertEquals(ImportPhase.INITIAL_LOAD, cursor().phase)

        val source = FakeRecordSource(listOf(page(listOf(indoor))))
        val client = answering(ItemStatus.ACCEPTED)
        service(source, client).sync()

        // The routed exercise started at 09:00 UTC, 06:00 at the offset it was recorded in.
        assertEquals(LocalDateTime.of(2026, 8, 10, 6, 0), source.windows.first().from)
        assertEquals(listOf("uid-routed", "uid-indoor"), client.delivered().map { it.samsungUid })
        assertEquals(ImportPhase.INCREMENTAL, cursor().phase)
    }

    @Test
    fun `reconciles the exercise history with nothing left behind when the API already holds it`() = runTest {
        imported()

        val client = answering(ItemStatus.ALREADY_PRESENT)
        val service = service(FakeRecordSource(listOf(page(listOf(routed, indoor)))), client)
        service.reconcile()
        service.sync()

        assertEquals(listOf("uid-routed", "uid-indoor"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor().lastOutcome)
    }

    @Test
    fun `delivers an edited exercise and a removed one from the changes feed`() = runTest {
        imported()
        val rerouted = exerciseRecord(
            uid = "uid-routed",
            updateTime = changedAt,
            sessions = listOf(session(route = listOf(location(latitude = "-22.9", longitude = "-43.2")))),
        )

        val client = answering(ItemStatus.ACCEPTED)
        service(
            FakeRecordSource(
                pages = emptyList(),
                changePages = listOf(
                    changePage(
                        listOf(SourceChange.Upserted(changedAt, rerouted), SourceChange.Removed(changedAt, "uid-indoor")),
                    ),
                ),
            ),
            client,
        ).sync()

        val (edited, removed) = client.delivered()
        assertEquals("uid-routed", edited.samsungUid)
        assertEquals(changedAt.toString(), edited.observedAt.instant)
        assertEquals("-22.9", edited.route()!!.single().jsonObject.getValue("latitudeDegrees").jsonPrimitive.content)
        assertEquals("uid-indoor", removed.samsungUid)
        assertEquals(RecordState.Removed(period = null), removed.state)
        assertEquals(setOf("exercise"), client.batches.map { it.recordType }.toSet())
    }
}
