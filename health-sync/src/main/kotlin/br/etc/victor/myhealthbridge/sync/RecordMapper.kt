package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import br.etc.victor.myhealthbridge.contract.SourcePeriod
import br.etc.victor.myhealthbridge.contract.SourceProvenance
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset

/**
 * Turns one [SourceRecord] into the canonical envelope of a Health Record.
 *
 * The version is part of the contract, not of this build: the API selects the payload shape it
 * validates by record type and mapper version together, so changing what a mapper emits means
 * declaring a new version rather than editing this one in place.
 */
interface RecordMapper {

    val version: String

    /**
     * The enum valued fields this mapper interprets, each with the constants it knows.
     *
     * A constant outside a declared set is a gap in this mapper rather than a defect of the record:
     * the observation is still preserved exactly as the source reported it, and the gap is reported so
     * that whoever owns the mapper can teach it the value. A field absent from here is not an enum as
     * far as this mapper is concerned and is never audited, which is what keeps free text out of a
     * diagnostic.
     */
    val knownEnums: Map<String, Set<String>> get() = emptyMap()

    fun map(record: SourceRecord): HealthRecordEnvelope

    /**
     * The observation of a Source Removal, which every mapper renders the same way.
     *
     * A removal carries no content, so there is nothing type specific left to map: it is dated by the
     * change time the source reports, and its Source Provenance is unknown because a removal says only
     * that the record is gone, never which application removed it. The offset is UTC for the reason
     * [map] uses it when the source reported no local context — a change time carries none of its own.
     */
    fun removalOf(uid: String, changedAt: Instant): HealthRecordEnvelope = HealthRecordEnvelope(
        samsungUid = uid,
        observedAt = zoned(changedAt, ZoneOffset.UTC),
        mapperVersion = version,
        sourceProvenance = SourceProvenance(
            sourceApp = SourceIdentity.Unknown,
            sourceDevice = SourceIdentity.Unknown,
        ),
        state = RecordState.Removed(),
    )
}

/**
 * The observation of a record the source still reports, which every mapper renders the same way apart
 * from its normalized payload.
 *
 * Only the normalization is type specific: identity, provenance, temporal context and the preserved
 * source payload are the same promises whatever the record is, and two mappers stating them apart
 * would be two chances to state them differently.
 */
internal fun RecordMapper.presentEnvelope(
    record: SourceRecord,
    normalizedPayload: JsonObject,
): HealthRecordEnvelope {
    // The contract has no unknown offset, and the device's current one is not what the record was
    // produced under. A source that reported no local context leaves the instant standing alone.
    val offset = record.zoneOffset ?: ZoneOffset.UTC

    return HealthRecordEnvelope(
        samsungUid = record.uid,
        // The source's own update time, never the import clock: an unchanged record has to render
        // identically on every import, or each one would store another Observed Record Version of
        // something nobody changed. It is also what makes a later edit at the source win the
        // projection, since currency is decided by the largest observed time. A record the source
        // never reported a modification time for falls back to its start, which is stable for the
        // same reason.
        observedAt = zoned(record.updateTime ?: record.startTime, offset),
        mapperVersion = version,
        sourceProvenance = SourceProvenance(
            sourceApp = identity(record.sourceAppId),
            sourceDevice = identity(record.sourceDeviceId),
        ),
        state = RecordState.Present(
            // A point measurement is a period whose start equals its end, which is what the contract
            // says an end-less reading is.
            period = SourcePeriod(
                start = zoned(record.startTime, offset),
                end = zoned(record.endTime ?: record.startTime, offset),
            ),
            sourcePayload = sourcePayload(record),
            normalizedPayload = normalizedPayload,
        ),
    )
}

/**
 * The shape a value has to have for a diagnostic to quote it: what an SDK enum constant looks like.
 *
 * Declaring a field as an enum says which field is read, never what the source may put in it — and
 * the whole reason this reports at all is that the value was not one the mapper expected. So the
 * value is checked rather than trusted, and it is bounded: an unbounded one would also become a Room
 * primary key and an Android tag.
 */
private val ENUM_CONSTANT = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")

