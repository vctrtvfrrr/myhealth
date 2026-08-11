package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The answer to an accepted batch: one result per submitted position, in the submitted order.
 *
 * Nothing the client sent is echoed back, so the response never carries health content or source
 * identifiers.
 */
@Serializable
data class IngestionResponse(
    val ingestionId: String,
    val results: List<ItemResult>,
)

@Serializable
data class ItemResult(
    val index: Int,
    val status: ItemStatus,
    val codes: List<RejectionCode>? = null,
)

/**
 * `ACCEPTED` and `ALREADY_PRESENT` both mean the observation is durably stored, which is what lets a
 * client drop the item from its outbox after a retry.
 */
@Serializable
enum class ItemStatus {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("already_present")
    ALREADY_PRESENT,

    @SerialName("rejected")
    REJECTED,
    ;

    val wireValue: String get() = name.lowercase()
}
