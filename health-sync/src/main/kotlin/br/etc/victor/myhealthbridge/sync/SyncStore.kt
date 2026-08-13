package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.health.HealthCategory
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

/** An envelope waiting to be delivered, as it is about to be staged. */
data class NewOutboxItem(
    val category: HealthCategory,
    val recordType: String,
    val samsungUid: String,
    val envelopeJson: String,
    val enqueuedAt: Instant,
)

/** One observation of this capability, rendered as the outbox keeps it. */
internal fun HealthCapability.staged(
    uid: String,
    envelope: HealthRecordEnvelope,
    at: Instant,
): NewOutboxItem = NewOutboxItem(
    category = category,
    recordType = recordType,
    samsungUid = uid,
    envelopeJson = IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope),
    enqueuedAt = at,
)

/** A staged envelope the sender is about to deliver. */
data class OutboxItem(
    val id: Long,
    val envelopeJson: String,
)

/**
 * What the outbox holds right now.
 *
 * A mapping pendency is an item the API rejected: it is kept, because the observation was read and
 * dropping it would hide a mapper defect, and it is never sent again, because nothing about it will
 * change until the mapper does.
 */
data class OutboxSize(
    val pending: Int = 0,
    val mappingPendencies: Int = 0,
)

/**
 * The durable side of the synchronization: what was read but not yet confirmed, and how far each
 * Health Category has read.
 *
 * The two live behind one interface because of [acceptPage]: a cursor may only move over a page that
 * is already staged, and only a single transaction can promise that after a process death.
 */
interface SyncStore {

    suspend fun cursor(category: HealthCategory): SyncCursor?

    fun observeCursors(): Flow<List<SyncCursor>>

    suspend fun writeCursor(cursor: SyncCursor)

    /** Stages a whole page and advances its cursor in one transaction, or neither. */
    suspend fun acceptPage(items: List<NewOutboxItem>, cursor: SyncCursor)

    suspend fun pending(recordType: String, limit: Int): List<OutboxItem>

    suspend fun pendingCount(): Int

    fun observeOutbox(): Flow<OutboxSize>

    /** Drops items the API confirmed as durable, whether accepted now or already present. */
    suspend fun confirm(ids: List<Long>)

    /** Keeps a rejected item as a mapping pendency, out of every later batch. */
    suspend fun reject(id: Long, codes: List<RejectionCode>)
}

/** The ingestion API this device delivers to. */
data class IngestionEndpoint(
    val baseUrl: String,
    val deviceToken: String,
)

interface IngestionEndpointStore {

    fun observe(): Flow<IngestionEndpoint?>

    suspend fun read(): IngestionEndpoint?

    suspend fun write(endpoint: IngestionEndpoint)
}

/**
 * The bounds the synchronization runs under.
 *
 * The outbox limit is backpressure: reading more of the history than the API has confirmed only
 * grows a buffer on a phone, so the import stops until the outbox drains.
 */
data class SyncPolicy(
    val maxOutboxItems: Int = 5_000,
    val batchItems: Int = 200,
    /** How far back the overlap re-read pulls the next read of a category. */
    val overlapWindow: Duration = Duration.ofDays(7),
    /** How much time has to pass between two overlap re-reads of the same category. */
    val overlapEvery: Duration = Duration.ofDays(1),
)
