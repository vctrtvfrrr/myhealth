package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RejectionCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/**
 * The content of an hour of activity: up to four totals, each a `{value, unit}` in the one unit the read
 * model casts it as. A total the source did not report is absent, but an hour with none of them is no
 * observation of activity, which an empty window already says.
 */
object ActivitySummaryContent {

    private class Total(val unit: String, val plausible: ClosedRange<BigDecimal>)

    /** Far beyond what anyone burns, moves or walks in an hour, so they catch mapper bugs, not athletes. */
    private val totals: Map<String, Total> = mapOf(
        "activeCaloriesBurned" to Total("kcal", BigDecimal.ZERO..BigDecimal(10_000)),
        "caloriesBurned" to Total("kcal", BigDecimal.ZERO..BigDecimal(10_000)),
        "activeTime" to Total("s", BigDecimal.ZERO..BigDecimal(3_600)),
        "distance" to Total("m", BigDecimal.ZERO..BigDecimal(100_000)),
    )

    fun validate(content: JsonObject): List<RejectionCode> {
        val reported = totals.filterKeys { it in content }
        if (reported.isEmpty()) return listOf(RejectionCode.INVALID_PAYLOAD)

        return reported.flatMap { (name, total) -> validate(content[name], total) }.distinct()
    }

    private fun validate(node: Any?, total: Total): List<RejectionCode> {
        val quantity = node as? JsonObject ?: return listOf(RejectionCode.INVALID_PAYLOAD)
        val codes = mutableListOf<RejectionCode>()

        val value = (quantity["value"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.let { runCatching { BigDecimal(it.content) }.getOrNull() }
        if (value == null || value !in total.plausible) codes += RejectionCode.INVALID_PAYLOAD

        val unit = (quantity["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (unit != total.unit) codes += RejectionCode.INVALID_UNIT

        return codes
    }
}
