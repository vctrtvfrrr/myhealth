package br.etc.victor.myhealthbridge.maintenance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One open Maintenance Incident.
 *
 * The identity is the primary key, so meeting the same condition again updates this row instead of
 * adding one: the table holds what is wrong, not a log of every time it happened.
 */
@Entity(tableName = "maintenance_incident")
data class MaintenanceIncidentEntity(
    @PrimaryKey @ColumnInfo(name = "identity") val identity: String,
    @ColumnInfo(name = "code") val code: String,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "detail") val detail: String?,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "occurrences") val occurrences: Long,
)
