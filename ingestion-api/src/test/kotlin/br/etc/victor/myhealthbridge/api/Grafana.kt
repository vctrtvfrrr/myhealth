package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The Grafana of the platform, at the tag it follows (ADR 0028): it holds the dashboards as the
 * reconciler saves them and runs their panels through `/api/ds/query`, where the datasource expands the
 * macros. What the browser would interpolate, the template variables and `$__timezone`, is substituted
 * here, the way the dashboard would send it.
 */
class Grafana(network: Network, datasourceProvisioning: String) : AutoCloseable {

    private val container: GenericContainer<*> = GenericContainer(IMAGE)
        .withNetwork(network)
        .withEnv("GF_SECURITY_ADMIN_PASSWORD", ADMIN_PASSWORD)
        .withEnv("GF_ANALYTICS_REPORTING_ENABLED", "false")
        .withEnv("GF_ANALYTICS_CHECK_FOR_UPDATES", "false")
        .withCopyToContainer(
            Transferable.of(datasourceProvisioning),
            "/etc/grafana/provisioning/datasources/myhealth-read.yml",
        )
        .withExposedPorts(PORT)
        .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))

    private val http = HttpClient.newHttpClient()

    fun start(): Grafana {
        container.start()
        return this
    }

    override fun close() = container.stop()

    /** Saves the generation as the reconciler does: the folder first, then every dashboard into it. */
    fun publish(dashboards: List<JsonObject>) {
        call(
            "POST",
            "/api/folders",
            buildJsonObject {
                put("uid", PlatformDashboards.FOLDER_UID)
                put("title", PlatformDashboards.FOLDER_TITLE)
            },
        )
        val known = dashboards.map { it["uid"]!!.jsonPrimitive.content }.toSet()
        dashboards.forEach { call("POST", "/api/dashboards/db", PlatformDashboards.saveRequest(it, known)) }
    }

    /** The dashboard as Grafana keeps it, under the uid the platform gave it. */
    fun dashboard(localUid: String): Dashboard {
        val saved = call("GET", "/api/dashboards/uid/${PlatformDashboards.namespaced(localUid)}").jsonObject
        return Dashboard(saved["dashboard"]!!.jsonObject, saved["meta"]!!.jsonObject["folderUid"]!!.jsonPrimitive.content)
    }

    fun query(target: JsonObject, range: TimeRange, variables: Map<String, String>): QueryResult {
        val substituted = buildJsonObject {
            target.forEach { (key, value) ->
                put(key, if (key == "rawSql") JsonPrimitive(interpolate(value.jsonPrimitive.content, variables)) else value)
            }
            put("intervalMs", 60_000)
            put("maxDataPoints", 1_000)
        }
        val response = call(
            "POST",
            "/api/ds/query",
            buildJsonObject {
                put("from", range.from.toEpochMilli().toString())
                put("to", range.to.toEpochMilli().toString())
                putJsonArray("queries") { add(substituted) }
            },
            accept = setOf(200, 400, 500),
        ).jsonObject
        val result = response["results"]!!.jsonObject[target["refId"]!!.jsonPrimitive.content]!!.jsonObject
        return QueryResult(
            error = result["error"]?.jsonPrimitive?.content,
            frames = (result["frames"] as? JsonArray).orEmpty().map { Frame(it.jsonObject) },
        )
    }

    private fun interpolate(sql: String, variables: Map<String, String>): String =
        (variables + ("__timezone" to TIMEZONE)).entries.fold(sql) { text, (name, value) ->
            Regex("""\$\{${Regex.escape(name)}(?::[^}]*)?}|\[\[${Regex.escape(name)}]]|\$${Regex.escape(name)}\b""")
                .replace(text) { value }
        }

    private fun call(method: String, path: String, body: JsonObject? = null, accept: Set<Int> = setOf(200)): JsonElement {
        val request = HttpRequest.newBuilder(URI("http://${container.host}:${container.getMappedPort(PORT)}$path"))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:$ADMIN_PASSWORD".toByteArray()))
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in accept) { "$method $path answered ${response.statusCode()}: ${response.body()}" }
        return Json.parseToJsonElement(response.body())
    }

    class Dashboard(val json: JsonObject, val folderUid: String) {

        /** Every panel, including those inside a collapsed row. */
        val panels: List<JsonObject> = json.panels().flatMap { listOf(it) + it.panels() }

        fun panel(title: String): JsonObject = panels.single { it["title"]?.jsonPrimitive?.content == title }

        /** The values of each custom variable, as `Text : value` pairs of its query. */
        val customVariables: Map<String, List<String>> =
            (json["templating"]?.jsonObject?.get("list") as? JsonArray).orEmpty()
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "custom" }
                .associate { variable ->
                    variable["name"]!!.jsonPrimitive.content to variable["query"]!!.jsonPrimitive.content
                        .split(',')
                        .map { option -> option.substringAfter(" : ").trim() }
                }

        private fun JsonObject.panels(): List<JsonObject> = (this["panels"] as? JsonArray).orEmpty().map { it.jsonObject }
    }

    class TimeRange(val from: Instant, val to: Instant)

    class QueryResult(val error: String?, val frames: List<Frame>) {
        val rows: List<Map<String, JsonElement>> get() = frames.flatMap { it.rows }
    }

    /** A data frame read as rows; a time field arrives as epoch milliseconds. */
    class Frame(json: JsonObject) {
        private val names = json["schema"]!!.jsonObject["fields"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        private val columns = (json["data"]?.jsonObject?.get("values") as? JsonArray).orEmpty().map { it.jsonArray }

        val rows: List<Map<String, JsonElement>> = (0 until (columns.firstOrNull()?.size ?: 0)).map { row ->
            names.zip(columns).associate { (name, values) -> name to (values.getOrNull(row) ?: JsonNull) }
        }
    }

    companion object {
        const val IMAGE = "grafana/grafana:13.1"
        const val TIMEZONE = "America/Sao_Paulo"
        private const val PORT = 3000
        private const val ADMIN_PASSWORD = "dashboard-test"
    }
}
