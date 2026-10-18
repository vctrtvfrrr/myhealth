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
    val inventory: InventoryResult? = null,
)

/** What the API concluded from a declared inventory, present only when the batch carried one. */
@Serializable
data class InventoryResult(
    val status: InventoryStatus,
    val removals: Int = 0,
)

/**
 * Only [CONCLUDED] concluded anything. The other two remove nothing, and resending the same inventory
 * would not change the answer: the application drops it and declares the stretch again on its next read.
 */
@Serializable
enum class InventoryStatus {
    @SerialName("concluded")
    CONCLUDED,

    /**
     * The inventory names an identity the API never stored, so the observations of its stretch were
     * not all confirmed and its silence about the rest cannot be trusted.
     */
    @SerialName("incomplete")
    INCOMPLETE,

    /** The interval, the read instant or an identity is not something an inventory can declare. */
    @SerialName("invalid")
    INVALID,
    ;

    val wireValue: String get() = name.lowercase()
}

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
