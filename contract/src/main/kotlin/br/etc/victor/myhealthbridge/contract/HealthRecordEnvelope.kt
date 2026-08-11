package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One observation of a single Health Record, as the Android app saw it.
 *
 * The record type is not repeated here: it comes from the batch root, together with this envelope's
 * Samsung UID it forms the Health Record Identity.
 */
@Serializable
data class HealthRecordEnvelope(
    val samsungUid: String,
    val observedAt: ZonedInstant,
    val mapperVersion: String,
    val sourceProvenance: SourceProvenance,
    val state: RecordState,
)

/**
 * An instant in UTC plus the offset that was in effect where it was produced.
 *
 * The offset is preserved separately because the local time an observation belongs to is part of
 * what was observed, and it cannot be recovered from the UTC instant alone.
 */
@Serializable
data class ZonedInstant(
    val instant: String,
    val offset: String,
)

/** A point measurement is expressed as a period whose start equals its end. */
@Serializable
data class SourcePeriod(
    val start: ZonedInstant,
    val end: ZonedInstant,
)

@Serializable
data class SourceProvenance(
    val sourceApp: SourceIdentity,
    val sourceDevice: SourceIdentity,
)

/**
 * The identity of an application or device as Samsung Health reports it.
 *
 * `unknown` is explicit so that "the source did not tell us" is preserved as an observation instead
 * of being indistinguishable from a field the app forgot to send.
 */
@Serializable
sealed interface SourceIdentity {

    @Serializable
    @SerialName("known")
    data class Known(val id: String) : SourceIdentity

    @Serializable
    @SerialName("unknown")
    data object Unknown : SourceIdentity
}

/**
 * What the observation says about the record: still there, or gone from the source.
 *
 * A [Removed] state carries no biometric content at all, and may omit the period when only the
 * identity is still known.
 */
@Serializable
sealed interface RecordState {

    @Serializable
    @SerialName("present")
    data class Present(
        val period: SourcePeriod,
        val sourcePayload: JsonObject,
        val normalizedPayload: JsonObject,
    ) : RecordState

    @Serializable
    @SerialName("removed")
    data class Removed(val period: SourcePeriod? = null) : RecordState
}
