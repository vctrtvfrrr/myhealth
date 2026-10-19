package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Nutrition, water intake and floors climbed through the same pipeline heart rate proved: each its own
 * record type, and every path a record can take — the paginated history with its Record Inventory, the
 * daily overlap, a reconciliation, and the changes feed with its edits and Source Removals.
 */
class ConsumptionSyncTest {

    enum class Consumption(
        val category: HealthCategory,
        val recordType: String,
        val payloadKey: String,
        val valueKey: String,
        val record: (uid: String, updateTime: Instant, value: String) -> SourceRecord,
    ) {
        NUTRITION(HealthCategory.NUTRITION, "nutrition", "nutrition", "calories", { uid, updateTime, value ->
            nutritionRecord(uid = uid, updateTime = updateTime, calories = value)
        }),
        WATER_INTAKE(HealthCategory.WATER_INTAKE, "water_intake", "waterIntake", "amount", { uid, updateTime, value ->
            waterIntakeRecord(uid = uid, updateTime = updateTime, amount = value)
        }),
        FLOORS_CLIMBED(HealthCategory.FLOORS_CLIMBED, "floors_climbed", "floorsClimbed", "floors", { uid, updateTime, value ->
            floorsClimbedRecord(uid = uid, updateTime = updateTime, floors = value)
        }),
    }

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val recordedAt = Instant.parse("2026-08-10T12:00:00Z")
    private val changedAt = Instant.parse("2026-08-11T11:00:00Z")

    private fun service(
        consumption: Consumption,
        source: FakeRecordSource,
        client: FakeIngestionClient = answering(ItemStatus.ACCEPTED),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(consumption.category)),
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

    private suspend fun cursor(consumption: Consumption) = store.cursor(consumption.category)!!

    private fun FakeIngestionClient.delivered(): List<HealthRecordEnvelope> = observationBatches.flatMap { it.items }

    private fun HealthRecordEnvelope.value(consumption: Consumption): String {
        val normalized = (state as RecordState.Present).normalizedPayload[consumption.payloadKey] as JsonObject
        return when (val value = normalized[consumption.valueKey]) {
            is JsonObject -> (value["value"] as JsonPrimitive).content
            else -> (value as JsonPrimitive).content
        }
    }

    private fun Consumption.first() = record("uid-first", recordedAt, "3.0")

    private fun Consumption.second() = record("uid-second", recordedAt, "5.0")

    /** A history already walked and delivered, which is the only state the changes feed is read in. */
    private suspend fun imported(consumption: Consumption) {
        val service = service(consumption, FakeRecordSource(listOf(page(listOf(consumption.first(), consumption.second())))))
        service.startInitialLoad()
        service.sync()
    }

    @ParameterizedTest
    @EnumSource(Consumption::class)
    fun `carries every page of the history to the ingestion API`(consumption: Consumption) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(
            consumption,
            FakeRecordSource(listOf(page(listOf(consumption.first()), nextPageToken = "next"), page(listOf(consumption.second())))),
            client,
        )
        service.startInitialLoad()

        service.sync()

        assertEquals(setOf(consumption.recordType), client.batches.map { it.recordType }.toSet())
        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertEquals(listOf("3.0", "5.0"), client.delivered().map { it.value(consumption) })
        assertTrue(store.staged.isEmpty())
        assertEquals(ImportPhase.INCREMENTAL, cursor(consumption).phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor(consumption).lastOutcome)
        assertEquals(emptyList<Any>(), maintenance.reported)
    }

    @ParameterizedTest
    @EnumSource(Consumption::class)
    fun `declares what each page read as a Record Inventory of its own record type`(consumption: Consumption) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(consumption, FakeRecordSource(listOf(page(listOf(consumption.first(), consumption.second())))), client)
        service.startInitialLoad()

        service.sync()

        val declared = client.batches.filter { it.inventory != null }
        assertEquals(setOf(consumption.recordType), declared.map { it.recordType }.toSet())
        assertEquals(setOf("uid-first", "uid-second"), declared.flatMap { it.inventory!!.samsungUids }.toSet())
    }

    @ParameterizedTest
    @EnumSource(Consumption::class)
    fun `re-reads the previous seven days once a day, the API answering what it already holds`(consumption: Consumption) = runTest {
        imported(consumption)
        val walked = cursor(consumption).readFrom
        val nextDay = Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC)
        val source = FakeRecordSource(listOf(page(listOf(consumption.first()))))
        val client = answering(ItemStatus.ALREADY_PRESENT)

        service(consumption, source, client, nextDay).sync()

        assertEquals(walked.minusDays(7), source.windows.first().from)
        assertEquals(listOf("uid-first"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(consumption).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Consumption::class)
    fun `reconciles the history with nothing left behind when the API already holds it`(consumption: Consumption) = runTest {
        imported(consumption)

        val client = answering(ItemStatus.ALREADY_PRESENT)
        val service = service(consumption, FakeRecordSource(listOf(page(listOf(consumption.first(), consumption.second())))), client)
        service.reconcile()
        service.sync()

        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(consumption).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Consumption::class)
    fun `delivers an edited record and a removed one from the changes feed`(consumption: Consumption) = runTest {
        imported(consumption)
        val edited = consumption.record("uid-first", changedAt, "4.0")

        val client = answering(ItemStatus.ACCEPTED)
        service(
            consumption,
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
        assertEquals("4.0", upserted.value(consumption))
        assertEquals("uid-second", removed.samsungUid)
        assertEquals(RecordState.Removed(period = null), removed.state)
        assertEquals(setOf(consumption.recordType), client.batches.map { it.recordType }.toSet())
    }
}
