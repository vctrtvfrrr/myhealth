package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one mapper of the irregular heart rhythm notifications the Samsung Health Monitor of a Galaxy Watch
 * reports, each one dated at the detection.
 */
object IrregularHeartRhythmMapper : RecordMapper {

    override val version: String = "samsung-health-irregular-heart-rhythm/1"

    private const val STATUS = "status"

    override val knownEnums: Map<String, Set<String>> = mapOf(STATUS to setOf(UNDEFINED, "DETECTED"))

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, monitored(record, "irregularHeartRhythm", STATUS))
}

/** The one mapper of the sleep apnea assessments the Samsung Health Monitor of a Galaxy Watch reports. */
object SleepApneaMapper : RecordMapper {

    override val version: String = "samsung-health-sleep-apnea/1"

    private const val RESULT = "result"

    override val knownEnums: Map<String, Set<String>> = mapOf(RESULT to setOf(UNDEFINED, "DETECTED", "NOT_DETECTED"))

    override fun map(record: SourceRecord): HealthRecordEnvelope =
        presentEnvelope(record, monitored(record, "sleepApnea", RESULT))
}

/**
 * The one mapper of the score Samsung Health computes for each local day.
 *
 * Samsung Health documents no scale for it, so it is carried without a unit it never claimed. An absent
 * score is emitted as absent rather than substituted, so the API rejects the item and it stays a mapping
 * pendency instead of becoming an invented score.
 */
object EnergyScoreMapper : RecordMapper {

    override val version: String = "samsung-health-energy-score/1"

    private const val TOTAL_SCORE = "total_score"

    override fun map(record: SourceRecord): HealthRecordEnvelope = presentEnvelope(
        record,
        buildJsonObject {
            val score = record.fields[TOTAL_SCORE] as? SourceValue.Number ?: return@buildJsonObject
            put("energyScore", buildJsonObject { put("score", JsonPrimitive(score.value)) })
        },
    )
}

/**
 * What a monitor reported, which is a fact by itself: the record is kept even when the source left its
 * constant `UNDEFINED`, the SDK's way of not stating it, and the constant is then absent.
 */
private fun monitored(record: SourceRecord, payloadKey: String, field: String): JsonObject = buildJsonObject {
    put(
        payloadKey,
        buildJsonObject {
            (record.fields[field] as? SourceValue.Text)?.value?.takeIf { it != UNDEFINED }?.let { put(field, it) }
        },
    )
}

private const val UNDEFINED = "UNDEFINED"
