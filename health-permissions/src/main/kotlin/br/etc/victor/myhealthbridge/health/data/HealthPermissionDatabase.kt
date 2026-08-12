package br.etc.victor.myhealthbridge.health.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PermissionRecordEntity::class], version = 1, exportSchema = false)
abstract class HealthPermissionDatabase : RoomDatabase() {

    abstract fun permissionRecords(): PermissionRecordDao

    companion object {
        fun open(context: Context): HealthPermissionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HealthPermissionDatabase::class.java,
                "health-permissions.db",
            ).build()
    }
}
