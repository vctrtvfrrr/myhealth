package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Loki panels of the operational dashboard, applied to a log line the way Loki applies their
 * pipelines: the line filters `|=` and `|~`, then the `regexp` stage, whose labels the counting queries
 * group by. The test has no Loki, so this is how the lines the API really writes reach those panels.
 */
class OperationalLogQueries(dashboard: JsonObject) {

    private val panels = dashboard["panels"]!!.jsonArray.map { it.jsonObject }

    /** The labels each counting query of [panel] groups [line] under, for the queries that count it. */
    fun counted(panel: String, line: String): List<Map<String, String>> =
        pipelines(panel).mapNotNull { pipeline -> pipeline.labels(line)?.filterKeys { it in pipeline.groupedBy } }

    /** Whether a query of [panel] keeps [line]. */
    fun shows(panel: String, line: String): Boolean = pipelines(panel).any { it.labels(line) != null }

    private fun pipelines(panel: String): List<Pipeline> =
        panels.single { it["title"]?.jsonPrimitive?.content == panel }["targets"]!!.jsonArray
            .map { Pipeline(it.jsonObject["expr"]!!.jsonPrimitive.content) }

    private class Pipeline(expr: String) {
        private val contains = Regex("""\|= "([^"]*)"""").findAll(expr).map { it.groupValues[1] }.toList()
        private val matches = Regex("""\|~ "([^"]*)"""").findAll(expr).map { Regex(it.groupValues[1]) }.toList()

        // LogQL writes a named group as RE2 does, `(?P<name>...)`, which Java spells `(?<name>...)`.
        private val extraction = Regex("""\| regexp `([^`]*)`""").find(expr)?.groupValues?.get(1)?.let { Regex(it.replace("(?P<", "(?<")) }
        private val labelNames = Regex("""\(\?P<([A-Za-z_][A-Za-z0-9_]*)>""").findAll(expr).map { it.groupValues[1] }.toList()

        val groupedBy: Set<String> =
            Regex("""by \(([^)]*)\)""").find(expr)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.toSet() ?: labelNames.toSet()

        fun labels(line: String): Map<String, String>? {
            if (!contains.all { it in line } || !matches.all { it.containsMatchIn(line) }) return null
            val found = extraction?.find(line) ?: return emptyMap()
            return labelNames.associateWith { name -> found.groups[name]?.value.orEmpty() }
        }
    }

    companion object {
        fun read(): OperationalLogQueries = OperationalLogQueries(
            PlatformDashboards.read(DeploymentArtifacts.root.resolve("observability/dashboards"))
                .single { it["uid"]!!.jsonPrimitive.content == "operacional" },
        )
    }
}
