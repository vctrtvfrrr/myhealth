package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.testcontainers.containers.Network
import java.time.Instant

/**
 * Every panel of every dashboard, run through the Grafana of the platform against a read model filled
 * through the API and read as `myhealth_read` (ADR 0028). Each panel must answer without error and with
 * rows for every value of its variables; exact values are asserted only where a panel's SQL carries a
 * rule of its own (ADR 0029).
 *
 * A new dashboard needs only its JSON, the synthetic records that give each of its panels rows in
 * [SEEDED], and the exact values of the rules its SQL carries.
 */
@Tag("integration")
class DashboardPanelsTest : IngestionApiTest() {

    private val network = Network.newNetwork()

    private lateinit var grafana: Grafana

    init {
        postgres.withNetwork(network).withNetworkAliases(DATABASE_HOST)
    }

    @BeforeAll
    fun startGrafana() {
        seed()
        grantReadAccess(READER_PASSWORD)
        grafana = Grafana(
            network,
            PlatformDashboards.datasourceProvisioning(
                url = "$DATABASE_HOST:5432",
                database = postgres.databaseName,
                password = READER_PASSWORD,
            ),
        ).start()
        grafana.publish(PlatformDashboards.read(DeploymentArtifacts.root.resolve("observability/dashboards")))
    }

    @AfterAll
    fun stopGrafana() {
        if (::grafana.isInitialized) grafana.close()
        network.close()
    }

    @TestFactory
    fun `every panel of every dashboard answers with rows for every value of its variables`(): List<DynamicTest> =
        PlatformDashboards.read(DeploymentArtifacts.root.resolve("observability/dashboards")).flatMap { authored ->
            val localUid = authored["uid"]!!.jsonPrimitive.content
            val dashboard = grafana.dashboard(localUid)
            dashboard.panels.flatMap { panel ->
                panel.readModelTargets().flatMap { target ->
                    combinations(dashboard.customVariables).map { variables ->
                        DynamicTest.dynamicTest("$localUid / ${panel.title()} / ${target.refId()} / $variables") {
                            val result = grafana.query(target, SEEDED, variables)

                            assertNull(result.error, result.error)
                            assertTrue(result.rows.isNotEmpty(), "the panel answered no rows")
                        }
                    }
                }
            }
        }

    @Test
    fun `the platform saves each dashboard under its uid, in the folder of the app, locked`() {
        val dashboard = grafana.dashboard("atividade")

        assertEquals("myhealth--atividade", dashboard.json["uid"]!!.jsonPrimitive.content)
        assertEquals(PlatformDashboards.FOLDER_UID, dashboard.folderUid)
        assertEquals("false", dashboard.json["editable"]!!.jsonPrimitive.content)
    }

