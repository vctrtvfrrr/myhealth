package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.health.CheckResult
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.PermissionHistoryStore
import br.etc.victor.myhealthbridge.health.PermissionObservation
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthGateway
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.contract.IngestionBatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Synthetic heart rate readings.
 *
 * Every value is invented: no fixture in this repository may come from a real measurement.
 */
fun sourceRecord(
    uid: String = "uid-1",
    start: Instant = Instant.parse("2026-08-10T21:59:00Z"),
    end: Instant? = start,
    offset: ZoneOffset? = ZoneOffset.ofHours(-3),
    updateTime: Instant? = Instant.parse("2026-08-10T22:00:00Z"),
    sourceAppId: String? = "com.example.shealth",
    sourceDeviceId: String? = "device-1",
    clientDataId: String? = "client-1",
    clientVersion: Int? = 3,
    fields: Map<String, SourceValue> = mapOf("heart_rate" to number("72.0")),
): SourceRecord = SourceRecord(
    uid = uid,
    startTime = start,
    endTime = end,
    zoneOffset = offset,
    updateTime = updateTime,
    sourceAppId = sourceAppId,
    sourceDeviceId = sourceDeviceId,
    clientDataId = clientDataId,
    clientVersion = clientVersion,
    fields = fields,
)

fun number(value: String): SourceValue.Number = SourceValue.Number(BigDecimal(value))

val heartRate: HealthCapability = HealthCapabilities.of(HealthCategory.HEART_RATE)!!

/** An in-memory outbox and cursor set, keeping the page and its cursor as one write. */
class FakeSyncStore : SyncStore {

    private val cursors = mutableMapOf<HealthCategory, SyncCursor>()
    private val items = mutableListOf<StagedItem>()
    private var nextId = 1L

    var acceptedPages: Int = 0
        private set

    class StagedItem(val id: Long, val item: NewOutboxItem, var codes: List<RejectionCode>?)

    val staged: List<StagedItem> get() = items

    override suspend fun cursor(category: HealthCategory): SyncCursor? = cursors[category]

    override fun observeCursors(): Flow<List<SyncCursor>> = MutableStateFlow(cursors.values.toList()).asStateFlow()

    override suspend fun writeCursor(cursor: SyncCursor) {
        cursors[cursor.category] = cursor
    }

    override suspend fun acceptPage(items: List<NewOutboxItem>, cursor: SyncCursor) {
        items.forEach { this.items += StagedItem(nextId++, it, null) }
        cursors[cursor.category] = cursor
        acceptedPages++
    }

    override suspend fun pending(recordType: String, limit: Int): List<OutboxItem> = items
        .filter { it.item.recordType == recordType && it.codes == null }
        .take(limit)
        .map { OutboxItem(it.id, it.item.envelopeJson) }

    override suspend fun pendingCount(): Int = items.count { it.codes == null }

    override fun observeOutbox(): Flow<OutboxSize> = MutableStateFlow(OutboxSize()).asStateFlow()

    override suspend fun confirm(ids: List<Long>) {
        items.removeAll { it.id in ids }
    }

    override suspend fun reject(id: Long, codes: List<RejectionCode>) {
        items.first { it.id == id }.codes = codes
    }
}

class FakeEndpointStore(private var endpoint: IngestionEndpoint? = IngestionEndpoint("https://api.invalid", "token")) :
    IngestionEndpointStore {

    override fun observe(): Flow<IngestionEndpoint?> = MutableStateFlow(endpoint).asStateFlow()

    override suspend fun read(): IngestionEndpoint? = endpoint

    override suspend fun write(endpoint: IngestionEndpoint) {
        this.endpoint = endpoint
    }
}

/** Answers the pages it was given, in order, and then reports the window as exhausted. */
class FakeRecordSource(private val pages: List<SamsungHealthOutcome<RecordPage>>) : HealthRecordSource {

    val windows = mutableListOf<ReadWindow>()

    private var next = 0

    override suspend fun readPage(
        capability: HealthCapability,
        window: ReadWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<RecordPage> {
        windows += window
        val page = pages.getOrNull(next) ?: SamsungHealthOutcome.Observed(RecordPage(emptyList(), null))
        next++
        return page
    }
}

fun page(records: List<SourceRecord>, nextPageToken: String? = null): SamsungHealthOutcome<RecordPage> =
    SamsungHealthOutcome.Observed(RecordPage(records, nextPageToken))

class FakeIngestionClient(private val answer: (IngestionBatch) -> SendOutcome) : IngestionClient {

    val batches = mutableListOf<IngestionBatch>()

    override suspend fun send(endpoint: IngestionEndpoint, batch: IngestionBatch): SendOutcome {
        batches += batch
        return answer(batch)
    }
}

/** Grants exactly the categories it is given, every time it is asked. */
class FakeGateway(private val granted: Set<HealthCategory>, private val available: Boolean = true) :
    SamsungHealthGateway {

    override suspend fun grantedReadCategories(): SamsungHealthOutcome<Set<HealthCategory>> =
        if (available) SamsungHealthOutcome.Observed(granted)
        else SamsungHealthOutcome.Failed(SamsungHealthAvailability.TemporarilyUnavailable("fake"))

    override suspend fun requestReadPermissions(categories: Set<HealthCategory>): SamsungHealthOutcome<Unit> =
        SamsungHealthOutcome.Observed(Unit)
}

class InMemoryPermissionHistory : PermissionHistoryStore {

    private var observation: PermissionObservation? = null

    override suspend fun read(): PermissionObservation? = observation

    override suspend fun write(observation: PermissionObservation) {
        this.observation = observation
    }
}
