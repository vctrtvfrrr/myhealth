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
    INVALID_BATCH(422, "The batch version or record type is structurally invalid"),
    INGESTION_TEMPORARILY_UNAVAILABLE(503, "The ingestion store is unavailable"),
    ;

    val wireValue: String get() = name.lowercase()
}
