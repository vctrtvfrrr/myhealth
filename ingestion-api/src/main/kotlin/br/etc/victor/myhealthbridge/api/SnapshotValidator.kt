package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.contract.SourceScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeSet

sealed interface SnapshotValidation {
    data class Valid(val observation: SnapshotEnvelope) : SnapshotValidation
    data class Invalid(val codes: List<RejectionCode>) : SnapshotValidation
}

/**
 * A validated snapshot observation, with everything persistence needs to index it.
 *
 * Two renderings are kept apart on purpose. [canonicalJson] is the whole observation as preserved,
 * reading instant included. [digest] is taken over the same document without it, because the moment an
 * observation was read says nothing about what was observed: two readings of the same content are the
 * same, which is what lets the API store only a change. See ADR 0025.
 */
class SnapshotEnvelope(
    val snapshotType: String,
    val windowStart: LocalDateTime?,
    val windowEnd: LocalDateTime?,
    val observedAt: Instant,
    val observedAtOffset: String,
    val sourceScope: String,
    val mapperVersion: String,
    val empty: Boolean,
    val canonicalJson: String,
    contentJson: String,
) {
    val digest: ByteArray = CanonicalJson.digest(contentJson)
}

/**
 * Turns one raw item of a snapshot batch into either an observation or the ordered reasons it cannot
 * become one, in the stage order an Observed Record Version is checked in: identity, provenance, time,
 * mapper, payload.
 *
 * The window is the identity, so a window that does not have the shape its kind is identified by is an
 * identity failure: storing it would create a snapshot no reading of the source would ever observe again.
 */
class SnapshotValidator(private val snapshotType: String) {

    private val kind = SnapshotKinds.of(snapshotType)

    fun validate(raw: JsonElement): SnapshotValidation {
        val item = raw as? JsonObject ?: return SnapshotValidation.Invalid(listOf(RejectionCode.INVALID_IDENTITY))
        val codes = TreeSet<RejectionCode>()

        val window = item.window(codes)

        val sourceScope = item.opaqueText("sourceScope", MAX_SCOPE_LENGTH)
            ?.takeIf { scope -> SourceScope.entries.any { it.wireValue == scope } }
        if (sourceScope == null) codes += RejectionCode.INVALID_PROVENANCE

        val observedAt = item.zonedInstant("observedAt", codes)

        val mapperVersion = item.opaqueText("mapperVersion", MAX_MAPPER_VERSION_LENGTH)
        val contentRule = resolveMapper(mapperVersion, codes)

        val content = item.content(contentRule, codes)

        if (codes.isNotEmpty()) return SnapshotValidation.Invalid(codes.toList())

        val observed = buildJsonObject {
            put("snapshotType", snapshotType)
            put("window", window?.canonical(item.node("window")) ?: JsonNull)
            put("sourceScope", sourceScope)
            put("mapperVersion", mapperVersion)
            put("content", content ?: JsonNull)
            retainUnknown(item, "snapshotType", "window", "sourceScope", "mapperVersion", "content", "observedAt")
        }
        val preserved = JsonObject(observed + ("observedAt" to canonical(observedAt!!.wire, item.node("observedAt"))))

        val contentJson = CanonicalJson.renderOrNull(observed)
        val canonicalJson = CanonicalJson.renderOrNull(preserved)
        if (contentJson == null || canonicalJson == null) {
            return SnapshotValidation.Invalid(listOf(RejectionCode.INVALID_PAYLOAD))
        }

        return SnapshotValidation.Valid(
            SnapshotEnvelope(
                snapshotType = snapshotType,
                windowStart = window?.start,
                windowEnd = window?.end,
                observedAt = observedAt.instant,
                observedAtOffset = observedAt.wire.offset,
                sourceScope = sourceScope!!,
                mapperVersion = mapperVersion!!,
                empty = content == null,
                canonicalJson = canonicalJson,
                contentJson = contentJson,
            ),
        )
    }

    private fun JsonObject.window(codes: MutableSet<RejectionCode>): Window? {
        val node = this["window"]?.takeIf { it != JsonNull }
        if (node == null) {
            if (kind != null) codes += RejectionCode.INVALID_IDENTITY
            return null
        }
        val window = (node as? JsonObject)?.let { interval ->
            val start = interval.localTime("start")
            val end = interval.localTime("end")
            if (start == null || end == null || !start.isBefore(end)) null else Window(start, end)
        }
        if (window == null || (kind != null && !kind.window.fits(window.start, window.end))) {
            codes += RejectionCode.INVALID_IDENTITY
            return null
        }
        return window
    }

    private fun JsonObject.localTime(key: String): LocalDateTime? =
        opaqueText(key, MAX_LOCAL_TIME_LENGTH)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun resolveMapper(
        mapperVersion: String?,
        codes: MutableSet<RejectionCode>,
    ): ((JsonObject) -> List<RejectionCode>)? {
        if (kind == null) {
            codes += RejectionCode.UNSUPPORTED_RECORD_TYPE
            return null
        }
        val rule = mapperVersion?.let { kind.contents[it] }
        if (rule == null) codes += RejectionCode.UNSUPPORTED_MAPPER
        return rule
    }

    /** Null for an empty window, whether the content was left out or written as an explicit null. */
    private fun JsonObject.content(
        rule: ((JsonObject) -> List<RejectionCode>)?,
        codes: MutableSet<RejectionCode>,
    ): JsonObject? {
        val node = this["content"]?.takeIf { it != JsonNull } ?: return null
        val content = node as? JsonObject ?: run {
            codes += RejectionCode.INVALID_PAYLOAD
            return null
        }
        rule?.let { codes += it(content) }
        return content
    }

    /**
     * The window as a local time written one way only, so that the same hour sent with or without its
     * seconds is one snapshot with one digest rather than two renderings of it.
     */
    private class Window(val start: LocalDateTime, val end: LocalDateTime) {
        fun canonical(raw: JsonObject?): JsonObject = buildJsonObject {
            put("start", start.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            put("end", end.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            retainUnknown(raw, "start", "end")
        }
    }

    private companion object {
        const val MAX_SCOPE_LENGTH = 32
        const val MAX_MAPPER_VERSION_LENGTH = 128
        const val MAX_LOCAL_TIME_LENGTH = 32
    }
}
