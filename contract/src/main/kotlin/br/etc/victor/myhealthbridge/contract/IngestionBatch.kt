package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.Serializable

/**
 * A homogeneous batch of Health Records.
 *
 * `recordType` lives only at the root, which is what makes the batch structurally homogeneous and
 * keeps it out of every envelope.
 */
@Serializable
data class IngestionBatch(
    val recordType: String,
    val items: List<HealthRecordEnvelope>,
    val contractVersion: Int = IngestionContract.CURRENT_VERSION,
)
