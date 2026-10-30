package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PlatformDashboardsTest {

    @Test
    fun `saves a dashboard under the uid, folder and lock the platform imposes`() {
        val request = PlatformDashboards.saveRequest(
            dashboard("""{"id": 7, "version": 3, "uid": "atividade", "title": "Atividade", "editable": true}"""),
            knownLocalUids = setOf("atividade"),
        )

        val saved = request["dashboard"]!!.jsonObject
        assertEquals("myhealth--atividade", saved.string("uid"))
        assertEquals("false", saved["editable"]!!.jsonPrimitive.content)
        assertFalse("id" in saved, "Grafana would read a stale id as another dashboard")
        assertFalse("version" in saved, "Grafana would refuse a stale version")
        assertEquals("codelab-app-myhealth", request.string("folderUid"))
        assertEquals("true", request["overwrite"]!!.jsonPrimitive.content)
    }

    @Test
    fun `rewrites a reference to another dashboard of the generation and keeps the authored tags`() {
        val saved = PlatformDashboards.normalize(
            dashboard(
                """
                {"uid": "sono", "tags": ["saude", "codelab-forged"],
                 "panels": [{"options": {"dashboardUID": "noite"}}]}
                """,
            ),
            knownLocalUids = setOf("sono", "noite"),
        )

        assertEquals(
            "myhealth--noite",
            saved["panels"]!!.jsonArray[0].jsonObject["options"]!!.jsonObject.string("dashboardUID"),
        )
        assertEquals(listOf("saude", "codelab-app-myhealth"), saved["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `writes a preview a local Grafana provisions as the platform would show it`(@TempDir root: File) {
        val source = root.resolve("dashboards").apply { mkdirs() }
        source.resolve("atividade.json").writeText("""{"uid": "atividade", "title": "Atividade", "editable": true}""")
        val preview = root.resolve("preview")

        GrafanaPreview.write(source, preview)

        val written = Json.parseToJsonElement(preview.resolve("dashboards/atividade.json").readText()).jsonObject
        assertEquals("myhealth--atividade", written.string("uid"))
        assertEquals("false", written["editable"]!!.jsonPrimitive.content)

        val provider = preview.resolve("provisioning/dashboards/myhealth.yml").readText()
        assertTrue(provider.contains("folder: 'App: myhealth'"), provider)
        assertTrue(provider.contains("folderUid: codelab-app-myhealth"), provider)

        val datasource = preview.resolve("provisioning/datasources/myhealth-read.yml").readText()
        assertTrue(datasource.contains("uid: myhealth-read"), datasource)
        assertTrue(datasource.contains("user: myhealth_read"), datasource)
        assertTrue(datasource.contains("password: \$MYHEALTH_READ_PASSWORD"), "the password is versioned: $datasource")
    }

    @Test
    fun `refuses to preview a generation the platform would reject`(@TempDir root: File) {
        val source = root.resolve("dashboards").apply { mkdirs() }
        source.resolve("visao.json").writeText("""{"uid": "visão geral"}""")

        assertThrows<IllegalArgumentException> { GrafanaPreview.write(source, root.resolve("preview")) }
        assertFalse(root.resolve("preview").exists(), "a rejected generation was written")
    }

    @Test
    fun `a preview drops the dashboards no longer in the repository`(@TempDir root: File) {
        val source = root.resolve("dashboards").apply { mkdirs() }
        val preview = root.resolve("preview")
        preview.resolve("dashboards").mkdirs()
        preview.resolve("dashboards/removed.json").writeText("{}")

        GrafanaPreview.write(source, preview)

        assertEquals(emptyList<String>(), preview.resolve("dashboards").list()!!.toList())
    }

    private fun dashboard(json: String): JsonObject = Json.parseToJsonElement(json.trimIndent()).jsonObject

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
}
