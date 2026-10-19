package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Blood pressure, body composition and body temperature through the same pipeline heart rate proved:
 * each its own record type of plain fields, and every path a reading can take — the paginated history
 * with its Record Inventory, the daily overlap, a reconciliation, and the changes feed with its edits
 * and Source Removals.
 */
class VitalSignSyncTest {

    enum class Vital(
        val category: HealthCategory,
        val recordType: String,
        val payloadKey: String,
        val record: (uid: String, updateTime: Instant) -> SourceRecord,
    ) {
        BLOOD_PRESSURE(HealthCategory.BLOOD_PRESSURE, "blood_pressure", "bloodPressure", { uid, updateTime ->
            bloodPressureRecord(uid = uid, updateTime = updateTime)
        }),
        BODY_COMPOSITION(HealthCategory.BODY_COMPOSITION, "body_composition", "bodyComposition", { uid, updateTime ->
            bodyCompositionRecord(uid = uid, updateTime = updateTime)
        }),
        BODY_TEMPERATURE(HealthCategory.BODY_TEMPERATURE, "body_temperature", "bodyTemperature", { uid, updateTime ->
            bodyTemperatureRecord(uid = uid, updateTime = updateTime)
        }),
    }

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val recordedAt = Instant.parse("2026-08-10T12:00:00Z")
    private val changedAt = Instant.parse("2026-08-11T11:00:00Z")

    private fun service(
        vital: Vital,
        source: FakeRecordSource,
        client: FakeIngestionClient = answering(ItemStatus.ACCEPTED),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(vital.category)),
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

    private suspend fun cursor(vital: Vital) = store.cursor(vital.category)!!

    private fun FakeIngestionClient.delivered(): List<HealthRecordEnvelope> = observationBatches.flatMap { it.items }

    private fun HealthRecordEnvelope.normalized(vital: Vital): JsonObject? =
        (state as RecordState.Present).normalizedPayload[vital.payloadKey] as? JsonObject

    private fun Vital.first() = record("uid-first", recordedAt)

    private fun Vital.second() = record("uid-second", recordedAt)

    /** A history already walked and delivered, which is the only state the changes feed is read in. */
    private suspend fun imported(vital: Vital) {
        val service = service(vital, FakeRecordSource(listOf(page(listOf(vital.first(), vital.second())))))
        service.startInitialLoad()
        service.sync()
    }

    @ParameterizedTest
    @EnumSource(Vital::class)
    fun `carries every page of the history to the ingestion API, each reading normalized`(vital: Vital) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(
            vital,
            FakeRecordSource(listOf(page(listOf(vital.first()), nextPageToken = "next"), page(listOf(vital.second())))),
            client,
        )
        service.startInitialLoad()

        service.sync()

        assertEquals(setOf(vital.recordType), client.batches.map { it.recordType }.toSet())
        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertTrue(client.delivered().all { it.normalized(vital) != null })
        assertTrue(store.staged.isEmpty())
        assertEquals(ImportPhase.INCREMENTAL, cursor(vital).phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor(vital).lastOutcome)
        assertEquals(emptyList<Any>(), maintenance.reported)
    }

    @ParameterizedTest
    @EnumSource(Vital::class)
    fun `declares what each page read as a Record Inventory of its own record type`(vital: Vital) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(vital, FakeRecordSource(listOf(page(listOf(vital.first(), vital.second())))), client)
        service.startInitialLoad()

        service.sync()

        val declared = client.batches.filter { it.inventory != null }
        assertEquals(setOf(vital.recordType), declared.map { it.recordType }.toSet())
        assertEquals(setOf("uid-first", "uid-second"), declared.flatMap { it.inventory!!.samsungUids }.toSet())
    }

    @ParameterizedTest
    @EnumSource(Vital::class)
    fun `re-reads the previous seven days once a day, the API answering what it already holds`(vital: Vital) = runTest {
        imported(vital)
        val walked = cursor(vital).readFrom
        val nextDay = Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC)
        val source = FakeRecordSource(listOf(page(listOf(vital.first()))))
        val client = answering(ItemStatus.ALREADY_PRESENT)

        service(vital, source, client, nextDay).sync()

        assertEquals(walked.minusDays(7), source.windows.first().from)
        assertEquals(listOf("uid-first"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(vital).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Vital::class)
    fun `reconciles the history with nothing left behind when the API already holds it`(vital: Vital) = runTest {
        imported(vital)

        val client = answering(ItemStatus.ALREADY_PRESENT)
        val service = service(vital, FakeRecordSource(listOf(page(listOf(vital.first(), vital.second())))), client)
        service.reconcile()
        service.sync()

        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(vital).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Vital::class)
    fun `delivers an edited reading and a removed one from the changes feed`(vital: Vital) = runTest {
        imported(vital)
        val edited = vital.record("uid-first", changedAt)

        val client = answering(ItemStatus.ACCEPTED)
        service(
            vital,
            FakeRecordSource(
                pages = emptyList(),
                changePages = listOf(
                    changePage(listOf(SourceChange.Upserted(changedAt, edited), SourceChange.Removed(changedAt, "uid-second"))),
                ),
            ),
            client,
        ).sync()

        val (upserted, removed) = client.delivered()
        assertEquals("uid-first", upserted.samsungUid)
        assertEquals(changedAt.toString(), upserted.observedAt.instant)
        assertTrue(upserted.normalized(vital) != null)
        assertEquals("uid-second", removed.samsungUid)
        assertEquals(RecordState.Removed(period = null), removed.state)
        assertEquals(setOf(vital.recordType), client.batches.map { it.recordType }.toSet())
    }
}
