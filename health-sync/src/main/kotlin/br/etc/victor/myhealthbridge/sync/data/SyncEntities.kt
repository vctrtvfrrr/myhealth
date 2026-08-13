package br.etc.victor.myhealthbridge.sync.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One envelope waiting for the API to confirm it.
 *
 * [rejectionCodes] null means deliverable; a non-null value is a mapping pendency, kept out of every
 * later batch. The index is what the sender selects on, over exactly that distinction.
 */
@Entity(
    tableName = "outbox_item",
    indices = [Index(value = ["record_type", "rejection_codes", "id"])],
)
data class OutboxItemEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "record_type") val recordType: String,
    @ColumnInfo(name = "samsung_uid") val samsungUid: String,
    @ColumnInfo(name = "envelope") val envelope: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "rejection_codes") val rejectionCodes: String? = null,
)

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "phase") val phase: String,
    @ColumnInfo(name = "read_from") val readFrom: String,
    @ColumnInfo(name = "initial_load_start") val initialLoadStart: String?,
    @ColumnInfo(name = "initial_load_end") val initialLoadEnd: String?,
    @ColumnInfo(name = "changes_from") val changesFrom: Long?,
    @ColumnInfo(name = "last_overlap_at") val lastOverlapAt: Long?,
    @ColumnInfo(name = "imported_records") val importedRecords: Long,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long?,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
    @ColumnInfo(name = "last_outcome") val lastOutcome: String?,
)

/** The single configured ingestion endpoint; the fixed key is what keeps it single. */
@Entity(tableName = "ingestion_endpoint")
data class IngestionEndpointEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "device_token") val deviceToken: String,
) {
    companion object {
        const val SINGLETON: Int = 1
    }
}
