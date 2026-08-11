package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import br.etc.victor.myhealthbridge.contract.SourcePeriod
import br.etc.victor.myhealthbridge.contract.SourceProvenance
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant
import java.time.ZoneOffset
import java.util.TreeSet

sealed interface ItemValidation {
    data class Valid(val envelope: ObservedEnvelope) : ItemValidation
    data class Invalid(val codes: List<RejectionCode>) : ItemValidation
}

/**
 * A validated observation, together with everything persistence needs to index it.
 *
 * The canonical rendering and its digest are computed here, from the envelope alone, so that the
 * transport version, the position in the batch and the arrival time cannot influence what counts as
 * the same Observed Record Version.
 */
class ObservedEnvelope(
    val recordType: String,
    val envelope: HealthRecordEnvelope,
    val observedAt: Instant,
    val periodStart: Instant?,
    val periodEnd: Instant?,
) {
    val canonicalJson: String = CanonicalJson.render(
        JsonObject(
            (IngestionContract.json.encodeToJsonElement(envelope) as JsonObject) +
                ("recordType" to JsonPrimitive(recordType)),
        ),
    )

    val digest: ByteArray = CanonicalJson.digest(canonicalJson)

    val stateName: String = when (envelope.state) {
        is RecordState.Present -> "present"
        is RecordState.Removed -> "removed"
    }

    private val period: SourcePeriod? = when (val state = envelope.state) {
        is RecordState.Present -> state.period
        is RecordState.Removed -> state.period
    }

    val periodStartOffset: String? = period?.start?.offset
    val periodEndOffset: String? = period?.end?.offset
}

/**
 * Turns one raw item into either an observation or an ordered list of reasons it cannot become one.
 *
 * Items are checked field by field rather than handed to a decoder, because a client fixing a mapper
 * needs every reason its item failed, not only the first one a decoder happened to hit. The stages
 * run in the documented order — identity, provenance, time, mapper, payload, unit — and the codes are
 * reported in that same order.
 */
class ItemValidator(private val recordType: String) {

    fun validate(raw: JsonElement): ItemValidation {
        val item = raw as? JsonObject ?: return ItemValidation.Invalid(listOf(RejectionCode.INVALID_IDENTITY))
        val codes = TreeSet<RejectionCode>()

        val samsungUid = item.opaqueText("samsungUid", MAX_UID_LENGTH)
        if (samsungUid == null) codes += RejectionCode.INVALID_IDENTITY

        val provenance = item.provenance(codes)

        val state = item["state"] as? JsonObject
        val stateKind = state?.opaqueText("kind", MAX_KIND_LENGTH)

        val observedAt = item.zonedInstant("observedAt", codes)
        val period = state?.period(stateKind, codes)

        val mapperVersion = item.opaqueText("mapperVersion", MAX_MAPPER_VERSION_LENGTH)
        val mapper = resolveMapper(mapperVersion, codes)

        val payloads = state.payloads(stateKind, mapper, codes)

        if (codes.isNotEmpty()) return ItemValidation.Invalid(codes.toList())

        val recordState = when (stateKind) {
            PRESENT -> RecordState.Present(
                period = period!!.wire,
                sourcePayload = payloads!!.source,
                normalizedPayload = payloads.normalized,
            )

            else -> RecordState.Removed(period = period?.wire)
        }

        return ItemValidation.Valid(
            ObservedEnvelope(
                recordType = recordType,
                envelope = HealthRecordEnvelope(
                    samsungUid = samsungUid!!,
                    observedAt = observedAt!!.wire,
                    mapperVersion = mapperVersion!!,
                    sourceProvenance = provenance!!,
                    state = recordState,
                ),
                observedAt = observedAt.instant,
                periodStart = period?.start,
                periodEnd = period?.end,
            ),
        )
    }

    private fun resolveMapper(
        mapperVersion: String?,
        codes: MutableSet<RejectionCode>,
    ): ((JsonObject) -> List<RejectionCode>)? {
        if (!NormalizedPayloads.supports(recordType)) {
            codes += RejectionCode.UNSUPPORTED_RECORD_TYPE
            return null
        }
        if (mapperVersion == null) {
            codes += RejectionCode.UNSUPPORTED_MAPPER
            return null
        }
        val mapper = NormalizedPayloads.validatorFor(recordType, mapperVersion)
        if (mapper == null) codes += RejectionCode.UNSUPPORTED_MAPPER
        return mapper
    }

