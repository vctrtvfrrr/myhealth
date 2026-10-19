package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
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
 * Blood oxygen, skin temperature and blood glucose through the same pipeline heart rate and sleep
 * proved: each its own record type, its series carried inside the envelope that holds it, and every
 * path a measurement can take — the paginated history with its Record Inventory, the daily overlap,
 * a reconciliation, and the changes feed with its edits and Source Removals.
 */
class BinnedSeriesSyncTest {

    enum class Binned(
        val category: HealthCategory,
        val recordType: String,
        val payloadKey: String,
        val record: (uid: String, updateTime: Instant, bins: Int) -> SourceRecord,
    ) {
        BLOOD_OXYGEN(HealthCategory.BLOOD_OXYGEN, "blood_oxygen", "bloodOxygen", { uid, updateTime, bins ->
            bloodOxygenRecord(uid = uid, updateTime = updateTime, bins = List(bins) { oxygenBin() })
        }),
        SKIN_TEMPERATURE(HealthCategory.SKIN_TEMPERATURE, "skin_temperature", "skinTemperature", { uid, updateTime, bins ->
            skinTemperatureRecord(uid = uid, updateTime = updateTime, bins = List(bins) { skinTemperatureBin() })
        }),
        BLOOD_GLUCOSE(HealthCategory.BLOOD_GLUCOSE, "blood_glucose", "bloodGlucose", { uid, updateTime, bins ->
            bloodGlucoseRecord(uid = uid, updateTime = updateTime, bins = List(bins) { glucoseBin() })
        }),
    }

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val recordedAt = Instant.parse("2026-08-10T12:00:00Z")
    private val changedAt = Instant.parse("2026-08-11T11:00:00Z")

    private fun service(
        binned: Binned,
        source: FakeRecordSource,
        client: FakeIngestionClient = answering(ItemStatus.ACCEPTED),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(binned.category)),
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

    private suspend fun cursor(binned: Binned) = store.cursor(binned.category)!!

    private fun FakeIngestionClient.delivered(): List<HealthRecordEnvelope> = observationBatches.flatMap { it.items }

    private fun HealthRecordEnvelope.series(binned: Binned): JsonArray? =
        ((state as RecordState.Present).normalizedPayload[binned.payloadKey] as JsonObject)["series"] as? JsonArray

    private fun Binned.first() = record("uid-first", recordedAt, 2)

    private fun Binned.second() = record("uid-second", recordedAt, 0)

    /** A history already walked and delivered, which is the only state the changes feed is read in. */
    private suspend fun imported(binned: Binned) {
        val service = service(binned, FakeRecordSource(listOf(page(listOf(binned.first(), binned.second())))))
        service.startInitialLoad()
        service.sync()
    }

    @ParameterizedTest
    @EnumSource(Binned::class)
    fun `carries every page of the history to the ingestion API, series inside its measurement`(binned: Binned) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(
            binned,
            FakeRecordSource(listOf(page(listOf(binned.first()), nextPageToken = "next"), page(listOf(binned.second())))),
            client,
        )
        service.startInitialLoad()

        service.sync()

        assertEquals(setOf(binned.recordType), client.batches.map { it.recordType }.toSet())
        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertEquals(2, client.delivered().first().series(binned)!!.size)
        assertEquals(null, client.delivered().last().series(binned))
        assertTrue(store.staged.isEmpty())
        assertEquals(ImportPhase.INCREMENTAL, cursor(binned).phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor(binned).lastOutcome)
        assertEquals(emptyList<Any>(), maintenance.reported)
    }

    @ParameterizedTest
    @EnumSource(Binned::class)
    fun `declares what each page read as a Record Inventory of its own record type`(binned: Binned) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(binned, FakeRecordSource(listOf(page(listOf(binned.first(), binned.second())))), client)
        service.startInitialLoad()

        service.sync()

        val declared = client.batches.filter { it.inventory != null }
        assertEquals(setOf(binned.recordType), declared.map { it.recordType }.toSet())
        assertEquals(setOf("uid-first", "uid-second"), declared.flatMap { it.inventory!!.samsungUids }.toSet())
    }

    @ParameterizedTest
    @EnumSource(Binned::class)
    fun `re-reads the previous seven days once a day, the API answering what it already holds`(binned: Binned) = runTest {
        imported(binned)
        val walked = cursor(binned).readFrom
        val nextDay = Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC)
        val source = FakeRecordSource(listOf(page(listOf(binned.first()))))
        val client = answering(ItemStatus.ALREADY_PRESENT)

        service(binned, source, client, nextDay).sync()

        assertEquals(walked.minusDays(7), source.windows.first().from)
        assertEquals(listOf("uid-first"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(binned).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Binned::class)
    fun `reconciles the history with nothing left behind when the API already holds it`(binned: Binned) = runTest {
        imported(binned)

        val client = answering(ItemStatus.ALREADY_PRESENT)
        val service = service(binned, FakeRecordSource(listOf(page(listOf(binned.first(), binned.second())))), client)
        service.reconcile()
        service.sync()

        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(binned).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Binned::class)
    fun `delivers an edited measurement and a removed one from the changes feed`(binned: Binned) = runTest {
        imported(binned)
        val edited = binned.record("uid-first", changedAt, 1)

        val client = answering(ItemStatus.ACCEPTED)
        service(
            binned,
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
        assertEquals(1, upserted.series(binned)!!.size)
        assertEquals("uid-second", removed.samsungUid)
        assertEquals(RecordState.Removed(period = null), removed.state)
        assertEquals(setOf(binned.recordType), client.batches.map { it.recordType }.toSet())
    }
}
