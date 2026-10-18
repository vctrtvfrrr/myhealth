package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Synthetic step snapshots.
 *
 * Every value is invented: the repository must never carry fixtures derived from real measurements.
 */
object Snapshots {

    const val STEPS = "steps"
    const val STEPS_MAPPER_VERSION = "samsung-health-steps/1"

    /** One local hour of steps; a null [steps] is a window with nothing in it. */
    fun steps(
        start: String = "2026-08-10T14:00:00",
        end: String = "2026-08-10T15:00:00",
        observedAt: String = "2026-08-10T18:20:00Z",
        observedOffset: String = "-03:00",
        steps: Long? = 1234,
        sourceScope: String = "platform",
        mapperVersion: String = STEPS_MAPPER_VERSION,
        explicitEmpty: Boolean = false,
    ): JsonObject = buildJsonObject {
        putJsonObject("window") {
            put("start", start)
            put("end", end)
        }
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", observedOffset)
        }
        put("sourceScope", sourceScope)
        put("mapperVersion", mapperVersion)
        when {
            steps != null -> putJsonObject("content") { put("steps", steps) }
            explicitEmpty -> put("content", JsonNull)
        }
    }

    fun batch(
        snapshotType: String = STEPS,
        contractVersion: Int = 3,
        items: List<JsonObject>,
    ): String = buildJsonObject {
        put("contractVersion", contractVersion)
        put("snapshotType", snapshotType)
        put("items", JsonArray(items))
    }.toString()
}
