package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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

    const val EXERCISE_RECORD_TYPE = "exercise"
    const val EXERCISE_MAPPER_VERSION = "samsung-health-exercise/1"

    /** A synthetic exercise, with invented coordinates when it carries a route at all. */
    fun exercise(
        samsungUid: String = "uid-exercise-1",
        type: String? = "RUNNING",
        duration: String? = "1800.0",
        durationUnit: String = "s",
        distance: String? = "5000.0",
        distanceUnit: String = "m",
        calories: String? = "320.0",
        caloriesUnit: String = "kcal",
        route: List<JsonObject>? = null,
        mapperVersion: String = EXERCISE_MAPPER_VERSION,
        observedAt: String = "2026-08-10T10:00:00Z",
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", "-03:00")
        }
        put("mapperVersion", mapperVersion)
        putJsonObject("sourceProvenance") {
            putJsonObject("sourceApp") { identity("com.example.shealth") }
            putJsonObject("sourceDevice") { identity("device-1") }
        }
        putJsonObject("state") {
            put("kind", "present")
            putJsonObject("period") {
                putJsonObject("start") {
                    put("instant", "2026-08-10T09:00:00Z")
                    put("offset", "-03:00")
                }
                putJsonObject("end") {
                    put("instant", "2026-08-10T09:30:00Z")
                    put("offset", "-03:00")
                }
            }
            putJsonObject("sourcePayload") {
                putJsonObject("fields") { type?.let { put("exercise_type", it) } }
            }
            putJsonObject("normalizedPayload") {
                putJsonObject("exercise") {
                    type?.let { put("type", it) }
                    quantity("duration", duration, durationUnit)
                    quantity("distance", distance, distanceUnit)
                    quantity("calories", calories, caloriesUnit)
                    route?.let { put("route", JsonArray(it)) }
                }
            }
        }
    }

    fun routePoint(
        at: String = "2026-08-10T09:00:00Z",
        latitude: String? = "-23.5",
        longitude: String? = "-46.6",
        altitude: String? = "760.0",
    ): JsonObject = buildJsonObject {
        put("at", at)
        latitude?.let { put("latitudeDegrees", measurement(it)) }
        longitude?.let { put("longitudeDegrees", measurement(it)) }
        altitude?.let { put("altitudeMeters", measurement(it)) }
    }

    const val SLEEP_RECORD_TYPE = "sleep"
    const val SLEEP_MAPPER_VERSION = "samsung-health-sleep/1"

    /** A synthetic night, staged when its sessions say so. */
    fun sleep(
        samsungUid: String = "uid-sleep-1",
        duration: String? = "27000.0",
        durationUnit: String = "s",
        score: String? = "82",
        sessions: List<JsonObject>? = listOf(sleepSession()),
        mapperVersion: String = SLEEP_MAPPER_VERSION,
        observedAt: String = "2026-08-10T09:35:00Z",
        withSleep: Boolean = true,
        start: String = "2026-08-10T02:00:00Z",
        end: String = "2026-08-10T09:30:00Z",
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", "-03:00")
        }
        put("mapperVersion", mapperVersion)
        putJsonObject("sourceProvenance") {
            putJsonObject("sourceApp") { identity("com.example.shealth") }
            putJsonObject("sourceDevice") { identity("device-1") }
        }
        putJsonObject("state") {
            put("kind", "present")
            putJsonObject("period") {
                putJsonObject("start") {
                    put("instant", start)
                    put("offset", "-03:00")
                }
                putJsonObject("end") {
                    put("instant", end)
                    put("offset", "-03:00")
                }
            }
            putJsonObject("sourcePayload") {
                putJsonObject("fields") { duration?.let { put("sleep_duration", it.toBigDecimal()) } }
            }
            putJsonObject("normalizedPayload") {
                if (withSleep) {
                    putJsonObject("sleep") {
                        quantity("duration", duration, durationUnit)
                        score?.let { put("score", measurement(it)) }
                        sessions?.let { put("sessions", JsonArray(it)) }
                    }
                }
            }
        }
    }

    fun sleepSession(
        start: String? = "2026-08-10T02:00:00Z",
        end: String? = "2026-08-10T09:30:00Z",
        durationSeconds: String? = "27000.0",
        stages: List<JsonObject>? = null,
    ): JsonObject = buildJsonObject {
        start?.let { put("start", it) }
        end?.let { put("end", it) }
        durationSeconds?.let { put("durationSeconds", measurement(it)) }
        stages?.let { put("stages", JsonArray(it)) }
    }

    fun sleepStage(
        start: String? = "2026-08-10T02:00:00Z",
        end: String? = "2026-08-10T02:40:00Z",
        stage: String? = "LIGHT",
    ): JsonObject = buildJsonObject {
        start?.let { put("start", it) }
        end?.let { put("end", it) }
        stage?.let { put("stage", it) }
    }

    /** A number when it is one, so a test can also state what a defective mapper would have put there. */
    private fun measurement(value: String): JsonPrimitive =
        value.toBigDecimalOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

    private fun JsonObjectBuilder.quantity(name: String, value: String?, unit: String) {
        if (value == null) return
        putJsonObject(name) {
            put("value", value.toBigDecimal())
            put("unit", unit)
        }
    }

    fun removal(
        samsungUid: String = "uid-1",
        observedAt: String = "2026-08-11T09:00:00Z",
        withPeriod: Boolean = false,
        mapperVersion: String = MAPPER_VERSION,
    ): JsonObject = buildJsonObject {
        put("samsungUid", samsungUid)
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", "-03:00")
        }
        put("mapperVersion", mapperVersion)
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

    /** A batch declaring which Health Records the application saw over a stretch of local time it read whole. */
    fun inventoryBatch(
        samsungUids: List<String>,
        from: String = "2026-08-10T00:00:00",
        until: String = "2026-08-11T00:00:00",
        readAt: String = "2026-08-12T10:00:00Z",
        recordType: String = RECORD_TYPE,
        contractVersion: Int = 2,
        items: List<JsonObject> = emptyList(),
    ): String = buildJsonObject {
        put("contractVersion", contractVersion)
        put("recordType", recordType)
        put("items", JsonArray(items))
        putJsonObject("inventory") {
            putJsonObject("interval") {
                put("start", from)
                put("end", until)
            }
            put("readAt", readAt)
            put("samsungUids", JsonArray(samsungUids.map(::JsonPrimitive)))
        }
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
