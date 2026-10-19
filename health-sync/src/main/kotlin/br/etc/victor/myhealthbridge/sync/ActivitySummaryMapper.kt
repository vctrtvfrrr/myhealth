package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.SnapshotObservation
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.contract.ZonedInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The one mapper of the activity Samsung Health totals in an hour, merged across the phone and the
 * wearables. The adapter reads each total as an aggregate of its own and hands them over together, as a
 * [SourceValue.Nested] keyed by the names declared here.
 *
 * Samsung Health documents none of the units: kilocalories, seconds and meters are the ones it uses
 * everywhere else, and the content states them so that a query never has to assume them.
 */
object ActivitySummaryMapper : SnapshotMapper {

    const val TOTAL_ACTIVE_CALORIES_BURNED = "total_active_calories_burned"
    const val TOTAL_CALORIES_BURNED = "total_calories_burned"

    /** Already in seconds, as the adapter preserves every duration. */
    const val TOTAL_ACTIVE_TIME = "total_active_time"
    const val TOTAL_DISTANCE = "total_distance"

    override val version: String = "samsung-health-activity-summary/1"

    override val sourceScope: SourceScope = SourceScope.PLATFORM

    override fun map(window: SnapshotWindow, aggregate: SourceValue?, observedAt: ZonedInstant): SnapshotObservation =
        SnapshotObservation(
            window = window.wire(),
            observedAt = observedAt,
            sourceScope = sourceScope,
            mapperVersion = version,
            content = aggregate?.let(::contentOf),
        )

    /**
     * A total that is not a number is left out rather than guessed at; with none left, the content is
     * empty and the API rejects it, so it stays a Mapping Pendency.
     */
    private fun contentOf(aggregate: SourceValue): JsonObject = buildJsonObject {
        val totals = (aggregate as? SourceValue.Nested)?.fields.orEmpty()
        quantity("activeCaloriesBurned", totals[TOTAL_ACTIVE_CALORIES_BURNED], KILOCALORIES)
        quantity("caloriesBurned", totals[TOTAL_CALORIES_BURNED], KILOCALORIES)
        quantity("activeTime", totals[TOTAL_ACTIVE_TIME], SECONDS)
        quantity("distance", totals[TOTAL_DISTANCE], METERS)
    }

    private fun JsonObjectBuilder.quantity(name: String, total: SourceValue?, unit: String) {
        if (total !is SourceValue.Number) return
        put(
            name,
            buildJsonObject {
                put("value", JsonPrimitive(total.value))
                put("unit", unit)
            },
        )
    }

    private const val KILOCALORIES = "kcal"
    private const val SECONDS = "s"
    private const val METERS = "m"
}
