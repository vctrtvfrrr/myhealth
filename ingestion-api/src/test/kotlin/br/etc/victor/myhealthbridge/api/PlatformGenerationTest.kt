package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-0008 of `codelab/infra`, restated: a generation the reconciler rejects keeps the API from being
 * deployed, so it is refused here first (ADR 0028).
 */
class PlatformGenerationTest {

    @Test
    fun `the dashboards of the repository form a generation the platform accepts`() {
        val dashboards = PlatformDashboards.read(DeploymentArtifacts.root.resolve("observability/dashboards"))

        assertTrue(dashboards.isNotEmpty(), "no dashboard was read")
        assertEquals(emptyList<String>(), PlatformGeneration.violations(dashboards))
    }

    @Test
    fun `accepts a dashboard that only reads the datasources the platform grants this app`() {
        val dashboard = dashboard(
            """
            {"uid": "atividade", "panels": [
              {"datasource": {"type": "grafana-postgresql-datasource", "uid": "myhealth-read"}},
              {"datasource": "prometheus"},
              {"targets": [{"datasource": {"uid": "loki"}}, {"datasourceUid": "__expr__"}]},
              {"datasource": null}
            ]}
            """,
        )

        assertEquals(emptyList<String>(), PlatformGeneration.violations(listOf(dashboard)))
    }

    @Test
    fun `refuses a uid outside the alphabet, beyond 30 characters, missing or repeated`() {
        val violations = PlatformGeneration.violations(
            listOf(
                dashboard("""{"uid": "visão"}"""),
                dashboard("""{"uid": "${"a".repeat(31)}"}"""),
                dashboard("""{"title": "sem uid"}"""),
                dashboard("""{"uid": "sono"}"""),
                dashboard("""{"uid": "sono"}"""),
            ),
        )

        assertEquals(
            listOf(
                "dashboard #0: uid 'visão' is not [A-Za-z0-9_-]+",
                "dashboard #1: uid '${"a".repeat(31)}' does not fit the 40-character uid myhealth--<uid>",
                "dashboard #2 declares no uid",
                "dashboards share the uid sono",
            ),
            violations,
        )
    }

    @Test
    fun `refuses a datasource the platform does not grant this app`() {
        val violations = PlatformGeneration.violations(
            listOf(
                dashboard("""{"uid": "a", "panels": [{"datasource": {"uid": "postgres-admin"}}]}"""),
                dashboard("""{"uid": "b", "panels": [{"targets": [{"datasourceUid": "-- Mixed --"}]}]}"""),
                dashboard("""{"uid": "c", "panels": [{"datasource": 3}]}"""),
            ),
        )

        assertEquals(
            listOf(
                "dashboard a: datasource 'postgres-admin' is not one of __expr__, loki, myhealth-read, prometheus",
                "dashboard b: datasource '-- Mixed --' is not one of __expr__, loki, myhealth-read, prometheus",
                "dashboard c: a datasource reference is neither a string nor an object",
            ),
            violations,
        )
    }

    @Test
    fun `refuses a datasource variable, an exported placeholder and the built-in annotation`() {
        val violations = PlatformGeneration.violations(
            listOf(
                dashboard("""{"uid": "a", "templating": {"list": [{"name": "fonte", "type": "datasource"}]}}"""),
                dashboard("""{"uid": "b", "panels": [{"title": "${'$'}{DS_MYHEALTH}"}]}"""),
                dashboard(
                    """
                    {"uid": "c", "annotations": {"list": [
                      {"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}}
                    ]}}
                    """,
                ),
            ),
        )

        assertEquals(
            listOf(
                "dashboard a: template variable 'fonte' is a datasource picker",
                "dashboard b: carries an exported \${DS_*} placeholder",
                "dashboard c: carries the built-in -- Grafana -- annotation",
                "dashboard c: datasource '-- Grafana --' is not one of __expr__, loki, myhealth-read, prometheus",
            ),
            violations,
        )
    }

    @Test
    fun `refuses a link to a dashboard outside the generation`() {
        val violations = PlatformGeneration.violations(
            listOf(
                dashboard(
                    """
                    {"uid": "atividade",
                     "links": [{"type": "link", "url": "/d/myhealth--atividade"}, {"type": "link", "url": "/d/myhealth--sono"}],
                     "panels": [
                       {"fieldConfig": {"defaults": {"links": [{"url": "/d/myhealth--treino?var-treino=1"}]}}},
                       {"options": {"dashboardUID": "noite"}},
                       {"links": [{"url": "/d/atividade"}]},
                       {"links": [{"url": "https://grafana.example.com/d/myhealth--atividade"}]}
                     ]}
                    """,
                ),
            ),
        )

        assertEquals(
            listOf(
                "dashboard atividade: dashboardUID='noite' refers to a dashboard that is not in this generation",
                "dashboard atividade: links to /d/myhealth--sono, which is not in this generation",
                "dashboard atividade: links to /d/myhealth--treino, which is not in this generation",
                "dashboard atividade: links to /d/atividade, which is not in this generation",
                "dashboard atividade: links to https://grafana.example.com/d/myhealth--atividade, which is not a path of this Grafana",
            ),
            violations,
        )
    }

    private fun dashboard(json: String): JsonObject = Json.parseToJsonElement(json.trimIndent()).jsonObject
}