    @Test
    fun `steps fall on their local day, beside the goal of that day, and count zero from the first day with steps`() {
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 1500.0, 6000.0),
                listOf("2026-08-11T03:00:00Z", 2000.0, 8000.0),
                listOf("2026-08-12T03:00:00Z", 0.0, null),
                listOf("2026-08-13T03:00:00Z", 300.0, null),
                listOf("2026-08-14T03:00:00Z", 0.0, null),
                listOf("2026-08-15T03:00:00Z", 0.0, null),
                listOf("2026-08-16T03:00:00Z", 0.0, null),
            ),
            series("atividade", "Passos", "day", SEEDED, "Passos", "Meta"),
        )
    }

    @Test
    fun `a week sums the daily goals of its days that fall in the period`() {
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", 3800.0, 14000.0), listOf("2026-08-16T03:00:00Z", 0.0, null)),
            series("atividade", "Passos", "week", SEEDED, "Passos", "Meta"),
        )
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", 2300.0, 8000.0), listOf("2026-08-16T03:00:00Z", 0.0, null)),
            series("atividade", "Passos", "week", Grafana.TimeRange(localMidnight("2026-08-11"), SEEDED.to), "Passos", "Meta"),
        )
    }

    @Test
    fun `an hour has no goal`() {
        val hours = series("atividade", "Passos", "hour", SEEDED, "Passos", "Meta")

        assertEquals(listOf("2026-08-10T12:00:00Z", 1000.0, null), hours.first())
        assertEquals(listOf(null), hours.map { it[2] }.distinct())
    }

    @Test
    fun `active time is set beside its own goal`() {
        assertEquals(
            listOf("2026-08-10T03:00:00Z", 754.321, 3600.0),
            series("atividade", "Tempo ativo", "day", SEEDED, "Tempo ativo", "Meta").first(),
        )
    }

    @Test
    fun `floors fall on the local day they were climbed on, and a day without them has no row`() {
        assertEquals(
            listOf(listOf("2026-08-10T03:00:00Z", 3.0)),
            series("atividade", "Andares", "day", SEEDED, "Andares"),
        )
    }

    private fun series(
        dashboard: String,
        panel: String,
        grouping: String,
        range: Grafana.TimeRange,
        vararg fields: String,
    ): List<List<Any?>> {
        val target = grafana.dashboard(dashboard).panel(panel).readModelTargets().single()
        val result = grafana.query(target, range, mapOf("agrupar" to grouping))
        assertNull(result.error, result.error)
        return result.rows.map { row ->
            listOf(Instant.ofEpochMilli(row.entries.first { it.key.equals("time", ignoreCase = true) }.value.jsonPrimitive.longOrNull!!).toString()) +
                fields.map { field -> row.getValue(field).takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull }
        }
    }

    private fun seed() {
        snapshots(
            Snapshots.STEPS,
            Snapshots.steps(start = "2026-08-10T09:00:00", end = "2026-08-10T10:00:00", observedAt = OBSERVED, steps = 1000),
            Snapshots.steps(start = "2026-08-10T14:00:00", end = "2026-08-10T15:00:00", observedAt = OBSERVED, steps = 500),
            Snapshots.steps(start = "2026-08-11T09:00:00", end = "2026-08-11T10:00:00", observedAt = OBSERVED, steps = 2000),
            Snapshots.steps(start = "2026-08-13T08:00:00", end = "2026-08-13T09:00:00", observedAt = OBSERVED, steps = 300),
        )
        snapshots(
            Snapshots.ACTIVITY_SUMMARY,
            Snapshots.activitySummary(start = "2026-08-10T09:00:00", end = "2026-08-10T10:00:00", observedAt = OBSERVED),
            Snapshots.activitySummary(start = "2026-08-11T09:00:00", end = "2026-08-11T10:00:00", observedAt = OBSERVED),
        )
        snapshots(Snapshots.STEPS_GOAL, goal("2026-08-10", "2026-08-11", Snapshots.STEPS_GOAL_MAPPER_VERSION) { put("steps", 6000) })
        snapshots(Snapshots.STEPS_GOAL, goal("2026-08-11", "2026-08-12", Snapshots.STEPS_GOAL_MAPPER_VERSION) { put("steps", 8000) })
        snapshots(
            "active_time_goal",
            goal("2026-08-10", "2026-08-11", "samsung-health-active-time-goal/2") { quantity("activeTime", 3600, "s") },
        )
        snapshots(
            "active_calories_burned_goal",
            goal("2026-08-10", "2026-08-11", "samsung-health-active-calories-burned-goal/2") {
                quantity("activeCaloriesBurned", 400, "kcal")
            },
        )
        // 22:30 of the 10th in São Paulo, already the 11th in UTC.
        records(
            ConsumptionEnvelopes.FLOORS_CLIMBED_RECORD_TYPE,
            ConsumptionEnvelopes.floorsClimbed(start = "2026-08-11T01:30:00Z", end = "2026-08-11T01:40:00Z", observedAt = OBSERVED),
        )
    }

    private fun goal(
        day: String,
        nextDay: String,
        mapperVersion: String,
        content: JsonObjectBuilder.() -> Unit,
    ): JsonObject = Snapshots.goal(
        day = day,
        nextDay = nextDay,
        observedAt = OBSERVED,
        mapperVersion = mapperVersion,
        content = buildJsonObject(content),
    )

    private fun JsonObjectBuilder.quantity(name: String, value: Number, unit: String) {
        putJsonObject(name) {
            put("value", value)
            put("unit", unit)
        }
    }

    private fun snapshots(snapshotType: String, vararg items: JsonObject) =
        accepted(api.postBatch(Snapshots.batch(snapshotType = snapshotType, items = items.toList()), token), items.size)

    private fun records(recordType: String, vararg items: JsonObject) =
        accepted(api.postBatch(Envelopes.batch(recordType = recordType, items = items.toList()), token), items.size)

    private fun accepted(response: IngestionApi.Response, count: Int) {
        assertEquals(200, response.status, response.body)
        assertEquals(List(count) { "accepted" }, statuses(response.body), response.body)
    }

    private fun JsonObject.title(): String = this["title"]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.refId(): String = this["refId"]!!.jsonPrimitive.content

    private fun JsonObject.readModelTargets(): List<JsonObject> =
        (this["targets"]?.jsonArray.orEmpty()).map { it.jsonObject }.filter { target ->
            val datasource = target["datasource"] ?: this["datasource"]
            datasource.uid() == PlatformDashboards.DATASOURCE_UID
        }

    private fun JsonElement?.uid(): String? = when (this) {
        is JsonObject -> this["uid"]?.jsonPrimitive?.content
        null, JsonNull -> null
        else -> jsonPrimitive.content
    }

    private fun combinations(variables: Map<String, List<String>>): List<Map<String, String>> =
        variables.entries.fold(listOf(emptyMap())) { combinations, (name, values) ->
            combinations.flatMap { combination -> values.map { combination + (name to it) } }
        }

    private companion object {
        const val DATABASE_HOST = "postgres"
        const val READER_PASSWORD = "dashboard-reader"
        const val OBSERVED = "2026-08-20T12:00:00Z"

        fun localMidnight(day: String): Instant = Instant.parse("${day}T03:00:00Z")

        /** The 9th to the 16th of August in São Paulo, a Sunday to a Sunday, with the 10th to the 13th seeded. */
        val SEEDED = Grafana.TimeRange(localMidnight("2026-08-09"), localMidnight("2026-08-17"))
    }
}
