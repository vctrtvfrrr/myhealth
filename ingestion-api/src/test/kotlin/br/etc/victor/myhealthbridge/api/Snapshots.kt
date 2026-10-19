package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal

/**
 * Synthetic step and activity snapshots.
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

    const val USER_PROFILE = "user_profile"
    const val USER_PROFILE_MAPPER_VERSION = "samsung-health-user-profile/1"

    /**
     * The profile, which has no window. Every value is invented: a profile is personal data, and none in
     * this repository may be the Data Owner's. A null [content] is no profile at all.
     */
    fun profile(
        observedAt: String = "2026-08-10T18:20:00Z",
        observedOffset: String = "-03:00",
        content: JsonObject? = profileContent(),
        mapperVersion: String = USER_PROFILE_MAPPER_VERSION,
        window: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        window?.let { put("window", it) }
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", observedOffset)
        }
        put("sourceScope", "all_sources")
        put("mapperVersion", mapperVersion)
        content?.let { put("content", it) }
    }

    fun profileContent(
        dateOfBirth: String? = "2000-01-01",
        height: Double? = 170.5,
        weight: Double? = 65.0,
        gender: String? = "GENDER_UNKNOWN",
        nickname: String? = "Synthetic",
    ): JsonObject = buildJsonObject {
        dateOfBirth?.let { put("date_of_birth", it) }
        height?.let { put("height", it) }
        weight?.let { put("weight", it) }
        gender?.let { put("gender", it) }
        nickname?.let { put("nickname", it) }
    }

    const val ACTIVITY_SUMMARY = "activity_summary"
    const val ACTIVITY_SUMMARY_MAPPER_VERSION = "samsung-health-activity-summary/1"

    /** One local hour of activity totals, each a `{value, unit}`; a null [content] is an empty window. */
    fun activitySummary(
        start: String = "2026-08-10T14:00:00",
        end: String = "2026-08-10T15:00:00",
        observedAt: String = "2026-08-10T18:20:00Z",
        content: JsonObject? = activityTotals(),
        mapperVersion: String = ACTIVITY_SUMMARY_MAPPER_VERSION,
    ): JsonObject = buildJsonObject {
        putJsonObject("window") {
            put("start", start)
            put("end", end)
        }
        putJsonObject("observedAt") {
            put("instant", observedAt)
            put("offset", "-03:00")
        }
        put("sourceScope", "platform")
        put("mapperVersion", mapperVersion)
        content?.let { put("content", it) }
    }

    fun activityTotals(
        activeCaloriesBurned: Quantity? = Quantity("42.5", "kcal"),
        caloriesBurned: Quantity? = Quantity("118.25", "kcal"),
        activeTime: Quantity? = Quantity("754.321", "s"),
        distance: Quantity? = Quantity("1310.7", "m"),
    ): JsonObject = buildJsonObject {
        activeCaloriesBurned?.let { put("activeCaloriesBurned", it.json()) }
        caloriesBurned?.let { put("caloriesBurned", it.json()) }
        activeTime?.let { put("activeTime", it.json()) }
        distance?.let { put("distance", it.json()) }
    }

    class Quantity(private val value: String, private val unit: String) {
        fun json(): JsonObject = buildJsonObject {
            put("value", JsonPrimitive(BigDecimal(value)))
            put("unit", unit)
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
