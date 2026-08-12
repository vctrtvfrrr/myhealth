package br.etc.victor.myhealthbridge.health.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class PermissionRecordDao {

    @Query("SELECT * FROM health_permission")
    abstract suspend fun all(): List<PermissionRecordEntity>

    @Transaction
    open suspend fun replaceAll(records: List<PermissionRecordEntity>) {
        deleteAll()
        insertAll(records)
    }

    @Query("DELETE FROM health_permission")
    protected abstract suspend fun deleteAll()

    @Insert
    protected abstract suspend fun insertAll(records: List<PermissionRecordEntity>)
}
