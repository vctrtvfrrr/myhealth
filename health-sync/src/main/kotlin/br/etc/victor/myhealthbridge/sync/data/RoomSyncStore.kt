package br.etc.victor.myhealthbridge.sync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.health.HealthCategory
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
    version = 2,
    exportSchema = false,
)
abstract class SyncDatabase : RoomDatabase() {

    abstract fun sync(): SyncDao

    companion object {
        fun open(context: Context): SyncDatabase =
            Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "health-sync.db")
                .addMigrations(CHANGES_AND_OVERLAP)
                .build()

        /**
         * The two positions the changes read and the overlap re-read need.
         *
         * They are added rather than rebuilt: the outbox holds observations already read and not yet
         * confirmed by the API, and a destructive migration would drop them before anything stored them.
         *
         * A cursor that already exists starts reading changes from this moment, because that is the
         * truth: no build before this one asked Samsung Health what had changed. The overlap re-read is
         * left unrecorded on purpose, so the first run after the update takes one at once and covers the
         * week behind it; anything older is what a Full Reconciliation is for.
         */
        private val CHANGES_AND_OVERLAP = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_cursor ADD COLUMN changes_from INTEGER")
                db.execSQL("ALTER TABLE sync_cursor ADD COLUMN last_overlap_at INTEGER")
                db.execSQL("UPDATE sync_cursor SET changes_from = ?", arrayOf(System.currentTimeMillis()))
            }
        }
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

/**
 * A row naming a category this build no longer catalogs is skipped rather than guessed at.
 *
 * A row whose position cannot be read becomes an unrecoverable cursor instead of a default one: a
 * default would either skip history nothing would ever read again or claim progress that was never
 * made, and the run that meets this one re-reads everything rather than either.
 */
internal fun SyncCursorEntity.toCursor(): SyncCursor? {
    val category = HealthCategory.byId(categoryId) ?: return null
    return runCatching { readPosition(category) }
        .getOrElse { SyncCursor(category, unrecoverable = "unreadable_sync_cursor_row") }
}

private fun SyncCursorEntity.readPosition(category: HealthCategory) = SyncCursor(
    category = category,
    phase = enumValueOf<ImportPhase>(phase),
    readFrom = LocalDateTime.parse(readFrom),
    initialLoad = window(),
    changesFrom = changesFrom?.let(Instant::ofEpochMilli),
    lastOverlapAt = lastOverlapAt?.let(Instant::ofEpochMilli),
    importedRecords = importedRecords,
    lastAttemptAt = lastAttemptAt?.let(Instant::ofEpochMilli),
    lastSuccessAt = lastSuccessAt?.let(Instant::ofEpochMilli),
    // How a past run ended says nothing about where the import stands, so an outcome this build does
    // not know is dropped instead of making the whole cursor unrecoverable.
    lastOutcome = lastOutcome?.let { name -> SyncOutcome.entries.firstOrNull { it.name == name } },
)

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
    changesFrom = changesFrom?.toEpochMilli(),
    lastOverlapAt = lastOverlapAt?.toEpochMilli(),
    importedRecords = importedRecords,
    lastAttemptAt = lastAttemptAt?.toEpochMilli(),
    lastSuccessAt = lastSuccessAt?.toEpochMilli(),
    lastOutcome = lastOutcome?.name,
)
