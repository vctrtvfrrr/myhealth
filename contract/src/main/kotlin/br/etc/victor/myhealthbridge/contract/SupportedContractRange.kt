package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.Serializable

/**
 * The set of Ingestion Contract Versions the API accepts, as the API publishes it.
 *
 * Both bounds are published, not only the minimum. Without the maximum, a client newer than this API
 * cannot tell "it accepts nothing above 1" from "it accepts my version but recommends an older one",
 * so it could not reach the very diagnosis this document exists to give it before it sends anything.
 * The recommended version is advice inside the bounds rather than a third bound.
 */
@Serializable
data class SupportedContractRange(
    val minimumVersion: Int,
    val maximumVersion: Int,
    val recommendedVersion: Int,
) {
    companion object {
        val PUBLISHED = SupportedContractRange(
            minimumVersion = IngestionContract.MINIMUM_VERSION,
            maximumVersion = IngestionContract.CURRENT_VERSION,
            recommendedVersion = IngestionContract.RECOMMENDED_VERSION,
        )
    }
}