    private fun JsonObject?.payloads(
        stateKind: String?,
        mapper: ((JsonObject) -> List<RejectionCode>)?,
        codes: MutableSet<RejectionCode>,
    ): Payloads? {
        if (this == null || stateKind !in KNOWN_STATES) {
            codes += RejectionCode.INVALID_PAYLOAD
            return null
        }

        if (stateKind == REMOVED) {
            // A removal describes an absence, so carrying biometric content in one is a mapper bug.
            if (SOURCE_PAYLOAD in this || NORMALIZED_PAYLOAD in this) codes += RejectionCode.INVALID_PAYLOAD
            return null
        }

        val source = this[SOURCE_PAYLOAD] as? JsonObject
        val normalized = this[NORMALIZED_PAYLOAD] as? JsonObject
        if (source == null || normalized == null) {
            codes += RejectionCode.INVALID_PAYLOAD
            return null
        }

        mapper?.let { codes += it(normalized) }
        return Payloads(source, normalized)
    }

    private fun JsonObject.provenance(codes: MutableSet<RejectionCode>): SourceProvenance? {
        val node = this["sourceProvenance"] as? JsonObject
        val app = node?.sourceIdentity("sourceApp")
        val device = node?.sourceIdentity("sourceDevice")
        if (app == null || device == null) {
            codes += RejectionCode.INVALID_PROVENANCE
            return null
        }
        return SourceProvenance(sourceApp = app, sourceDevice = device)
    }

    private fun JsonObject.sourceIdentity(key: String): SourceIdentity? {
        val node = this[key] as? JsonObject ?: return null
        return when (node.opaqueText("kind", MAX_KIND_LENGTH)) {
            "known" -> node.opaqueText("id", MAX_SOURCE_ID_LENGTH)?.let { SourceIdentity.Known(it) }
            "unknown" -> SourceIdentity.Unknown
            else -> null
        }
    }

    private fun JsonObject.period(stateKind: String?, codes: MutableSet<RejectionCode>): Period? {
        val node = this["period"]
        if (node == null) {
            if (stateKind == PRESENT) codes += RejectionCode.INVALID_TIME_RANGE
            return null
        }
        val period = node as? JsonObject ?: run {
            codes += RejectionCode.INVALID_TIME_RANGE
            return null
        }

        val start = period.zonedInstant("start", codes)
        val end = period.zonedInstant("end", codes)
        if (start == null || end == null) return null
        if (start.instant > end.instant) {
            codes += RejectionCode.INVALID_TIME_RANGE
            return null
        }
        return Period(start.instant, end.instant, SourcePeriod(start.wire, end.wire))
    }

    private fun JsonObject.zonedInstant(key: String, codes: MutableSet<RejectionCode>): TimePoint? {
        val node = this[key] as? JsonObject ?: run {
            codes += RejectionCode.INVALID_TIME_RANGE
            return null
        }

        val instantText = node.opaqueText("instant", MAX_INSTANT_LENGTH)
        val instant = instantText?.let(::parseUtcInstant)
        if (instant == null) codes += RejectionCode.INVALID_TIME_RANGE

        val offsetText = node.opaqueText("offset", MAX_OFFSET_LENGTH)?.takeIf(::isOriginalOffset)
        if (offsetText == null) codes += RejectionCode.INVALID_OFFSET

        if (instant == null || offsetText == null) return null
        return TimePoint(instant, ZonedInstant(instantText, offsetText))
    }

    /**
     * Opaque means opaque: the text is length checked and never trimmed, case folded or Unicode
     * normalized, because a Samsung identifier that differs only in whitespace is a different one.
     */
    private fun JsonObject.opaqueText(key: String, maxLength: Int): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotEmpty() && it.length <= maxLength }

    private class Payloads(val source: JsonObject, val normalized: JsonObject)

    private class TimePoint(val instant: Instant, val wire: ZonedInstant)

    private class Period(val start: Instant, val end: Instant, val wire: SourcePeriod)

    private companion object {
        const val PRESENT = "present"
        const val REMOVED = "removed"
        const val SOURCE_PAYLOAD = "sourcePayload"
        const val NORMALIZED_PAYLOAD = "normalizedPayload"

        val KNOWN_STATES = setOf(PRESENT, REMOVED)

        const val MAX_UID_LENGTH = 256
        const val MAX_SOURCE_ID_LENGTH = 256
        const val MAX_MAPPER_VERSION_LENGTH = 128
        const val MAX_KIND_LENGTH = 32
        const val MAX_INSTANT_LENGTH = 64
        const val MAX_OFFSET_LENGTH = 6

        val ORIGINAL_OFFSET = Regex("""[+-]\d{2}:\d{2}""")

        fun parseUtcInstant(text: String): Instant? =
            if (!text.endsWith("Z")) null else runCatching { Instant.parse(text) }.getOrNull()

        fun isOriginalOffset(text: String): Boolean =
            ORIGINAL_OFFSET.matches(text) && runCatching { ZoneOffset.of(text) }.isSuccess
    }
}
