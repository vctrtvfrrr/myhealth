package br.etc.victor.myhealthbridge.health.data

import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.PermissionHistoryStore
import br.etc.victor.myhealthbridge.health.PermissionObservation
import br.etc.victor.myhealthbridge.health.PermissionRecord
import java.time.Instant

internal class RoomPermissionHistoryStore(private val dao: PermissionRecordDao) : PermissionHistoryStore {

    override suspend fun read(): PermissionObservation? {
        val rows = dao.all().mapNotNull { row -> HealthCategory.byId(row.categoryId)?.let { it to row } }
        if (rows.isEmpty()) return null
        return PermissionObservation(
            observedAt = Instant.ofEpochMilli(rows.maxOf { (_, row) -> row.observedAt }),
            records = rows.associate { (category, row) ->
                category to PermissionRecord(
                    category = category,
                    requestObserved = row.requestObserved,
                    grantObserved = row.grantObserved,
                    granted = row.granted,
                )
            },
        )
    }

    override suspend fun write(observation: PermissionObservation) {
        dao.replaceAll(
            observation.records.values.map { record ->
                PermissionRecordEntity(
                    categoryId = record.category.id,
                    requestObserved = record.requestObserved,
                    grantObserved = record.grantObserved,
                    granted = record.granted,
                    observedAt = observation.observedAt.toEpochMilli(),
                )
            },
        )
    }
}
