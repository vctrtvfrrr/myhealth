package br.etc.victor.myhealthbridge.sync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SyncDao {

    @Query("SELECT * FROM sync_cursor WHERE category_id = :categoryId")
    abstract suspend fun cursor(categoryId: String): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursor")
    abstract fun observeCursors(): Flow<List<SyncCursorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun writeCursor(cursor: SyncCursorEntity)

    /** The whole page and the cursor over it, or neither: a cursor must never outrun what is staged. */
    @Transaction
    open suspend fun acceptPage(items: List<OutboxItemEntity>, cursor: SyncCursorEntity) {
        insertAll(items)
        writeCursor(cursor)
    }

    @Query(
        "SELECT * FROM outbox_item WHERE record_type = :recordType AND rejection_codes IS NULL " +
            "ORDER BY id LIMIT :limit",
    )
    abstract suspend fun pending(recordType: String, limit: Int): List<OutboxItemEntity>

    @Query("SELECT COUNT(*) FROM outbox_item WHERE rejection_codes IS NULL")
    abstract suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM outbox_item WHERE rejection_codes IS NULL")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_item WHERE rejection_codes IS NOT NULL")
    abstract fun observeMappingPendencyCount(): Flow<Int>

    @Query("DELETE FROM outbox_item WHERE id IN (:ids)")
    abstract suspend fun delete(ids: List<Long>)

    @Query("UPDATE outbox_item SET rejection_codes = :codes WHERE id = :id")
    abstract suspend fun reject(id: Long, codes: String)

    @Query("SELECT * FROM ingestion_endpoint WHERE id = :id")
    abstract suspend fun endpoint(id: Int = IngestionEndpointEntity.SINGLETON): IngestionEndpointEntity?

    @Query("SELECT * FROM ingestion_endpoint WHERE id = :id")
    abstract fun observeEndpoint(id: Int = IngestionEndpointEntity.SINGLETON): Flow<IngestionEndpointEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun writeEndpoint(endpoint: IngestionEndpointEntity)

    @Insert
    protected abstract suspend fun insertAll(items: List<OutboxItemEntity>)
}