/**
 * The constants of [record] this mapper declared as enums but does not know, as `field=CONSTANT`.
 *
 * The constant is named because the field alone does not say what the mapper has to learn. A value
 * that does not look like a constant is reported as the bare field name instead: the maintainer still
 * learns where to look, and free text, a coordinate, a token or a separator this identity is built
 * out of is never persisted, shown, or allowed to collide with another identity.
 */
internal fun RecordMapper.unknownEnums(record: SourceRecord): List<String> =
    unknownEnumsIn(record.fields).distinct()

private fun RecordMapper.unknownEnumsIn(fields: Map<String, SourceValue>): List<String> =
    fields.flatMap { (name, value) ->
        when (value) {
            is SourceValue.Number, is SourceValue.Flag -> emptyList()
            is SourceValue.Nested -> unknownEnumsIn(value.fields)
            is SourceValue.Series -> value.entries.flatMap { unknownEnumsIn(it) }
            is SourceValue.Text -> {
                val known = knownEnums[name]
                when {
                    known == null || value.value in known -> emptyList()
                    ENUM_CONSTANT.matches(value.value) -> listOf("$name=${value.value}")
                    else -> listOf(name)
                }
            }
        }
    }

/**
 * The one mapper of Samsung Health heart rate records.
 *
 * The normalized payload carries only what the read model exposes; everything else the source
 * reported stays in the source payload, which is what preserves a field this version does not
 * interpret.
 */
object HeartRateMapper : RecordMapper {

    override val version: String = "samsung-health-heart-rate/1"

    private const val BEATS_PER_MINUTE = "/min"

    private const val HEART_RATE_FIELD = "heart_rate"

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, normalizedPayload(record))

    /**
     * An absent measurement is emitted as absent rather than substituted, so the API rejects the item
     * and it stays a mapping pendency instead of becoming an invented reading.
     */
    private fun normalizedPayload(record: SourceRecord): JsonObject = buildJsonObject {
        val beats = record.fields[HEART_RATE_FIELD] as? SourceValue.Number ?: return@buildJsonObject
        put(
            "heartRate",
            buildJsonObject {
                put("value", JsonPrimitive(beats.value))
                put("unit", BEATS_PER_MINUTE)
            },
        )
    }
}

/**
 * Everything the source reported, under the names it uses.
 *
 * The client identity sits under its own key instead of beside the fields, because a source field is
 * named by Samsung Health and a key added here could otherwise shadow one.
 */
internal fun sourcePayload(record: SourceRecord): JsonObject = buildJsonObject {
    put("fields", jsonOf(record.fields))
    if (record.clientDataId != null || record.clientVersion != null) {
        put(
            "client",
            buildJsonObject {
                record.clientDataId?.let { put("dataId", it) }
                record.clientVersion?.let { put("version", it) }
            },
        )
    }
}

private fun jsonOf(fields: Map<String, SourceValue>): JsonObject = buildJsonObject {
    fields.forEach { (name, value) ->
        when (value) {
            is SourceValue.Number -> put(name, JsonPrimitive(value.value))
            is SourceValue.Text -> put(name, value.value)
            is SourceValue.Flag -> put(name, value.value)
            is SourceValue.Nested -> put(name, jsonOf(value.fields))
            is SourceValue.Series -> put(name, JsonArray(value.entries.map(::jsonOf)))
        }
    }
}

internal fun identity(id: String?): SourceIdentity =
    if (id.isNullOrEmpty()) SourceIdentity.Unknown else SourceIdentity.Known(id)

/**
 * The instant in UTC beside the offset that was in effect where it was produced.
 *
 * The offset is written out rather than taken from [ZoneOffset.getId], which answers `Z` at UTC and
 * would be refused: the contract asks for the original offset in signed hours and minutes.
 */
internal fun zoned(instant: Instant, offset: ZoneOffset): ZonedInstant = ZonedInstant(
    instant = instant.toString(),
    offset = offsetText(offset),
)

private fun offsetText(offset: ZoneOffset): String {
    val totalMinutes = offset.totalSeconds / 60
    val sign = if (totalMinutes < 0) "-" else "+"
    val absolute = kotlin.math.abs(totalMinutes)
    return "%s%02d:%02d".format(sign, absolute / 60, absolute % 60)
}
