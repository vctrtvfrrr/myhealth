package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The dashboard rules of ADR-0008 of `codelab/infra`, restated rather than run, so this CI does not
 * depend on a private repository of the platform (ADR 0028). The reconciler stops at the first rule
 * a generation breaks; this reports all of them.
 */
object PlatformGeneration {

    private val LOCAL_UID = Regex("^[A-Za-z0-9_-]+$")
    private const val UID_LIMIT = 40
    private val ALLOWED_DATASOURCES = setOf("prometheus", "loki", "__expr__", PlatformDashboards.DATASOURCE_UID)
    private val DASHBOARD_LINK = Regex("""/d/([A-Za-z0-9_-]+)""")
    private const val BUILT_IN_ANNOTATION = "-- Grafana --"

    fun violations(dashboards: List<JsonObject>): List<String> {
        val uids = dashboards.mapIndexed { index, dashboard -> localUid(dashboard, index) }
        val known = uids.mapNotNull { it.getOrNull() }.toSet()
        val duplicates = uids.mapNotNull { it.getOrNull() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        return uids.mapNotNull { it.exceptionOrNull()?.message } +
            duplicates.sorted().map { "dashboards share the uid $it" } +
            dashboards.zip(uids).flatMap { (dashboard, uid) ->
                val name = uid.getOrNull() ?: return@flatMap emptyList()
                contentViolations(dashboard, known).map { "dashboard $name: $it" }
            }
    }

    private fun localUid(dashboard: JsonObject, index: Int): Result<String> = runCatching {
        val uid = (dashboard["uid"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
            ?: error("dashboard #$index declares no uid")
        check(LOCAL_UID.matches(uid)) { "dashboard #$index: uid '$uid' is not [A-Za-z0-9_-]+" }
        check(PlatformDashboards.namespaced(uid).length <= UID_LIMIT) {
            "dashboard #$index: uid '$uid' does not fit the $UID_LIMIT-character uid ${PlatformDashboards.APP}--<uid>"
        }
        uid
    }

    private fun contentViolations(dashboard: JsonObject, known: Set<String>): List<String> = buildList {
        variables(dashboard).filter { it["type"]?.jsonPrimitive?.content == "datasource" }.forEach {
            add("template variable '${it["name"]?.jsonPrimitive?.content}' is a datasource picker")
        }
        if ("\${DS_" in dashboard.toString()) add("carries an exported \${DS_*} placeholder")
        if (BUILT_IN_ANNOTATION in annotations(dashboard).toString()) add("carries the built-in $BUILT_IN_ANNOTATION annotation")

        walk(dashboard) { node ->
            listOf("datasource", "datasourceUid").mapNotNull { node[it] }.forEach { reference ->
                datasourceViolation(reference)?.let(::add)
            }
            PlatformDashboards.REFERENCE_KEYS.mapNotNull { key -> node[key]?.let { key to it } }.forEach { (key, target) ->
                val uid = (target as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (uid !in known) add("$key='${uid ?: target}' refers to a dashboard that is not in this generation")
            }
        }
        walk(dashboard) { node ->
            val url = (node["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@walk
            DASHBOARD_LINK.findAll(url).map { it.groupValues[1] }.forEach { target ->
                val local = target.removePrefix("${PlatformDashboards.APP}--")
                if (local == target || local !in known) add("links to /d/$target, which is not in this generation")
            }
        }
    }

    private fun datasourceViolation(reference: JsonElement): String? {
        val uid = when {
            reference is JsonNull -> return null
            reference is JsonPrimitive && reference.isString -> reference.content
            reference is JsonObject -> (reference["uid"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            else -> return "a datasource reference is neither a string nor an object"
        }
        return if (uid in ALLOWED_DATASOURCES) null else "datasource '$uid' is not one of ${ALLOWED_DATASOURCES.sorted().joinToString()}"
    }

    private fun variables(dashboard: JsonObject): List<JsonObject> =
        ((dashboard["templating"] as? JsonObject)?.get("list") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun annotations(dashboard: JsonObject): JsonElement =
        (dashboard["annotations"] as? JsonObject)?.get("list") ?: JsonNull

    private fun walk(element: JsonElement, visit: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                visit(element)
                element.values.forEach { walk(it, visit) }
            }

            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }
}
