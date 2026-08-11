package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic heart rate envelopes.
 *
 * Every value is invented: the repository must never carry fixtures derived from real measurements.
 */
object Envelopes {

    const val RECORD_TYPE = "heart_rate"
    const val MAPPER_VERSION = "samsung-health-heart-rate/1"

    fun heartRate(
        samsungUid: String = "uid-1",
        observedAt: String = "2026-08-10T22:00:00Z",
        observedOffset: String = "-03:00",
        start: String = "2026-08-10T21:59:00Z",
        end: String = "2026-08-10T21:59:00Z",
        offset: String = "-03:00",
        beatsPerMinute: String = "72",
        unit: String = "/min",
        mapperVersion: String = MAPPER_VERSION,
        sourceApp: String? = "com.example.shealth",
        sourceDevice: String? = "device-1",
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", observedOffset)
        }
        put("mapperVersion", mapperVersion)
        putJsonObject("sourceProvenance") {
            putJsonObject("sourceApp") { identity(sourceApp) }
            putJsonObject("sourceDevice") { identity(sourceDevice) }
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
            putJsonObject("sourcePayload") {
                put("heart_rate", beatsPerMinute)
                put("com.samsung.health.heart_rate.unit", unit)
            }
            putJsonObject("normalizedPayload") {
                putJsonObject("heartRate") {
                    put("value", beatsPerMinute.toBigDecimal())
                    put("unit", unit)
                }
            }
        }
    }

    fun removal(
        samsungUid: String = "uid-1",
        observedAt: String = "2026-08-11T09:00:00Z",
        withPeriod: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", "-03:00")
        }
        put("mapperVersion", MAPPER_VERSION)
        putJsonObject("sourceProvenance") {
            putJsonObject("sourceApp") { identity("com.example.shealth") }
            putJsonObject("sourceDevice") { identity(null) }
        }
        putJsonObject("state") {
            put("kind", "removed")
            if (withPeriod) {
                putJsonObject("period") {
                    putJsonObject("start") {
                        put("instant", "2026-08-10T21:59:00Z")
                        put("offset", "-03:00")
                    }
                    putJsonObject("end") {
                        put("instant", "2026-08-10T21:59:00Z")
                        put("offset", "-03:00")
                    }
                }
            }
        }
    }

    fun batch(recordType: String = RECORD_TYPE, contractVersion: Int = 1, items: List<JsonObject>): String =
        buildJsonObject {
            put("contractVersion", contractVersion)
            put("recordType", recordType)
            put("items", kotlinx.serialization.json.JsonArray(items))
        }.toString()

    private fun JsonObjectBuilder.identity(id: String?) {
        if (id == null) {
            put("kind", "unknown")
        } else {
            put("kind", "known")
            put("id", id)
        }
    }
}
