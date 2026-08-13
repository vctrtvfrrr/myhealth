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

    fun map(record: SourceRecord): HealthRecordEnvelope
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

    override fun map(record: SourceRecord): HealthRecordEnvelope {
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
                // A point measurement is a period whose start equals its end, which is what the
                // contract says an end-less reading is.
                period = SourcePeriod(
                    start = zoned(record.startTime, offset),
                    end = zoned(record.endTime ?: record.startTime, offset),
                ),
                sourcePayload = sourcePayload(record),
                normalizedPayload = normalizedPayload(record),
            ),
        )
    }

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
