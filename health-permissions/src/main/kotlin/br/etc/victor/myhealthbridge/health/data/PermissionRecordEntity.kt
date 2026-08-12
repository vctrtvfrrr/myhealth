package br.etc.victor.myhealthbridge.health.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "health_permission")
data class PermissionRecordEntity(
    @PrimaryKey @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "request_observed") val requestObserved: Boolean,
    @ColumnInfo(name = "grant_observed") val grantObserved: Boolean,
    @ColumnInfo(name = "granted") val granted: Boolean,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
)
