package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.Serializable

/**
 * The set of Ingestion Contract Versions the API accepts, as the API publishes it.
 *
 * The current version is deliberately absent: no client behaves differently for knowing it, and a
 * third identical number would only invite each reader to guess at a distinction that is not there.
 */
@Serializable
data class SupportedContractRange(
    val minimumVersion: Int,
    val recommendedVersion: Int,
) {
    companion object {
        val PUBLISHED = SupportedContractRange(
            minimumVersion = IngestionContract.MINIMUM_VERSION,
            recommendedVersion = IngestionContract.RECOMMENDED_VERSION,
        )
    }
}
