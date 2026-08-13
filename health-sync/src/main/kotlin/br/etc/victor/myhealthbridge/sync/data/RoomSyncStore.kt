package br.etc.victor.myhealthbridge.sync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.sync.HISTORY_FLOOR
import br.etc.victor.myhealthbridge.sync.ImportPhase
import br.etc.victor.myhealthbridge.sync.IngestionEndpoint
import br.etc.victor.myhealthbridge.sync.IngestionEndpointStore
import br.etc.victor.myhealthbridge.sync.InitialLoadWindow
import br.etc.victor.myhealthbridge.sync.NewOutboxItem
import br.etc.victor.myhealthbridge.sync.OutboxItem
import br.etc.victor.myhealthbridge.sync.OutboxSize
import br.etc.victor.myhealthbridge.sync.SyncCursor
import br.etc.victor.myhealthbridge.sync.SyncOutcome
import br.etc.victor.myhealthbridge.sync.SyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime

@Database(
    entities = [OutboxItemEntity::class, SyncCursorEntity::class, IngestionEndpointEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun sync(): SyncDao

    companion object {
        fun open(context: Context): SyncDatabase =
            Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "health-sync.db").build()
    }
}

/**
 * The Room side of the synchronization, opened once.
 *
 * Both stores share the database because [SyncStore.acceptPage] has to stage a page and move its
 * cursor in one transaction, and a second Room instance over the same file could not.
 */
class SyncStores(context: Context) {

    private val dao = SyncDatabase.open(context).sync()

    val sync: SyncStore = RoomSyncStore(dao)

    val endpoints: IngestionEndpointStore = RoomIngestionEndpointStore(dao)
}

internal class RoomSyncStore(private val dao: SyncDao) : SyncStore {

    override suspend fun cursor(category: HealthCategory): SyncCursor? = dao.cursor(category.id)?.toCursor()

    override fun observeCursors(): Flow<List<SyncCursor>> =
        dao.observeCursors().map { rows -> rows.mapNotNull(SyncCursorEntity::toCursor) }

    override suspend fun writeCursor(cursor: SyncCursor) = dao.writeCursor(cursor.toEntity())

    override suspend fun acceptPage(items: List<NewOutboxItem>, cursor: SyncCursor) =
        dao.acceptPage(items.map(NewOutboxItem::toEntity), cursor.toEntity())

    override suspend fun pending(recordType: String, limit: Int): List<OutboxItem> =
        dao.pending(recordType, limit).map { OutboxItem(id = it.id, envelopeJson = it.envelope) }

    override suspend fun pendingCount(): Int = dao.pendingCount()

    override fun observeOutbox(): Flow<OutboxSize> =
        combine(dao.observePendingCount(), dao.observeMappingPendencyCount(), ::OutboxSize)

    override suspend fun confirm(ids: List<Long>) = dao.delete(ids)

    override suspend fun reject(id: Long, codes: List<RejectionCode>) =
        dao.reject(id, codes.joinToString(",") { it.wireValue })
}

internal class RoomIngestionEndpointStore(private val dao: SyncDao) : IngestionEndpointStore {

    override fun observe(): Flow<IngestionEndpoint?> = dao.observeEndpoint().map { it?.toEndpoint() }

    override suspend fun read(): IngestionEndpoint? = dao.endpoint()?.toEndpoint()

    override suspend fun write(endpoint: IngestionEndpoint) = dao.writeEndpoint(
        IngestionEndpointEntity(baseUrl = endpoint.baseUrl, deviceToken = endpoint.deviceToken),
    )
}

private fun IngestionEndpointEntity.toEndpoint() = IngestionEndpoint(baseUrl, deviceToken)

private fun NewOutboxItem.toEntity() = OutboxItemEntity(
    categoryId = category.id,
    recordType = recordType,
    samsungUid = samsungUid,
    envelope = envelopeJson,
    enqueuedAt = enqueuedAt.toEpochMilli(),
)

/** A row naming a category this build no longer catalogs is skipped rather than guessed at. */
private fun SyncCursorEntity.toCursor(): SyncCursor? {
    val category = HealthCategory.byId(categoryId) ?: return null
    return SyncCursor(
        category = category,
        phase = enumValueOrNull<ImportPhase>(phase) ?: ImportPhase.NOT_STARTED,
        readFrom = runCatching { LocalDateTime.parse(readFrom) }.getOrDefault(HISTORY_FLOOR),
        initialLoad = window(),
        importedRecords = importedRecords,
        lastAttemptAt = lastAttemptAt?.let(Instant::ofEpochMilli),
        lastSuccessAt = lastSuccessAt?.let(Instant::ofEpochMilli),
        lastOutcome = lastOutcome?.let { enumValueOrNull<SyncOutcome>(it) },
    )
}

private fun SyncCursorEntity.window(): InitialLoadWindow? {
    val start = initialLoadStart?.let { LocalDateTime.parse(it) } ?: return null
    val end = initialLoadEnd?.let { LocalDateTime.parse(it) } ?: return null
    return InitialLoadWindow(start, end)
}

private fun SyncCursor.toEntity() = SyncCursorEntity(
    categoryId = category.id,
    phase = phase.name,
    readFrom = readFrom.toString(),
    initialLoadStart = initialLoad?.start?.toString(),
    initialLoadEnd = initialLoad?.end?.toString(),
    importedRecords = importedRecords,
    lastAttemptAt = lastAttemptAt?.toEpochMilli(),
    lastSuccessAt = lastSuccessAt?.toEpochMilli(),
    lastOutcome = lastOutcome?.name,
)

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
