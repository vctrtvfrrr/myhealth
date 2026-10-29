package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.IngestionResponse
import br.etc.victor.myhealthbridge.contract.InventoryResult
import br.etc.victor.myhealthbridge.contract.InventoryStatus
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ItemStatus
import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.math.ceil
import javax.sql.DataSource

/** The ingestion ran out of its configured budget, so nothing it did may be committed. */
class IngestionTimedOut(message: String) : RuntimeException(message)

/**
 * The budget of a single ingestion.
 *
 * The deadline covers the whole transaction rather than each statement, because coroutine cancellation
 * cannot interrupt a blocking driver and a per statement limit that restarts every time would let a
 * long batch commit well after the budget it was given.
 */
private class Deadline(seconds: Int) {

    private val expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())

    /** What is left of the budget, in whole seconds. Never zero, which a driver reads as "no limit". */
    fun remainingSeconds(): Int {
        val remaining = expiresAt - System.nanoTime()
        if (remaining <= 0) throw IngestionTimedOut("the ingestion budget expired before its next statement")
        return maxOf(1, ceil(remaining / NANOS_PER_SECOND).toInt())
    }

    fun checkNotExpired() {
        if (System.nanoTime() >= expiresAt) throw IngestionTimedOut("the ingestion budget expired before the commit")
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

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
        recordType: String,
        receivedAt: Instant,
        items: List<ItemValidation>,
        inventory: DeclaredInventory? = null,
    ): IngestionResponse = transaction(deviceId, contractVersion, recordType, receivedAt, items.size) { statements, ingestionId ->
        val observed = LinkedHashSet<Long>()
        val results = items.mapIndexed { index, item ->
            when (item) {
                is ItemValidation.Invalid -> {
                    statements.insertRejection(ingestionId, index, item.codes)
                    ItemResult(index, ItemStatus.REJECTED, item.codes)
                }

                is ItemValidation.Valid -> {
                    val stored = statements.store(item.envelope, receivedAt)
                    statements.insertOutcome(ingestionId, index, stored.status, stored.versionId)
                    observed += stored.identityId
                    ItemResult(index, stored.status)
                }
            }
        }

        // Projecting inside the same transaction is what keeps the Current Health Record from
        // ever describing less than the versions this response reports as durable.
        statements.project(observed)

        // After the projection, so that an observation delivered beside the inventory already
        // counts as known and as current when the absences are weighed.
        val concluded = inventory?.let { statements.conclude(ingestionId, it, receivedAt) }

        IngestionResponse(ingestionId.toString(), results, concluded)
    }

    /**
     * Writes a batch of Observed Snapshots on the same terms: one transaction for every observation, every
     * positional result and the Current Observed Snapshot of every snapshot the batch touched.
     */
    fun persistSnapshots(
        deviceId: Long,
        contractVersion: Int,
        snapshotType: String,
        receivedAt: Instant,
        items: List<SnapshotValidation>,
    ): IngestionResponse = transaction(deviceId, contractVersion, snapshotType, receivedAt, items.size) { statements, ingestionId ->
        val observed = LinkedHashSet<Long>()
        val results = items.mapIndexed { index, item ->
            when (item) {
                is SnapshotValidation.Invalid -> {
                    statements.insertRejection(ingestionId, index, item.codes)
                    ItemResult(index, ItemStatus.REJECTED, item.codes)
                }

                is SnapshotValidation.Valid -> {
                    val stored = statements.observe(item.observation, receivedAt)
                    statements.insertSnapshotOutcome(ingestionId, index, stored.status, stored.versionId)
                    observed += stored.identityId
                    ItemResult(index, stored.status)
                }
            }
        }
        statements.projectSnapshots(observed)
        IngestionResponse(ingestionId.toString(), results)
    }

    private fun transaction(
        deviceId: Long,
        contractVersion: Int,
        healthCategory: String,
        receivedAt: Instant,
        itemCount: Int,
        work: (Statements, UUID) -> IngestionResponse,
    ): IngestionResponse {
        val ingestionId = UUID.randomUUID()
        val deadline = Deadline(timeoutSeconds)

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val statements = Statements(connection, deadline)
                statements.insertIngestion(ingestionId, deviceId, contractVersion, healthCategory, itemCount, receivedAt)
                val response = work(statements, ingestionId)

                // Nothing is committed once the budget is gone: a 503 must never describe stored data.
                deadline.checkNotExpired()
                connection.commit()
                return response
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private class StoredVersion(val versionId: Long, val identityId: Long, val status: ItemStatus)

    /**
     * The statements of a single transaction. They are not closed one by one: closing the connection
     * closes them, and the connection is always closed by the caller.
     */
    private class Statements(private val connection: Connection, private val deadline: Deadline) {

        private val ingestion = prepare(
            """
            insert into ingestion (id, ingestion_device_id, contract_version, health_category, item_count, received_at)
            values (?, ?, ?, ?, ?, ?)
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

        private val projection = prepare("select project_current_health_record(?)")

        private val snapshotIdentity = prepare(
            """
            insert into observed_snapshot (snapshot_type, window_start, window_end) values (?, ?, ?)
            on conflict (
                snapshot_type,
                coalesce(window_start, '-infinity'::timestamp),
                coalesce(window_end, '-infinity'::timestamp)
            ) do update set snapshot_type = excluded.snapshot_type
            returning id
            """,
        )

        /**
         * The observation immediately before the one arriving, in observation time. One read at the same
         * instant counts as before it, so an identical resend meets itself.
         */
        private val predecessor = prepare(
            """
            select id, content_digest from snapshot_observation
            where observed_snapshot_id = ? and observed_at <= ?
            order by observed_at desc, first_received_at desc, id desc
            limit 1
            """,
        )

        private val snapshotObservation = prepare(
            """
            insert into snapshot_observation (
                observed_snapshot_id, content_digest, snapshot_type, observed_at, observed_at_offset,
                source_scope, mapper_version, empty, observation, first_received_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
            returning id
            """,
        )

        private val snapshotProjection = prepare("select project_current_observed_snapshot(?)")

        private val knownIdentities = prepare(
            "select count(*) from health_record_identity where record_type = ? and samsung_uid = any (?)",
        )

        /**
         * The Health Records the API holds as present, that start inside the interval in their own local
         * time, that it knew of before the inventory's stretch was read, and that the inventory leaves out.
         *
         * The local start is the UTC instant shifted by the offset it was recorded under, which is the
         * clock a history read is filtered by. `at time zone` cannot do the shift: it reads a numeric
         * offset with the POSIX sign, the opposite of ISO 8601.
         */
        private val absentees = prepare(
            """
            select v.envelope::text
            from health_record_identity i
            join current_health_record c on c.health_record_identity_id = i.id
            join observed_record_version v on v.id = c.observed_record_version_id
            where i.record_type = ?
              and v.state = 'present'
              and (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) >= ?
              and (v.period_start at time zone 'UTC') + cast(v.period_start_offset as interval) < ?
              and v.observed_at < ?
              and not (i.samsung_uid = any (?))
            order by i.id
            for update of i
            """,
        )

        private val inventoryRow = prepare(
            """
            insert into declared_inventory (
                ingestion_id, record_type, interval_start, interval_end, read_at, declared_count, status,
                removal_count
            )
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
        )

        private val concludedRemoval = prepare(
            "insert into concluded_removal (ingestion_id, observed_record_version_id) values (?, ?)",
        )

        private val item = prepare(
            """
            insert into ingestion_item (ingestion_id, position, status, observed_record_version_id, rejection_codes)
            values (?, ?, ?, ?, ?)
            """,
        )

        private val snapshotItem = prepare(
            """
            insert into ingestion_item (ingestion_id, position, status, snapshot_observation_id)
            values (?, ?, ?, ?)
            """,
        )

        fun insertIngestion(
            id: UUID,
            deviceId: Long,
            contractVersion: Int,
            healthCategory: String,
            itemCount: Int,
            receivedAt: Instant,
        ) {
            ingestion.setObject(1, id)
            ingestion.setLong(2, deviceId)
            ingestion.setInt(3, contractVersion)
            ingestion.setString(4, healthCategory)
            ingestion.setInt(5, itemCount)
            ingestion.setUtc(6, receivedAt)
            ingestion.executeWithinBudget()
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
            version.queryWithinBudget().use { inserted ->
                if (inserted.next()) return StoredVersion(inserted.getLong(1), identityId, ItemStatus.ACCEPTED)
            }

            existingVersion.setLong(1, identityId)
            existingVersion.setBytes(2, envelope.digest)
            existingVersion.queryWithinBudget().use { existing ->
                check(existing.next()) { "the conflicting observed record version disappeared" }
                return StoredVersion(existing.getLong(1), identityId, ItemStatus.ALREADY_PRESENT)
            }
        }

        /**
         * Stores a snapshot observation only when it differs from its predecessor in observation time;
         * otherwise the predecessor stands, as the first moment that content was seen. See ADR 0025.
         *
         * Comparing with a read is safe here because the identity upsert before it holds the snapshot's
         * row until this transaction ends, so no concurrent ingestion can slip an observation in between.
         */
        fun observe(observation: SnapshotEnvelope, receivedAt: Instant): StoredVersion {
            val snapshotId = snapshotOf(observation)

            predecessor.setLong(1, snapshotId)
            predecessor.setUtc(2, observation.observedAt)
            predecessor.queryWithinBudget().use { row ->
                if (row.next() && row.getBytes(2).contentEquals(observation.digest)) {
                    return StoredVersion(row.getLong(1), snapshotId, ItemStatus.ALREADY_PRESENT)
                }
            }

            snapshotObservation.setLong(1, snapshotId)
            snapshotObservation.setBytes(2, observation.digest)
            snapshotObservation.setString(3, observation.snapshotType)
            snapshotObservation.setUtc(4, observation.observedAt)
            snapshotObservation.setString(5, observation.observedAtOffset)
            snapshotObservation.setString(6, observation.sourceScope)
            snapshotObservation.setString(7, observation.mapperVersion)
            snapshotObservation.setBoolean(8, observation.empty)
            snapshotObservation.setString(9, observation.canonicalJson)
            snapshotObservation.setUtc(10, receivedAt)
            snapshotObservation.queryWithinBudget().use { inserted ->
                check(inserted.next()) { "the snapshot observation insert returned no row" }
                return StoredVersion(inserted.getLong(1), snapshotId, ItemStatus.ACCEPTED)
            }
        }

        /** Recomputes the Current Observed Snapshot of every snapshot this ingestion observed. */
        fun projectSnapshots(snapshotIds: Set<Long>) {
            if (snapshotIds.isEmpty()) return
            snapshotProjection.setArray(1, connection.createArrayOf("bigint", snapshotIds.toTypedArray()))
            snapshotProjection.queryWithinBudget().use { row ->
                check(row.next()) { "the snapshot projection returned no row" }
            }
        }

        private fun snapshotOf(observation: SnapshotEnvelope): Long {
            snapshotIdentity.setString(1, observation.snapshotType)
            snapshotIdentity.setObject(2, observation.windowStart, Types.TIMESTAMP)
            snapshotIdentity.setObject(3, observation.windowEnd, Types.TIMESTAMP)
            snapshotIdentity.queryWithinBudget().use { row ->
                check(row.next()) { "the observed snapshot upsert returned no row" }
                return row.getLong(1)
            }
        }

        /** Recomputes the Current Health Record of every identity this ingestion observed. */
        fun project(identityIds: Set<Long>) {
            if (identityIds.isEmpty()) return
            projection.setArray(1, connection.createArrayOf("bigint", identityIds.toTypedArray()))
            projection.queryWithinBudget().use { row ->
                check(row.next()) { "the projection returned no row" }
            }
        }

        /**
         * Concludes a Source Removal for every Health Record the inventory proves absent, as an Observed
         * Record Version of its own; nothing preserved is touched.
         *
         * An inventory naming an identity this API never stored is not trusted at all: its stretch was
         * declared before every observation in it was confirmed, so what it leaves out may simply not have
         * arrived yet.
         */
        fun conclude(ingestionId: UUID, inventory: DeclaredInventory, receivedAt: Instant): InventoryResult {
            if (inventory !is DeclaredInventory.Valid) {
                record(ingestionId, inventory, InventoryStatus.INVALID, removed = emptyList())
                return InventoryResult(InventoryStatus.INVALID)
            }
            if (knownCount(inventory) < inventory.samsungUids.size) {
                record(ingestionId, inventory, InventoryStatus.INCOMPLETE, removed = emptyList())
                return InventoryResult(InventoryStatus.INCOMPLETE)
            }

            val validator = ItemValidator(inventory.recordType)
            val removed = absenteesOf(inventory).mapNotNull { current ->
                val removal = validator.validate(concludedRemovalOf(current)) as? ItemValidation.Valid
                removal?.let { store(it.envelope, receivedAt) }
            }
            project(removed.mapTo(LinkedHashSet()) { it.identityId })
            record(ingestionId, inventory, InventoryStatus.CONCLUDED, removed)
            return InventoryResult(InventoryStatus.CONCLUDED, removed.count { it.status == ItemStatus.ACCEPTED })
        }

        private fun knownCount(inventory: DeclaredInventory.Valid): Int {
            knownIdentities.setString(1, inventory.recordType)
            knownIdentities.setArray(2, connection.createArrayOf("text", inventory.samsungUids.toTypedArray()))
            knownIdentities.queryWithinBudget().use { row ->
                check(row.next()) { "the identity count returned no row" }
                return row.getInt(1)
            }
        }

        private fun absenteesOf(inventory: DeclaredInventory.Valid): List<JsonObject> {
            absentees.setString(1, inventory.recordType)
            absentees.setObject(2, inventory.start)
            absentees.setObject(3, inventory.end)
            absentees.setUtc(4, inventory.readAt)
            absentees.setArray(5, connection.createArrayOf("text", inventory.samsungUids.toTypedArray()))
            absentees.queryWithinBudget().use { rows ->
                return buildList {
                    while (rows.next()) add(Json.parseToJsonElement(rows.getString(1)).jsonObject)
                }
            }
        }

        private fun record(
            ingestionId: UUID,
            inventory: DeclaredInventory,
            status: InventoryStatus,
            removed: List<StoredVersion>,
        ) {
            val valid = inventory as? DeclaredInventory.Valid
            inventoryRow.setObject(1, ingestionId)
            inventoryRow.setString(2, inventory.recordType)
            inventoryRow.setObject(3, valid?.start, Types.TIMESTAMP)
            inventoryRow.setObject(4, valid?.end, Types.TIMESTAMP)
            inventoryRow.setUtc(5, valid?.readAt)
            inventoryRow.setInt(6, valid?.samsungUids?.size ?: 0)
            inventoryRow.setString(7, status.wireValue)
            inventoryRow.setInt(8, removed.count { it.status == ItemStatus.ACCEPTED })
            inventoryRow.executeWithinBudget()

            removed.map { it.versionId }.distinct().forEach { versionId ->
                concludedRemoval.setObject(1, ingestionId)
                concludedRemoval.setLong(2, versionId)
                concludedRemoval.executeWithinBudget()
            }
        }

        fun insertOutcome(ingestionId: UUID, position: Int, status: ItemStatus, versionId: Long) {
            item.setObject(1, ingestionId)
            item.setInt(2, position)
            item.setString(3, status.wireValue)
            item.setLong(4, versionId)
            item.setArray(5, connection.createArrayOf("text", emptyArray()))
            item.executeWithinBudget()
        }

        fun insertSnapshotOutcome(ingestionId: UUID, position: Int, status: ItemStatus, observationId: Long) {
            snapshotItem.setObject(1, ingestionId)
            snapshotItem.setInt(2, position)
            snapshotItem.setString(3, status.wireValue)
            snapshotItem.setLong(4, observationId)
            snapshotItem.executeWithinBudget()
        }

        fun insertRejection(ingestionId: UUID, position: Int, codes: List<RejectionCode>) {
            item.setObject(1, ingestionId)
            item.setInt(2, position)
            item.setString(3, ItemStatus.REJECTED.wireValue)
            item.setNull(4, Types.BIGINT)
            item.setArray(5, connection.createArrayOf("text", codes.map { it.wireValue }.toTypedArray()))
            item.executeWithinBudget()
        }

        private fun identityOf(envelope: ObservedEnvelope): Long {
            identity.setString(1, envelope.recordType)
            identity.setString(2, envelope.envelope.samsungUid)
            identity.queryWithinBudget().use { row ->
                check(row.next()) { "the health record identity upsert returned no row" }
                return row.getLong(1)
            }
        }

        private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql.trimIndent())

        private fun PreparedStatement.executeWithinBudget() {
            queryTimeout = deadline.remainingSeconds()
            executeUpdate()
        }

        private fun PreparedStatement.queryWithinBudget(): ResultSet {
            queryTimeout = deadline.remainingSeconds()
            return executeQuery()
        }

        private fun PreparedStatement.setUtc(index: Int, instant: Instant?) =
            if (instant == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE)
            else setObject(index, instant.atOffset(ZoneOffset.UTC))
    }
}
