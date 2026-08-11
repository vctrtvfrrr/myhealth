package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.Serializable

/**
 * An RFC 9457 problem document for a request that produced no per-item results at all.
 *
 * It never echoes what was received, so the client has to rely on [BatchErrorCode] to decide what to
 * do next.
 */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
) {
    companion object {
        const val MEDIA_TYPE: String = "application/problem+json"

        fun of(error: BatchErrorCode): ProblemDetails = ProblemDetails(
            type = "urn:myhealthbridge:ingestion:${error.wireValue}",
            title = error.title,
            status = error.status,
            code = error.wireValue,
        )
    }
}

/** Why a whole request failed before any item could be evaluated. */
enum class BatchErrorCode(val status: Int, val title: String) {
    INVALID_DEVICE_TOKEN(401, "The ingestion device token is missing, unknown or revoked"),
    UNSUPPORTED_MEDIA_TYPE(415, "The request body must be uncompressed ${IngestionContract.MEDIA_TYPE}"),
    BATCH_TOO_LARGE(413, "The request body exceeds the configured byte limit"),
    INVALID_REQUEST(400, "The request body is not a batch document"),
    TOO_MANY_ITEMS(422, "The batch carries more items than the configured limit"),
    INVALID_BATCH(422, "The batch record type is structurally invalid or the version is not an integer"),
    // Not 426: RFC 9110 makes the Upgrade header mandatory there and scopes it to the HTTP protocol
    // itself, not to the version of a payload. These are well formed documents this API cannot
    // process, which is what 422 means; the stable code is what tells the two directions apart.
    CONTRACT_VERSION_TOO_OLD(422, "The declared contract version is below the minimum this API supports"),
    CONTRACT_VERSION_TOO_NEW(422, "The declared contract version is above what this API supports"),
    INGESTION_TEMPORARILY_UNAVAILABLE(503, "The ingestion store is unavailable"),
    ;

    val wireValue: String get() = name.lowercase()
}
