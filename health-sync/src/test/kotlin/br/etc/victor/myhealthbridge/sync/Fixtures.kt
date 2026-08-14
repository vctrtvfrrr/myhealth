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
import br.etc.victor.myhealthbridge.maintenance.IncidentIdentity
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import br.etc.victor.myhealthbridge.maintenance.MaintenanceIncident
import br.etc.victor.myhealthbridge.maintenance.MaintenanceNotifier
import br.etc.victor.myhealthbridge.maintenance.MaintenancePolicy
import br.etc.victor.myhealthbridge.maintenance.MaintenanceService
import br.etc.victor.myhealthbridge.maintenance.MaintenanceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal
import java.time.Clock
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

/**
 * A synthetic exercise, as the adapter hands one over.
 *
 * Every value is invented, including the coordinates: no route in this repository may come from a
 * place the Data Owner has been.
 */
fun exerciseRecord(
    uid: String = "uid-exercise-1",
    start: Instant = Instant.parse("2026-08-10T09:00:00Z"),
    end: Instant? = Instant.parse("2026-08-10T09:30:00Z"),
    updateTime: Instant? = Instant.parse("2026-08-10T10:00:00Z"),
    exerciseType: String? = "RUNNING",
    customTitle: String? = null,
    sessions: List<Map<String, SourceValue>>? = listOf(session()),
): SourceRecord = sourceRecord(
    uid = uid,
    start = start,
    end = end,
    updateTime = updateTime,
    fields = buildMap {
        exerciseType?.let { put("exercise_type", SourceValue.Text(it)) }
        customTitle?.let { put("custom_title", SourceValue.Text(it)) }
        sessions?.let { put("sessions", SourceValue.Series(it)) }
    },
)

fun session(
    duration: String? = "1800.000",
    distance: String? = "5000.0",
    calories: String? = "320.0",
    route: List<Map<String, SourceValue>> = emptyList(),
    fields: Map<String, SourceValue> = emptyMap(),
): Map<String, SourceValue> = buildMap {
    duration?.let { put("duration", number(it)) }
    distance?.let { put("distance", number(it)) }
    calories?.let { put("calories", number(it)) }
    if (route.isNotEmpty()) put("route", SourceValue.Series(route))
    putAll(fields)
}

fun location(
    at: String = "2026-08-10T09:00:00Z",
    latitude: String = "-23.5",
    longitude: String = "-46.6",
    altitude: String? = "760.0",
    accuracy: String? = "4.0",
): Map<String, SourceValue> = buildMap {
    put("timestamp", SourceValue.Text(at))
    put("latitude", number(latitude))
    put("longitude", number(longitude))
    altitude?.let { put("altitude", number(it)) }
    accuracy?.let { put("accuracy", number(it)) }
}

/** An outbox item of a record type no capability in this test drains. */
fun foreignItem(uid: String): NewOutboxItem = NewOutboxItem(
    category = HealthCategory.STEPS,
    recordType = "steps",
    samsungUid = uid,
    envelopeJson = "{}",
    enqueuedAt = Instant.parse("2026-08-11T12:00:00Z"),
)

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

    /** Drops every local trace of the synchronization, the way reinstalling the application does. */
    fun forgetLocalState() {
        cursors.clear()
        items.clear()
        acceptedPages = 0
    }
}

/**
 * The maintenance channel as the synchronization sees it, keeping what was reported to it.
 *
 * Store and notifier are the same object because a test of the synchronization asks one question of
 * both: which incidents this run raised.
 */
class FakeMaintenance : MaintenanceStore, MaintenanceNotifier {

    private val incidents = mutableMapOf<String, MaintenanceIncident>()

    val posted = mutableListOf<MaintenanceIncident>()

    val withdrawn = mutableListOf<IncidentIdentity>()

    val reported: List<IncidentIdentity> get() = incidents.values.map { it.identity }

    val reportedCodes: List<MaintenanceCode> get() = reported.map { it.code }

    override suspend fun read(identity: IncidentIdentity): MaintenanceIncident? = incidents[identity.key]

    override suspend fun write(incident: MaintenanceIncident) {
        incidents[incident.identity.key] = incident
    }

    override suspend fun transient(): List<MaintenanceIncident> =
        incidents.values.filter { it.identity.code.transient }

    override suspend fun forget(identities: List<IncidentIdentity>) {
        identities.forEach { incidents.remove(it.key) }
    }

    override fun observe(): Flow<List<MaintenanceIncident>> = flowOf(incidents.values.toList())

    override fun post(incident: MaintenanceIncident) {
        posted += incident
    }

    override fun withdraw(identity: IncidentIdentity) {
        withdrawn += identity
    }
}

fun maintenanceService(channel: FakeMaintenance, clock: Clock): MaintenanceService =
    MaintenanceService(channel, channel, MaintenancePolicy(), clock)

class FakeEndpointStore(private var endpoint: IngestionEndpoint? = IngestionEndpoint("https://api.invalid", "token")) :
    IngestionEndpointStore {

    override fun observe(): Flow<IngestionEndpoint?> = MutableStateFlow(endpoint).asStateFlow()

    override suspend fun read(): IngestionEndpoint? = endpoint

    override suspend fun write(endpoint: IngestionEndpoint) {
        this.endpoint = endpoint
    }
}

/**
 * Answers the page the token asks for, the way Samsung Health does: no token means the start of the
 * window, and a token means the page that follows the one which handed it out.
 */
class FakeRecordSource(
    private val pages: List<SamsungHealthOutcome<RecordPage>>,
    private val changePages: List<SamsungHealthOutcome<ChangePage>> = emptyList(),
) : HealthRecordSource {

    val windows = mutableListOf<ReadWindow>()

    val tokens = mutableListOf<String?>()

    val changeWindows = mutableListOf<ChangeWindow>()

    val changeTokens = mutableListOf<String?>()

    private val byToken: Map<String?, Int> = tokenIndex(pages) { it.nextPageToken }

    private val byChangeToken: Map<String?, Int> = tokenIndex(changePages) { it.nextPageToken }

    override suspend fun readPage(
        capability: HealthCapability,
        window: ReadWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<RecordPage> {
        windows += window
        tokens += pageToken
        return byToken[pageToken]
            ?.let(pages::getOrNull)
            ?: SamsungHealthOutcome.Observed(RecordPage(emptyList(), null))
    }

    override suspend fun readChanges(
        capability: HealthCapability,
        window: ChangeWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<ChangePage> {
        changeWindows += window
        changeTokens += pageToken
        return byChangeToken[pageToken]
            ?.let(changePages::getOrNull)
            ?: SamsungHealthOutcome.Observed(ChangePage(emptyList(), null))
    }

    private fun <T> tokenIndex(
        pages: List<SamsungHealthOutcome<T>>,
        tokenOf: (T) -> String?,
    ): Map<String?, Int> = buildMap {
        put(null, 0)
        pages.forEachIndexed { index, outcome ->
            (outcome as? SamsungHealthOutcome.Observed)?.value?.let(tokenOf)?.let { put(it, index + 1) }
        }
    }
}

fun page(records: List<SourceRecord>, nextPageToken: String? = null): SamsungHealthOutcome<RecordPage> =
    SamsungHealthOutcome.Observed(RecordPage(records, nextPageToken))

fun changePage(changes: List<SourceChange>, nextPageToken: String? = null): SamsungHealthOutcome<ChangePage> =
    SamsungHealthOutcome.Observed(ChangePage(changes, nextPageToken))

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
