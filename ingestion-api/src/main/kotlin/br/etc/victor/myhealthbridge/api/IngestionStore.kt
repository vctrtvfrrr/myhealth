package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.IngestionResponse
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RejectionCode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Writes one whole ingestion, or nothing at all.
 *
 * Every accepted observation, its identity, the ingestion itself and all positional results share a
 * single transaction, so a response can never describe a state that was not persisted. Semantic
 * rejections are part of that state: they are recorded, and they do not hold back the other items.
 */
class IngestionStore(
    private val dataSource: DataSource,
    private val timeoutSeconds: Int,
) {

    fun persist(
        deviceId: Long,
        contractVersion: Int,
        receivedAt: Instant,
        items: List<ItemValidation>,
    ): IngestionResponse {
        val ingestionId = UUID.randomUUID()

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val statements = Statements(connection, timeoutSeconds)
                statements.insertIngestion(ingestionId, deviceId, contractVersion, items.size, receivedAt)

                val results = items.mapIndexed { index, item ->
                    when (item) {
                        is ItemValidation.Invalid -> {
                            statements.insertRejection(ingestionId, index, item.codes)
                            ItemResult(index, ItemStatus.REJECTED, item.codes)
                        }

                        is ItemValidation.Valid -> {
                            val stored = statements.store(item.envelope, receivedAt)
                            statements.insertOutcome(ingestionId, index, stored.status, stored.versionId)
                            ItemResult(index, stored.status)
                        }
                    }
                }

                connection.commit()
                return IngestionResponse(ingestionId.toString(), results)
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private class StoredVersion(val versionId: Long, val status: ItemStatus)

    /**
     * The statements of a single transaction. They are not closed one by one: closing the connection
     * closes them, and the connection is always closed by the caller.
     */
    private class Statements(private val connection: Connection, private val timeoutSeconds: Int) {

        private val ingestion = prepare(
            """
            insert into ingestion (id, ingestion_device_id, contract_version, item_count, received_at)
            values (?, ?, ?, ?, ?)
            """,
        )

        private val identity = prepare(
            """
            insert into health_record_identity (record_type, samsung_uid) values (?, ?)
            on conflict (record_type, samsung_uid) do update set record_type = excluded.record_type
            returning id
            """,
        )

        private val version = prepare(
            """
            insert into observed_record_version (
                health_record_identity_id, content_digest, record_type, state,
                observed_at, observed_at_offset, period_start, period_start_offset,
                period_end, period_end_offset, mapper_version, envelope, first_received_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
            on conflict (health_record_identity_id, content_digest) do nothing
            returning id
            """,
        )

        private val existingVersion = prepare(
            "select id from observed_record_version where health_record_identity_id = ? and content_digest = ?",
        )

        private val item = prepare(
            """
            insert into ingestion_item (ingestion_id, position, status, observed_record_version_id, rejection_codes)
            values (?, ?, ?, ?, ?)
            """,
        )

        fun insertIngestion(id: UUID, deviceId: Long, contractVersion: Int, itemCount: Int, receivedAt: Instant) {
            ingestion.setObject(1, id)
            ingestion.setLong(2, deviceId)
            ingestion.setInt(3, contractVersion)
            ingestion.setInt(4, itemCount)
            ingestion.setUtc(5, receivedAt)
            ingestion.executeUpdate()
        }

        /**
         * Inserting first is what makes concurrency safe: the unique constraint decides who observed
         * the version initially, instead of a read that another transaction can invalidate.
         */
        fun store(envelope: ObservedEnvelope, receivedAt: Instant): StoredVersion {
            val identityId = identityOf(envelope)

            version.setLong(1, identityId)
            version.setBytes(2, envelope.digest)
            version.setString(3, envelope.recordType)
            version.setString(4, envelope.stateName)
            version.setUtc(5, envelope.observedAt)
            version.setString(6, envelope.envelope.observedAt.offset)
            version.setUtc(7, envelope.periodStart)
            version.setString(8, envelope.periodStartOffset)
            version.setUtc(9, envelope.periodEnd)
            version.setString(10, envelope.periodEndOffset)
            version.setString(11, envelope.envelope.mapperVersion)
            version.setString(12, envelope.canonicalJson)
            version.setUtc(13, receivedAt)
            version.executeQuery().use { inserted ->
                if (inserted.next()) return StoredVersion(inserted.getLong(1), ItemStatus.ACCEPTED)
            }

            existingVersion.setLong(1, identityId)
            existingVersion.setBytes(2, envelope.digest)
            existingVersion.executeQuery().use { existing ->
                check(existing.next()) { "the conflicting observed record version disappeared" }
                return StoredVersion(existing.getLong(1), ItemStatus.ALREADY_PRESENT)
            }
        }

        fun insertOutcome(ingestionId: UUID, position: Int, status: ItemStatus, versionId: Long) {
            item.setObject(1, ingestionId)
            item.setInt(2, position)
            item.setString(3, status.wireValue)
            item.setLong(4, versionId)
            item.setArray(5, connection.createArrayOf("text", emptyArray()))
            item.executeUpdate()
        }

        fun insertRejection(ingestionId: UUID, position: Int, codes: List<RejectionCode>) {
            item.setObject(1, ingestionId)
            item.setInt(2, position)
            item.setString(3, ItemStatus.REJECTED.wireValue)
            item.setNull(4, Types.BIGINT)
            item.setArray(5, connection.createArrayOf("text", codes.map { it.wireValue }.toTypedArray()))
            item.executeUpdate()
        }

        private fun identityOf(envelope: ObservedEnvelope): Long {
            identity.setString(1, envelope.recordType)
            identity.setString(2, envelope.envelope.samsungUid)
            identity.executeQuery().use { row ->
                check(row.next()) { "the health record identity upsert returned no row" }
                return row.getLong(1)
            }
        }

        private fun prepare(sql: String): PreparedStatement =
            connection.prepareStatement(sql.trimIndent()).apply { queryTimeout = timeoutSeconds }

        private fun PreparedStatement.setUtc(index: Int, instant: Instant?) =
            if (instant == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
            else setObject(index, instant.atOffset(ZoneOffset.UTC))
    }
}
