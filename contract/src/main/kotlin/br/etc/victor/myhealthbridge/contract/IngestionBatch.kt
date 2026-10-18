package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.Serializable

/**
 * A homogeneous batch of Health Records.
 *
 * `recordType` lives only at the root, which is what makes the batch structurally homogeneous and
 * keeps it out of every envelope.
 *
 * An [inventory] exists from version 2 on. A batch carrying one may carry no items at all, because what
 * it declares is an absence; a version 1 batch never carries one, and an API reading it concludes no
 * removal.
 */
@Serializable
data class IngestionBatch(
    val recordType: String,
    val items: List<HealthRecordEnvelope>,
    val contractVersion: Int = IngestionContract.CURRENT_VERSION,
    val inventory: RecordInventory? = null,
)

/**
 * Every Health Record Identity of the batch's record type the application saw over a stretch of local
 * time it read whole, declared only once the API confirmed every observation read there.
 *
 * It is what lets the API conclude a Source Removal nobody reported: an identity the API already
 * projects as present, whose period starts inside [interval] and which is not declared here, is gone
 * at the source. Declaring an identity outside the interval is harmless; leaving one inside it out is
 * a removal, which is why only a stretch read in full may be declared. See ADR 0022.
 *
 * [readAt] is when the read of that stretch began. A record the API learned of only after it, through
 * the changes feed, was not there to be seen, and its absence from the inventory proves nothing.
 */
@Serializable
data class RecordInventory(
    val interval: LocalInterval,
    val readAt: String,
    val samsungUids: List<String>,
)

/**
 * A stretch of local time, start included and end excluded, compared against the local start of a
 * record's period: the same clock a history read is filtered by.
 */
@Serializable
data class LocalInterval(
    val start: String,
    val end: String,
)
