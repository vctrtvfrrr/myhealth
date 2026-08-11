package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a single item could not become an Observed Record Version.
 *
 * The codes carry no received content, so a client may log them and route them to whoever owns the
 * mapper. Declaration order is the order the codes are reported in, which is the order the
 * validation stages run: identity, provenance, time, mapper, payload, unit.
 */
@Serializable
enum class RejectionCode {
    @SerialName("invalid_identity")
    INVALID_IDENTITY,

    @SerialName("invalid_provenance")
    INVALID_PROVENANCE,

    @SerialName("invalid_time_range")
    INVALID_TIME_RANGE,

    @SerialName("invalid_offset")
    INVALID_OFFSET,

    @SerialName("unsupported_record_type")
    UNSUPPORTED_RECORD_TYPE,

    @SerialName("unsupported_mapper")
    UNSUPPORTED_MAPPER,

    @SerialName("invalid_payload")
    INVALID_PAYLOAD,

    @SerialName("invalid_unit")
    INVALID_UNIT,
    ;

    val wireValue: String get() = name.lowercase()
}
