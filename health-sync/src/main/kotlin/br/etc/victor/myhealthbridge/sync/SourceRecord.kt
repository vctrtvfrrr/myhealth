package br.etc.victor.myhealthbridge.sync

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * One Health Record exactly as Samsung Health reported it, with no SDK type left in it.
 *
 * The adapter that talks to the SDK produces this; every mapper consumes it. Keeping the boundary
 * here is what lets the mappers be covered by synthetic fixtures instead of by a device.
 *
 * [fields] carries every public field of the record type under the name the source gives it, so a
 * mapper can preserve what it does not itself interpret.
 */
data class SourceRecord(
    val uid: String,
    val startTime: Instant,
    /** Absent on a point measurement, which Samsung Health reports without an end. */
    val endTime: Instant?,
    /** Absent when the source recorded no local context for the measurement. */
    val zoneOffset: ZoneOffset?,
    /** Absent when the source never reported a modification time for the record. */
    val updateTime: Instant?,
    val sourceAppId: String?,
    val sourceDeviceId: String?,
    val clientDataId: String?,
    val clientVersion: Int?,
    val fields: Map<String, SourceValue>,
)

/**
 * A value inside a [SourceRecord].
 *
 * Numbers are decimal rather than floating point because the canonical rendering is digested: a value
 * that went through a `Double` would be a different observation than the one Samsung Health reported.
 * An enum reaches this as [Text], under the constant name the source uses.
 */
sealed interface SourceValue {

    data class Number(val value: BigDecimal) : SourceValue

    data class Text(val value: String) : SourceValue

    data class Flag(val value: Boolean) : SourceValue

    /** A single nested entry, such as the swimming log inside an exercise session. */
    data class Nested(val fields: Map<String, SourceValue>) : SourceValue

    /** An ordered list of nested entries, such as the binned samples inside a heart rate record. */
    data class Series(val entries: List<Map<String, SourceValue>>) : SourceValue
}
