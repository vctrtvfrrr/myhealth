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
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
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
            val variables = dashboard.customVariables + dashboard.givenVariables.associateWith { name ->
                listOf(
                    GIVEN_VARIABLES[localUid]?.get(name)
                        ?: error("\$$name of $localUid has no value to test with: give it one in GIVEN_VARIABLES"),
                )
            }
            dashboard.panels.filter { it.type() !in PANELS_WITHOUT_QUERY }.flatMap { panel ->
                val name = "$localUid / ${panel.title()}"
                val targets = panel.targets()
                if (targets.isEmpty()) return@flatMap listOf(dynamicTest(name) { fail<Unit>("the panel runs no query") })
                targets.flatMap { target ->
                    val datasource = target.datasourceUid(panel)
                    if (datasource != PlatformDashboards.DATASOURCE_UID) {
                        return@flatMap listOf(
                            dynamicTest("$name / ${target.refId()}") {
                                assumeTrue(false, "$datasource is not reachable here: check this query in the preview")
                            },
                        )
                    }
                    combinations(variables).map { values ->
                        dynamicTest("$name / ${target.refId()} / $values") {
                            val result = grafana.query(target, SEEDED, values)

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
    fun `a month sums the daily goals of its days`() {
        assertEquals(
            listOf(listOf("2026-08-01T03:00:00Z", 3800.0, 14000.0)),
            series("atividade", "Passos", "month", SEEDED, "Passos", "Meta"),
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
    fun `active calories are set beside their own goal`() {
        assertEquals(
            listOf("2026-08-10T03:00:00Z", 42.5, 400.0),
            series("atividade", "Calorias ativas", "day", SEEDED, "Calorias ativas", "Meta").first(),
        )
    }

    @Test
    fun `floors fall on the local day they were climbed on, and a day without them has no row`() {
        assertEquals(
            listOf(listOf("2026-08-10T03:00:00Z", 3.0)),
            series("atividade", "Andares", "day", SEEDED, "Andares"),
        )
        assertEquals(
            emptyList<List<Any?>>(),
            series("atividade", "Andares", "day", Grafana.TimeRange(localMidnight("2026-08-11"), SEEDED.to), "Andares"),
        )
    }

    @Test
    fun `a night falls on the local day it ends, summing every sleep of that day beside the goal of that day`() {
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 28800.0, 28800.0),
                listOf("2026-08-12T03:00:00Z", 23400.0, 27000.0),
                listOf("2026-08-14T03:00:00Z", 25200.0, null),
            ),
            series("sono", "Duração", "day", SEEDED, "Duração", "Meta"),
        )
    }

    @Test
    fun `sleep counts an hour as a day`() {
        assertEquals(
            series("sono", "Duração", "day", SEEDED, "Duração", "Meta"),
            series("sono", "Duração", "hour", SEEDED, "Duração", "Meta"),
        )
    }

    @Test
    fun `a week of sleep averages its days with sleep, and its goal averages the target of those same days`() {
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", 25800.0, 27900.0)),
            series("sono", "Duração", "week", SEEDED, "Duração", "Meta"),
        )
        assertEquals(
            listOf(listOf("2026-08-01T03:00:00Z", 25800.0, 27900.0)),
            series("sono", "Duração", "month", SEEDED, "Duração", "Meta"),
        )
    }

    @Test
    fun `the score and the times of a day come from its longest sleep, a bedtime before noon counting a day later`() {
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 82.0),
                listOf("2026-08-12T03:00:00Z", 70.0),
                listOf("2026-08-14T03:00:00Z", 76.0),
            ),
            series("sono", "Sleep score", "day", SEEDED, "Sleep score"),
        )
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 82800.0, 82800.0),
                listOf("2026-08-12T03:00:00Z", 88200.0, 81000.0),
                listOf("2026-08-14T03:00:00Z", 82800.0, null),
            ),
            series("sono", "Deitar", "day", SEEDED, "Deitar", "Meta"),
        )
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 23400.0, 25200.0),
                listOf("2026-08-12T03:00:00Z", 25200.0, 21600.0),
                listOf("2026-08-14T03:00:00Z", 21600.0, null),
            ),
            series("sono", "Acordar", "day", SEEDED, "Acordar", "Meta"),
        )
    }

    @Test
    fun `the period of sleep counts the nights on goal out of the nights that had one`() {
        val period = table("sono", "Período", SEEDED).single()

        assertEquals(
            listOf(25800.0, 76.0, 84600.0, 23400.0),
            listOf("Duração média", "Score médio", "Deitar médio", "Acordar médio").map { period.getValue(it)!!.toDouble() },
        )
        assertEquals("1 de 2", period["Noites na meta"])
    }

    @Test
    fun `the time in each stage sums the stages of the day of the night, and a week averages it over its days with sleep`() {
        val stages = arrayOf("Acordado", "REM", "Leve", "Profundo", "Indefinido")
        assertEquals(
            listOf(listOf("2026-08-10T03:00:00Z", 600.0, 3600.0, 6600.0, 3600.0, 600.0)),
            series("sono", "Tempo por estágio", "day", Grafana.TimeRange(localMidnight("2026-08-10"), localMidnight("2026-08-11")), *stages),
        )
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", 200.0, 1200.0, 3400.0, 1200.0, 200.0)),
            series("sono", "Tempo por estágio", "week", SEEDED, *stages),
        )
    }

    @Test
    fun `each night links to its own detail, thirty minutes wider on each side`() {
        val from = Instant.parse("2026-08-10T01:30:00Z").toEpochMilli()
        val to = Instant.parse("2026-08-10T10:00:00Z").toEpochMilli()
        val night = table("sono", "Noites", SEEDED).single { it["samsung_uid"] == "night-10" }

        assertEquals(listOf("10/08/2026", from.toString(), to.toString()), listOf(night["Dia"], night["from"], night["to"]))
        assertEquals(
            listOf("2026-08-10T03:00:00Z", from.toDouble(), to.toDouble()),
            series("sono", "Duração", "day", SEEDED, "from", "to").first(),
        )
        assertEquals("night-10", rowsOf("sono", "Duração", SEEDED, mapOf("agrupar" to "day")).first()["samsung_uid"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sleep apnea shows its whole history, whatever the period`() {
        assertEquals(
            listOf("Indefinido", "Não detectado", "Detectado"),
            table("sono", "Histórico de apneia", Grafana.TimeRange(localMidnight("2026-09-01"), localMidnight("2026-09-02"))).map { it["Resultado"] },
        )
    }

    @Test
    fun `the detail of a night is on the day the night ends, and each stage runs in its own field until it ends`() {
        assertEquals("10/08/2026", table("noite-de-sono", "Noite", NIGHT).single()["Dia"])
        assertEquals(
            listOf(
                listOf("2026-08-10T02:00:00Z", null, "Acordado", null, null, null),
                listOf("2026-08-10T02:10:00Z", null, null, null, null, null),
                listOf("2026-08-10T02:10:00Z", null, null, null, "Leve", null),
                listOf("2026-08-10T04:00:00Z", null, null, null, null, null),
                listOf("2026-08-10T04:00:00Z", null, null, null, null, "Profundo"),
                listOf("2026-08-10T05:00:00Z", null, null, null, null, null),
                listOf("2026-08-10T05:00:00Z", null, null, "REM", null, null),
                listOf("2026-08-10T06:00:00Z", null, null, null, null, null),
                listOf("2026-08-10T06:00:00Z", "Indefinido", null, null, null, null),
                listOf("2026-08-10T06:10:00Z", null, null, null, null, null),
            ),
            table("noite-de-sono", "Hipnograma", NIGHT).map { row ->
                listOf(Instant.ofEpochMilli(row.getValue("time")!!.toLong()).toString()) +
                    listOf("Indefinido", "Acordado", "REM", "Leve", "Profundo").map { row[it] }
            },
        )
    }

    @Test
    fun `the oxygen and skin readings of a night are those measured through it, before midnight included`() {
        assertEquals(
            listOf(listOf("2026-08-10T02:30:00Z", 95.0), listOf("2026-08-10T05:00:00Z", 97.0)),
            series("noite-de-sono", "SpO2", "day", WIDE_NIGHT, "SpO2"),
        )
        assertEquals(
            listOf(listOf("2026-08-10T02:30:00Z", 33.2)),
            series("noite-de-sono", "Temperatura da pele", "day", WIDE_NIGHT, "Temperatura da pele"),
        )
    }

    private fun series(
        dashboard: String,
        panel: String,
        grouping: String,
        range: Grafana.TimeRange,
        vararg fields: String,
    ): List<List<Any?>> = rowsOf(dashboard, panel, range, mapOf("agrupar" to grouping)).map { row ->
        listOf(Instant.ofEpochMilli(row.entries.first { it.key.equals("time", ignoreCase = true) }.value.jsonPrimitive.longOrNull!!).toString()) +
            fields.map { field -> row.getValue(field).takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull }
    }

    private fun table(dashboard: String, panel: String, range: Grafana.TimeRange): List<Map<String, String?>> =
        rowsOf(dashboard, panel, range, mapOf("agrupar" to "day")).map { row ->
            row.mapValues { (_, value) -> value.takeUnless { it is JsonNull }?.jsonPrimitive?.content }
        }

    private fun rowsOf(
        dashboard: String,
        panel: String,
        range: Grafana.TimeRange,
        variables: Map<String, String>,
    ): List<Map<String, JsonElement>> {
        val found = grafana.dashboard(dashboard).panel(panel)
        val target = found.targets().single { it.datasourceUid(found) == PlatformDashboards.DATASOURCE_UID }
        val result = grafana.query(target, range, variables + GIVEN_VARIABLES[dashboard].orEmpty())
        assertNull(result.error, result.error)
        return result.rows
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
        // 23:00 of the 9th to 06:30 of the 10th in São Paulo, the 10th's night, then a nap that afternoon.
        records(
            Envelopes.SLEEP_RECORD_TYPE,
            night(
                "night-10",
                start = "2026-08-10T02:00:00Z",
                end = "2026-08-10T09:30:00Z",
                duration = "27000",
                score = "82",
                Envelopes.sleepStage(start = "2026-08-10T02:00:00Z", end = "2026-08-10T02:10:00Z", stage = "AWAKE"),
                Envelopes.sleepStage(start = "2026-08-10T02:10:00Z", end = "2026-08-10T04:00:00Z", stage = "LIGHT"),
                Envelopes.sleepStage(start = "2026-08-10T04:00:00Z", end = "2026-08-10T05:00:00Z", stage = "DEEP"),
                Envelopes.sleepStage(start = "2026-08-10T05:00:00Z", end = "2026-08-10T06:00:00Z", stage = "REM"),
                Envelopes.sleepStage(start = "2026-08-10T06:00:00Z", end = "2026-08-10T06:10:00Z", stage = "UNDEFINED"),
            ),
            night("nap-10", start = "2026-08-10T17:00:00Z", end = "2026-08-10T17:30:00Z", duration = null, score = "60"),
            night(
                "night-12",
                start = "2026-08-12T03:30:00Z",
                end = "2026-08-12T10:00:00Z",
                duration = "23400",
                score = "70",
                Envelopes.sleepStage(start = "2026-08-12T04:00:00Z", end = "2026-08-12T05:00:00Z", stage = "LIGHT"),
            ),
            night("night-14", start = "2026-08-14T02:00:00Z", end = "2026-08-14T09:00:00Z", duration = "25200", score = "76"),
        )
        snapshots("sleep_goal", sleepGoal("2026-08-10", "2026-08-11", bedTime = "23:00", wakeUpTime = "07:00"))
        snapshots("sleep_goal", sleepGoal("2026-08-11", "2026-08-12", bedTime = "23:00", wakeUpTime = "09:00"))
        snapshots("sleep_goal", sleepGoal("2026-08-12", "2026-08-13", bedTime = "22:30", wakeUpTime = "06:00"))
        records(
            BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE,
            BinnedEnvelopes.bloodOxygen(
                samsungUid = "oxygen-night-10",
                start = "2026-08-10T02:30:00Z",
                end = "2026-08-10T05:10:00Z",
                series = listOf(
                    BinnedEnvelopes.oxygenBin(start = "2026-08-10T02:30:00Z", end = "2026-08-10T02:40:00Z", saturation = "95"),
                    BinnedEnvelopes.oxygenBin(start = "2026-08-10T05:00:00Z", end = "2026-08-10T05:10:00Z", saturation = "97"),
                ),
            ),
            BinnedEnvelopes.bloodOxygen(
                samsungUid = "oxygen-day-10",
                start = "2026-08-10T15:00:00Z",
                end = "2026-08-10T15:10:00Z",
                series = listOf(BinnedEnvelopes.oxygenBin(start = "2026-08-10T15:00:00Z", end = "2026-08-10T15:10:00Z")),
            ),
        )
        records(
            BinnedEnvelopes.SKIN_TEMPERATURE_RECORD_TYPE,
            BinnedEnvelopes.skinTemperature(
                samsungUid = "skin-night-10",
                start = "2026-08-10T02:30:00Z",
                end = "2026-08-10T02:40:00Z",
                series = listOf(
                    BinnedEnvelopes.skinTemperatureBin(start = "2026-08-10T02:30:00Z", end = "2026-08-10T02:40:00Z", temperature = "33.2"),
                ),
            ),
        )
        records(
            MonitorEnvelopes.SLEEP_APNEA_RECORD_TYPE,
            MonitorEnvelopes.sleepApnea("apnea-detected", "DETECTED", start = "2026-08-08T02:00:00Z", end = "2026-08-08T09:00:00Z"),
            MonitorEnvelopes.sleepApnea("apnea-not-detected", "NOT_DETECTED", start = "2026-08-09T02:00:00Z", end = "2026-08-09T09:00:00Z"),
            MonitorEnvelopes.sleepApnea("apnea-undefined", "UNDEFINED", start = "2026-08-10T02:00:00Z", end = "2026-08-10T09:00:00Z"),
        )
    }

    private fun night(
        samsungUid: String,
        start: String,
        end: String,
        duration: String?,
        score: String,
        vararg stages: JsonObject,
    ): JsonObject = Envelopes.sleep(
        samsungUid = samsungUid,
        duration = duration,
        score = score,
        start = start,
        end = end,
        sessions = listOf(Envelopes.sleepSession(start = start, end = end, durationSeconds = duration, stages = stages.toList())),
    )

    private fun sleepGoal(day: String, nextDay: String, bedTime: String, wakeUpTime: String): JsonObject =
        goal(day, nextDay, "samsung-health-sleep-goal/2") {
            put("bedTime", bedTime)
            put("wakeUpTime", wakeUpTime)
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

    private fun JsonObject.type(): String = this["type"]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.targets(): List<JsonObject> = this["targets"]?.jsonArray.orEmpty().map { it.jsonObject }

    private fun JsonObject.datasourceUid(panel: JsonObject): String? = (this["datasource"] ?: panel["datasource"]).uid()

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

        val PANELS_WITHOUT_QUERY = setOf("row", "text")

        /** The value a test gives each variable that only a link or the viewer sets, by dashboard uid. */
        val GIVEN_VARIABLES: Map<String, Map<String, String>> = mapOf("noite-de-sono" to mapOf("samsung_uid" to "night-10"))

        fun localMidnight(day: String): Instant = Instant.parse("${day}T03:00:00Z")

        /** The 9th to the 16th of August in São Paulo, a Sunday to a Sunday, with the 10th to the 13th seeded. */
        val SEEDED = Grafana.TimeRange(localMidnight("2026-08-09"), localMidnight("2026-08-17"))

        /** The night of the 10th as its link opens it, thirty minutes wider on each side. */
        val NIGHT = Grafana.TimeRange(Instant.parse("2026-08-10T01:30:00Z"), Instant.parse("2026-08-10T10:00:00Z"))

        /** Wide enough to hold the readings of the 10th's day too, which the night must still leave out. */
        val WIDE_NIGHT = Grafana.TimeRange(localMidnight("2026-08-09"), localMidnight("2026-08-11"))
    }
}
