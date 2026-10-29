package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * What the platform reconciler does to a dashboard of this stack before saving it (ADR-0008 of
 * `codelab/infra`): the one implementation the panel test and the local preview share (ADR 0028), so a
 * `/d/myhealth--<uid>` link resolves the same in both as on the platform.
 */
object PlatformDashboards {

    const val APP = "myhealth"
    const val FOLDER_UID = "codelab-app-$APP"
    const val FOLDER_TITLE = "App: $APP"
    const val DATASOURCE_UID = "myhealth-read"
    const val DATASOURCE_NAME = "MyHealth (read)"
    const val READ_ACCOUNT = "myhealth_read"

    val REFERENCE_KEYS = listOf("dashboardUID", "dashboardUid", "__dashboardUid__")

    fun namespaced(localUid: String): String = "$APP--$localUid"

    /** Only the files directly in [directory] reach the platform: the deploy action does not recurse. */
    fun read(directory: File): List<JsonObject> = files(directory).map { parse(it) }

    fun files(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().sortedBy { it.name }

    fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    /** The body the reconciler posts to `/api/dashboards/db`. */
    fun saveRequest(dashboard: JsonObject, knownLocalUids: Set<String>): JsonObject = buildJsonObject {
        put("dashboard", normalize(dashboard, knownLocalUids))
        put("folderUid", FOLDER_UID)
        put("overwrite", true)
    }

    fun normalize(dashboard: JsonObject, knownLocalUids: Set<String>): JsonObject {
        val localUid = dashboard["uid"]!!.jsonPrimitive.content
        val authoredTags = (dashboard["tags"] as? JsonArray).orEmpty()
            .filter { it is JsonPrimitive && it.isString && !it.content.startsWith("codelab-") }
        val rewritten = rewriteReferences(dashboard, knownLocalUids).jsonObject
        return JsonObject(
            rewritten - "id" - "version" + mapOf(
                "uid" to JsonPrimitive(namespaced(localUid)),
                "editable" to JsonPrimitive(false),
                "tags" to JsonArray(authoredTags + JsonPrimitive("codelab-app-$APP")),
            ),
        )
    }

    private fun rewriteReferences(element: JsonElement, known: Set<String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (key in REFERENCE_KEYS && value is JsonPrimitive && value.content in known) {
                    JsonPrimitive(namespaced(value.content))
                } else {
                    rewriteReferences(value, known)
                }
            },
        )

        is JsonArray -> JsonArray(element.map { rewriteReferences(it, known) })
        else -> element
    }

    /** The datasource `codelab/infra` provisions, pointed at [url] and logged in with [password]. */
    fun datasourceProvisioning(url: String, database: String, password: String): String = """
        |apiVersion: 1
        |
        |datasources:
        |  - name: $DATASOURCE_NAME
        |    uid: $DATASOURCE_UID
        |    type: grafana-postgresql-datasource
        |    access: proxy
        |    url: $url
        |    user: $READ_ACCOUNT
        |    editable: false
        |    jsonData:
        |      database: $database
        |      sslmode: disable
        |    secureJsonData:
        |      password: $password
        |""".trimMargin()
}
