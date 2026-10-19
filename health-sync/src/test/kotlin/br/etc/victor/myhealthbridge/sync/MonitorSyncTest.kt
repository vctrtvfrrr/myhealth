package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.HealthPermissionsService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Irregular heart rhythm notifications, sleep apnea assessments and energy scores through the pipeline
 * heart rate proved: the paginated history with its Record Inventory, the daily overlap, a
 * reconciliation, and the changes feed with its edits and Source Removals. A device without the
 * Samsung Health Monitor reports nothing for the first two, which is an empty history, not a failure.
 */
class MonitorSyncTest {

    enum class Monitored(
        val category: HealthCategory,
        val recordType: String,
        val record: (uid: String, updateTime: Instant) -> SourceRecord,
    ) {
        IRREGULAR_HEART_RHYTHM(HealthCategory.IRREGULAR_HEART_RHYTHM, "irregular_heart_rhythm", { uid, updateTime ->
            irregularHeartRhythmRecord(uid = uid, updateTime = updateTime)
        }),
        SLEEP_APNEA(HealthCategory.SLEEP_APNEA, "sleep_apnea", { uid, updateTime ->
            sleepApneaRecord(uid = uid, updateTime = updateTime)
        }),
        ENERGY_SCORE(HealthCategory.ENERGY_SCORE, "energy_score", { uid, updateTime ->
            energyScoreRecord(uid = uid, updateTime = updateTime)
        }),
    }

    private val now = Instant.parse("2026-08-11T12:00:00Z")
    private val store = FakeSyncStore()
    private val maintenance = FakeMaintenance()

    private val recordedAt = Instant.parse("2026-08-10T12:00:00Z")
    private val changedAt = Instant.parse("2026-08-11T11:00:00Z")

    private fun service(
        monitored: Monitored,
        source: FakeRecordSource,
        client: FakeIngestionClient = answering(ItemStatus.ACCEPTED),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ): SyncService {
        val policy = SyncPolicy()
        val channel = maintenanceService(maintenance, clock)
        return SyncService(
            permissions = HealthPermissionsService(
                gateway = FakeGateway(setOf(monitored.category)),
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

    private suspend fun cursor(monitored: Monitored) = store.cursor(monitored.category)!!

    private fun FakeIngestionClient.delivered(): List<HealthRecordEnvelope> = observationBatches.flatMap { it.items }

    private fun Monitored.first() = record("uid-first", recordedAt)

    private fun Monitored.second() = record("uid-second", recordedAt)

    /** A history already walked and delivered, which is the only state the changes feed is read in. */
    private suspend fun imported(monitored: Monitored) {
        val service = service(monitored, FakeRecordSource(listOf(page(listOf(monitored.first(), monitored.second())))))
        service.startInitialLoad()
        service.sync()
    }

    @ParameterizedTest
    @EnumSource(Monitored::class)
    fun `carries every page of the history to the ingestion API`(monitored: Monitored) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(
            monitored,
            FakeRecordSource(listOf(page(listOf(monitored.first()), nextPageToken = "next"), page(listOf(monitored.second())))),
            client,
        )
        service.startInitialLoad()

        service.sync()

        assertEquals(setOf(monitored.recordType), client.batches.map { it.recordType }.toSet())
        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(ImportPhase.INCREMENTAL, cursor(monitored).phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor(monitored).lastOutcome)
        assertEquals(emptyList<Any>(), maintenance.reported)
    }

    @ParameterizedTest
    @EnumSource(Monitored::class)
    fun `declares what each page read as a Record Inventory of its own record type`(monitored: Monitored) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(monitored, FakeRecordSource(listOf(page(listOf(monitored.first(), monitored.second())))), client)
        service.startInitialLoad()

        service.sync()

        val declared = client.batches.filter { it.inventory != null }
        assertEquals(setOf(monitored.recordType), declared.map { it.recordType }.toSet())
        assertEquals(setOf("uid-first", "uid-second"), declared.flatMap { it.inventory!!.samsungUids }.toSet())
    }

    /** What a watch without the Samsung Health Monitor, or a day with no score yet, looks like to the sync. */
    @ParameterizedTest
    @EnumSource(Monitored::class)
    fun `completes an empty history and an empty changes feed without an incident`(monitored: Monitored) = runTest {
        val client = answering(ItemStatus.ACCEPTED)
        val service = service(monitored, FakeRecordSource(pages = listOf(page(emptyList())), changePages = listOf(changePage(emptyList()))), client)
        service.startInitialLoad()

        service.sync()
        service.sync()

        assertEquals(emptyList<HealthRecordEnvelope>(), client.delivered())
        assertEquals(ImportPhase.INCREMENTAL, cursor(monitored).phase)
        assertEquals(SyncOutcome.SUCCEEDED, cursor(monitored).lastOutcome)
        assertEquals(emptyList<Any>(), maintenance.reported)
    }

    @ParameterizedTest
    @EnumSource(Monitored::class)
    fun `re-reads the previous seven days once a day, the API answering what it already holds`(monitored: Monitored) = runTest {
        imported(monitored)
        val walked = cursor(monitored).readFrom
        val nextDay = Clock.fixed(now.plus(Duration.ofDays(1)), ZoneOffset.UTC)
        val source = FakeRecordSource(listOf(page(listOf(monitored.first()))))
        val client = answering(ItemStatus.ALREADY_PRESENT)

        service(monitored, source, client, nextDay).sync()

        assertEquals(walked.minusDays(7), source.windows.first().from)
        assertEquals(listOf("uid-first"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(monitored).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Monitored::class)
    fun `reconciles the history with nothing left behind when the API already holds it`(monitored: Monitored) = runTest {
        imported(monitored)

        val client = answering(ItemStatus.ALREADY_PRESENT)
        val service = service(monitored, FakeRecordSource(listOf(page(listOf(monitored.first(), monitored.second())))), client)
        service.reconcile()
        service.sync()

        assertEquals(listOf("uid-first", "uid-second"), client.delivered().map { it.samsungUid })
        assertTrue(store.staged.isEmpty())
        assertEquals(SyncOutcome.SUCCEEDED, cursor(monitored).lastOutcome)
    }

    @ParameterizedTest
    @EnumSource(Monitored::class)
    fun `delivers an edited record and a removed one from the changes feed`(monitored: Monitored) = runTest {
        imported(monitored)
        val edited = monitored.record("uid-first", changedAt)

        val client = answering(ItemStatus.ACCEPTED)
        service(
            monitored,
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
        assertEquals("uid-second", removed.samsungUid)
        assertEquals(RecordState.Removed(period = null), removed.state)
        assertEquals(setOf(monitored.recordType), client.batches.map { it.recordType }.toSet())
    }
}
