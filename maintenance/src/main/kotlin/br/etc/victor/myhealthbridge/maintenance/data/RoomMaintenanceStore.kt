package br.etc.victor.myhealthbridge.maintenance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.maintenance.IncidentIdentity
import br.etc.victor.myhealthbridge.maintenance.MaintenanceCode
import br.etc.victor.myhealthbridge.maintenance.MaintenanceIncident
import br.etc.victor.myhealthbridge.maintenance.MaintenanceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

@Database(entities = [MaintenanceIncidentEntity::class], version = 1, exportSchema = false)
abstract class MaintenanceDatabase : RoomDatabase() {

    abstract fun maintenance(): MaintenanceDao

    companion object {
        fun open(context: Context): MaintenanceDatabase =
            Room.databaseBuilder(context.applicationContext, MaintenanceDatabase::class.java, "maintenance.db")
                .build()
    }
}

fun maintenanceStore(context: Context): MaintenanceStore =
    RoomMaintenanceStore(MaintenanceDatabase.open(context).maintenance())

internal class RoomMaintenanceStore(private val dao: MaintenanceDao) : MaintenanceStore {

    override suspend fun read(identity: IncidentIdentity): MaintenanceIncident? =
        dao.incident(identity.key)?.toIncident()

    override suspend fun write(incident: MaintenanceIncident) = dao.write(incident.toEntity())

    override suspend fun transient(): List<MaintenanceIncident> = dao
        .incidentsOf(MaintenanceCode.entries.filter { it.transient }.map { it.id })
        .mapNotNull(MaintenanceIncidentEntity::toIncident)

    override suspend fun forget(identities: List<IncidentIdentity>) = dao.forget(identities.map { it.key })

    override fun observe(): Flow<List<MaintenanceIncident>> =
        dao.observeIncidents().map { rows -> rows.mapNotNull(MaintenanceIncidentEntity::toIncident) }
}

/**
 * A row this build cannot name is skipped rather than shown as itself.
 *
 * A code or a category no longer cataloged names a defect this version of the code does not have, so
 * there is nothing left for the Data Owner to act on.
 */
internal fun MaintenanceIncidentEntity.toIncident(): MaintenanceIncident? {
    val code = MaintenanceCode.byId(code) ?: return null
    val category = categoryId?.let { HealthCategory.byId(it) ?: return null }

    return MaintenanceIncident(
        identity = IncidentIdentity(code = code, category = category, detail = detail),
        firstSeenAt = Instant.ofEpochMilli(firstSeenAt),
        lastSeenAt = Instant.ofEpochMilli(lastSeenAt),
        occurrences = occurrences,
    )
}

private fun MaintenanceIncident.toEntity() = MaintenanceIncidentEntity(
    identity = identity.key,
    code = identity.code.id,
    categoryId = identity.category?.id,
    detail = identity.detail,
    firstSeenAt = firstSeenAt.toEpochMilli(),
    lastSeenAt = lastSeenAt.toEpochMilli(),
    occurrences = occurrences,
)
