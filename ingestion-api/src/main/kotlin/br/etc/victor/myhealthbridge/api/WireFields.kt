package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset

// The field rules every item of every batch kind is held to, whether it is an Observed Record Version
// or an Observed Snapshot: one reading of an instant, an offset and an opaque text.

internal class TimePoint(val instant: Instant, val wire: ZonedInstant)

/**
 * Opaque means opaque: the text is length checked and never trimmed, case folded or Unicode
 * normalized, because a Samsung identifier that differs only in whitespace is a different one.
 */
internal fun JsonObject.opaqueText(key: String, maxLength: Int): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotEmpty() && it.length <= maxLength }

internal fun JsonObject.zonedInstant(key: String, codes: MutableSet<RejectionCode>): TimePoint? {
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

internal fun canonical(instant: ZonedInstant, raw: JsonObject?): JsonObject = buildJsonObject {
    put("instant", instant.instant)
    put("offset", instant.offset)
    retainUnknown(raw, "instant", "offset")
}

internal fun JsonObject?.node(key: String): JsonObject? = this?.get(key) as? JsonObject

/**
 * Carries into the canonical document whatever this contract version could not interpret.
 *
 * Inside the digest rather than beside it: an unknown property is part of what the source reported,
 * and leaving it out would let two observations that differ only by it collapse onto one.
 */
internal fun JsonObjectBuilder.retainUnknown(raw: JsonObject?, vararg written: String) {
    raw?.forEach { (key, value) -> if (key !in written) put(key, value) }
}

private const val MAX_INSTANT_LENGTH = 64
private const val MAX_OFFSET_LENGTH = 6

private val ORIGINAL_OFFSET = Regex("""[+-]\d{2}:\d{2}""")

private fun parseUtcInstant(text: String): Instant? =
    if (!text.endsWith("Z")) null else runCatching { Instant.parse(text) }.getOrNull()

private fun isOriginalOffset(text: String): Boolean =
    ORIGINAL_OFFSET.matches(text) && runCatching { ZoneOffset.of(text) }.isSuccess
