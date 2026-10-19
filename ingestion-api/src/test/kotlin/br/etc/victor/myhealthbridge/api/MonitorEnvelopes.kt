package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic envelopes of what the watch monitors report, irregular heart rhythm notifications and sleep
 * apnea assessments, and of the daily energy score.
 *
 * Every value is invented: the repository must never carry fixtures derived from real measurements.
 */
object MonitorEnvelopes {

    const val IRREGULAR_HEART_RHYTHM_RECORD_TYPE = "irregular_heart_rhythm"
    const val IRREGULAR_HEART_RHYTHM_MAPPER_VERSION = "samsung-health-irregular-heart-rhythm/1"
    const val SLEEP_APNEA_RECORD_TYPE = "sleep_apnea"
    const val SLEEP_APNEA_MAPPER_VERSION = "samsung-health-sleep-apnea/1"
    const val ENERGY_SCORE_RECORD_TYPE = "energy_score"
    const val ENERGY_SCORE_MAPPER_VERSION = "samsung-health-energy-score/1"

    fun irregularHeartRhythm(
        samsungUid: String = "uid-irregular-heart-rhythm-1",
        status: String? = "DETECTED",
        mapperVersion: String = IRREGULAR_HEART_RHYTHM_MAPPER_VERSION,
        observedAt: String = "2026-08-10T04:25:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(
        samsungUid,
        mapperVersion,
        observedAt,
        start = "2026-08-10T04:20:00Z",
        end = "2026-08-10T04:20:00Z",
        sourceFields = { status?.let { put("status", it) } },
    ) {
        if (withPayload) putJsonObject("irregularHeartRhythm") { status?.let { put("status", it) } }
    }

    fun sleepApnea(
        samsungUid: String = "uid-sleep-apnea-1",
        result: String? = "NOT_DETECTED",
        mapperVersion: String = SLEEP_APNEA_MAPPER_VERSION,
        observedAt: String = "2026-08-10T09:45:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(
        samsungUid,
        mapperVersion,
        observedAt,
        start = "2026-08-10T02:00:00Z",
        end = "2026-08-10T09:30:00Z",
        sourceFields = { result?.let { put("result", it) } },
    ) {
        if (withPayload) putJsonObject("sleepApnea") { result?.let { put("result", it) } }
    }

    /** A score of the local day that starts at [day], which Samsung Health dates with no end. */
    fun energyScore(
        samsungUid: String = "uid-energy-score-1",
        score: String? = "82.0",
        day: String = "2026-08-10T03:00:00Z",
        offset: String = "-03:00",
        mapperVersion: String = ENERGY_SCORE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T10:00:00Z",
        withPayload: Boolean = true,
    ): JsonObject = present(
        samsungUid,
        mapperVersion,
        observedAt,
        start = day,
        end = day,
        offset = offset,
        sourceFields = { score?.toBigDecimalOrNull()?.let { put("total_score", it) } },
    ) {
        if (withPayload) putJsonObject("energyScore") { score?.let { put("score", measurement(it)) } }
    }

    private fun present(
        samsungUid: String,
        mapperVersion: String,
        observedAt: String,
        start: String,
        end: String,
        offset: String = "-03:00",
        sourceFields: JsonObjectBuilder.() -> Unit,
        normalized: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", offset)
        }
        put("mapperVersion", mapperVersion)
        putJsonObject("sourceProvenance") {
            putJsonObject("sourceApp") {
                put("kind", "known")
                put("id", "com.example.shealth")
            }
            putJsonObject("sourceDevice") {
                put("kind", "known")
                put("id", "device-1")
            }
        }
        putJsonObject("state") {
            put("kind", "present")
            putJsonObject("period") {
                putJsonObject("start") {
                    put("instant", start)
                    put("offset", offset)
                }
                putJsonObject("end") {
                    put("instant", end)
                    put("offset", offset)
                }
            }
            putJsonObject("sourcePayload") { putJsonObject("fields", sourceFields) }
            putJsonObject("normalizedPayload", normalized)
        }
    }

    /** A number when it is one, so a test can also state what a defective mapper would have put there. */
    private fun measurement(value: String): JsonPrimitive =
        value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
}
