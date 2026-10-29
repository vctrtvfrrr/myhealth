package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The provisioning a local Grafana reads to show the dashboards as the platform saves them, against the
 * production read model reached through an SSH tunnel (ADR 0028). The password is the variable the
 * compose file hands the container, never a value written here.
 */
object GrafanaPreview {

    private val json = Json { prettyPrint = true }

    fun write(source: File, target: File) {
        val dashboards = target.resolve("dashboards")
        dashboards.deleteRecursively()
        dashboards.mkdirs()
        val files = PlatformDashboards.files(source)
        val known = files.map { PlatformDashboards.parse(it)["uid"]!!.jsonPrimitive.content }.toSet()
        files.forEach { file ->
            val normalized = PlatformDashboards.normalize(PlatformDashboards.parse(file), known)
            dashboards.resolve(file.name).writeText(json.encodeToString(normalized) + "\n")
        }

        target.resolve("provisioning/dashboards").apply { mkdirs() }.resolve("myhealth.yml").writeText(
            """
            |apiVersion: 1
            |
            |providers:
            |  - name: ${PlatformDashboards.APP}
            |    folder: '${PlatformDashboards.FOLDER_TITLE}'
            |    folderUid: ${PlatformDashboards.FOLDER_UID}
            |    type: file
            |    allowUiUpdates: false
            |    updateIntervalSeconds: 10
            |    options:
            |      path: $DASHBOARDS_PATH
            |""".trimMargin(),
        )
        target.resolve("provisioning/datasources").apply { mkdirs() }.resolve("myhealth-read.yml").writeText(
            PlatformDashboards.datasourceProvisioning(
                url = "host.docker.internal:$TUNNEL_PORT",
                database = PlatformDashboards.APP,
                password = "\$MYHEALTH_READ_PASSWORD",
            ),
        )
    }

    const val DASHBOARDS_PATH = "/etc/grafana/dashboards"
    const val TUNNEL_PORT = 15432
}

fun main(arguments: Array<String>) {
    val (source, target) = arguments.map(::File)
    GrafanaPreview.write(source, target)
    println("Wrote the Grafana preview to $target")
}
