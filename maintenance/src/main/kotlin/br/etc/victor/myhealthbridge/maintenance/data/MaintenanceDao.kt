package br.etc.victor.myhealthbridge.maintenance.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MaintenanceDao {

    @Query("SELECT * FROM maintenance_incident WHERE identity = :identity")
    suspend fun incident(identity: String): MaintenanceIncidentEntity?

    @Query("SELECT * FROM maintenance_incident WHERE code IN (:codes)")
    suspend fun incidentsOf(codes: List<String>): List<MaintenanceIncidentEntity>

    /** Most recently met first: what is happening now is what the Data Owner opened the screen for. */
    @Query("SELECT * FROM maintenance_incident ORDER BY last_seen_at DESC")
    fun observeIncidents(): Flow<List<MaintenanceIncidentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(incident: MaintenanceIncidentEntity)

    @Query("DELETE FROM maintenance_incident WHERE identity IN (:identities)")
    suspend fun forget(identities: List<String>)
}
