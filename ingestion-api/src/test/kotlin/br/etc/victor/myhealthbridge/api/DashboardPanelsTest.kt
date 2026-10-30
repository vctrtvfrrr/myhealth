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
                listOf("2026-08-12T03:00:00Z", 25200.0, 27000.0),
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
            listOf(listOf("2026-08-09T03:00:00Z", 26400.0, 27900.0)),
            series("sono", "Duração", "week", SEEDED, "Duração", "Meta"),
        )
        assertEquals(
            listOf(listOf("2026-08-01T03:00:00Z", 26400.0, 27900.0)),
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
                listOf("2026-08-14T03:00:00Z", 97200.0, null),
            ),
            series("sono", "Deitar", "day", SEEDED, "Deitar", "Meta"),
        )
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 23400.0, 25200.0),
                listOf("2026-08-12T03:00:00Z", 25200.0, 21600.0),
                listOf("2026-08-14T03:00:00Z", 36000.0, null),
            ),
            series("sono", "Acordar", "day", SEEDED, "Acordar", "Meta"),
        )
    }

    @Test
    fun `the period of sleep counts the nights on goal out of the nights that had one`() {
        val period = table("sono", "Período", SEEDED).single()

        assertEquals(
            listOf(26400.0, 76.0, 89400.0, 28200.0),
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
            listOf(listOf("2026-08-09T03:00:00Z", 200.0, 1200.0, 4000.0, 1200.0, 200.0)),
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

    /** 03:00 to 10:00 of the 14th under +01:00 is opened as that wall clock in São Paulo, not as the instants. */
    @Test
    fun `a night slept under another offset links to its own wall clock in the zone of the browser`() {
        val night = table("sono", "Noites", SEEDED).single { it["samsung_uid"] == "night-14" }

        assertEquals(
            listOf("14/08/2026", Instant.parse("2026-08-14T05:30:00Z").toEpochMilli().toString(), Instant.parse("2026-08-14T13:30:00Z").toEpochMilli().toString()),
            listOf(night["Dia"], night["from"], night["to"]),
        )
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
            listOf(listOf("2026-08-10T01:55:00Z", 94.0), listOf("2026-08-10T02:30:00Z", 95.0), listOf("2026-08-10T05:00:00Z", 97.0)),
            series("noite-de-sono", "SpO2", "day", WIDE_NIGHT, "SpO2"),
        )
        assertEquals(
            listOf(listOf("2026-08-10T02:30:00Z", 33.2)),
            series("noite-de-sono", "Temperatura da pele", "day", WIDE_NIGHT, "Temperatura da pele"),
        )
    }

    @Test
    fun `heart rate averages the rates of each local day, takes its range only from the records that carry one, and leaves the days without a measurement empty`() {
        assertEquals(
            listOf(
                listOf("2026-08-09T03:00:00Z", null, null, null),
                listOf("2026-08-10T03:00:00Z", 75.0, 55.0, 120.0),
                listOf("2026-08-11T03:00:00Z", 66.0, null, null),
                listOf("2026-08-12T03:00:00Z", 80.0, 50.0, 70.0),
                listOf("2026-08-13T03:00:00Z", null, null, null),
                listOf("2026-08-14T03:00:00Z", null, null, null),
                listOf("2026-08-15T03:00:00Z", null, null, null),
                listOf("2026-08-16T03:00:00Z", null, null, null),
            ),
            series("coracao", "Frequência cardíaca", "day", SEEDED, *RANGE),
        )
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", 75.2, 50.0, 120.0), listOf("2026-08-16T03:00:00Z", null, null, null)),
            series("coracao", "Frequência cardíaca", "week", SEEDED, *RANGE),
        )
    }

    @Test
    fun `heart rate by the hour places each record on the hour it starts, every hour of the period present`() {
        val hours = series("coracao", "Frequência cardíaca", "hour", SEEDED, *RANGE)

        assertEquals(8 * 24, hours.size)
        assertEquals(
            listOf(
                listOf("2026-08-10T12:00:00Z", 70.0, 60.0, 90.0),
                listOf("2026-08-10T21:00:00Z", 80.0, 55.0, 120.0),
                listOf("2026-08-11T15:00:00Z", 66.0, null, null),
                listOf("2026-08-12T15:00:00Z", 60.0, 50.0, 70.0),
                listOf("2026-08-13T01:00:00Z", 100.0, null, null),
            ),
            hours.filter { it[1] != null },
        )
    }

    @Test
    fun `oxygen reads its records on the day they end, and their bins on the hour each starts`() {
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", null, null, null), listOf("2026-08-10T03:00:00Z", 96.0, 93.0, 98.0)),
            series("coracao", "SpO2", "day", SEEDED, *RANGE).take(2),
        )
        assertEquals(
            listOf(
                listOf("2026-08-10T01:00:00Z", 93.5, 93.0, 98.0),
                listOf("2026-08-10T02:00:00Z", 95.0, 93.0, 98.0),
                listOf("2026-08-10T05:00:00Z", 97.0, 93.0, 98.0),
                listOf("2026-08-10T15:00:00Z", 96.0, 93.0, 98.0),
            ),
            series("coracao", "SpO2", "hour", SEEDED, *RANGE).filter { it[1] != null },
        )
    }

    @Test
    fun `a week or a month of oxygen and skin temperature reads the records, not their bins`() {
        for (grouping in listOf("week", "month")) {
            assertEquals(listOf(96.0, 93.0, 98.0), series("coracao", "SpO2", grouping, SEEDED, *RANGE).first().drop(1))
            assertEquals(listOf(33.5, 33.1, 33.9), series("coracao", "Temperatura da pele", grouping, SEEDED, *RANGE).first().drop(1))
        }
    }

    @Test
    fun `skin temperature reads its records on the day they end, and their bins on the hour each starts`() {
        assertEquals(
            listOf(
                listOf("2026-08-09T03:00:00Z", 33.5, 33.1, 33.9),
                listOf("2026-08-10T03:00:00Z", 33.5, 33.1, 33.9),
                listOf("2026-08-11T03:00:00Z", null, null, null),
            ),
            series("coracao", "Temperatura da pele", "day", SEEDED, *RANGE).take(3),
        )
        assertEquals(
            listOf(listOf("2026-08-10T02:00:00Z", 33.2, 33.1, 33.9), listOf("2026-08-10T15:00:00Z", 33.5, 33.1, 33.9)),
            series("coracao", "Temperatura da pele", "hour", SEEDED, *RANGE).filter { it[1] != null },
        )
    }

    @Test
    fun `exercise time stacks by type on the local day each exercise starts, zero where there was none`() {
        assertEquals(
            listOf(
                listOf("2026-08-10T03:00:00Z", 1800.0, 0.0),
                listOf("2026-08-11T03:00:00Z", 0.0, 3600.0),
                listOf("2026-08-12T03:00:00Z", 1200.0, 0.0),
                listOf("2026-08-13T03:00:00Z", 0.0, 0.0),
                listOf("2026-08-14T03:00:00Z", 600.0, 0.0),
                listOf("2026-08-15T03:00:00Z", 0.0, 0.0),
                listOf("2026-08-16T03:00:00Z", 0.0, 0.0),
            ),
            series("exercicio", "Duração por tipo", "day", SEEDED, "Corrida", "Ciclismo"),
        )
        assertEquals(
            listOf(listOf("2026-08-09T03:00:00Z", 3600.0, 3600.0), listOf("2026-08-16T03:00:00Z", 0.0, 0.0)),
            series("exercicio", "Duração por tipo", "week", SEEDED, "Corrida", "Ciclismo"),
        )
    }

    @Test
    fun `the totals of each type pace only the exercises with a distance, whatever the grouping`() {
        val totals = table("exercicio", "Totais por tipo", SEEDED)

        assertEquals(listOf("Ciclismo", "Corrida"), totals.map { it["Tipo"] })
        val running = totals.single { it["Tipo"] == "Corrida" }
        assertEquals(
            listOf(3.0, 3600.0, 8.0, 530.0),
            listOf("Treinos", "Duração", "Distância", "Calorias").map { running.getValue(it)!!.toDouble() },
        )
        assertEquals(9.6, running.getValue("Velocidade")!!.toDouble(), 1e-9)
        assertEquals("6:15 /km", running["Ritmo"])
        assertEquals("3:00 /km", totals.single { it["Tipo"] == "Ciclismo" }["Ritmo"])
        for (grouping in listOf("hour", "week", "month")) {
            assertEquals(
                rowsOf("exercicio", "Totais por tipo", SEEDED, mapOf("agrupar" to "day")),
                rowsOf("exercicio", "Totais por tipo", SEEDED, mapOf("agrupar" to grouping)),
            )
        }
    }

    @Test
    fun `each exercise is listed from the latest, on the local time it starts, linking to its detail five minutes wider on each side`() {
        val workouts = table("exercicio", "Treinos", SEEDED)

        assertEquals(listOf("run-treadmill-14", "run-late-12", "ride-11", "run-10"), workouts.map { it["samsung_uid"] })
        val late = workouts.single { it["samsung_uid"] == "run-late-12" }
        assertEquals(
            listOf(
                "12/08/2026 22:30",
                Instant.parse("2026-08-13T01:25:00Z").toEpochMilli().toString(),
                Instant.parse("2026-08-13T01:55:00Z").toEpochMilli().toString(),
            ),
            listOf(late["Início"], late["from"], late["to"]),
        )
        val run = workouts.single { it["samsung_uid"] == "run-10" }
        assertEquals(listOf("Corrida", "morning loop", "6:00 /km"), listOf(run["Tipo"], run["Título"], run["Ritmo"]))
        assertEquals(10.0, run.getValue("Velocidade")!!.toDouble(), 1e-9)
        val treadmill = workouts.single { it["samsung_uid"] == "run-treadmill-14" }
        assertEquals(listOf(null, null), listOf(treadmill["Velocidade"], treadmill["Ritmo"]))
    }

    @Test
    fun `the detail of an exercise states its totals`() {
        val workout = table("treino", "Treino", WORKOUT).single()

        assertEquals(
            listOf("11/08/2026 09:00", "Ciclismo", "two legs", "3:00 /km"),
            listOf(workout["Início"], workout["Tipo"], workout["Título"], workout["Ritmo"]),
        )
        assertEquals(20.0, workout.getValue("Velocidade")!!.toDouble(), 1e-9)
    }

    @Test
    fun `the route runs through every point, session by session, and an exercise without one has no point`() {
        assertEquals(
            listOf(
                listOf("-23.5", "-46.6", "1"),
                listOf("-23.51", "-46.61", "1"),
                listOf("-23.52", "-46.6", "2"),
                listOf("-23.53", "-46.62", "2"),
            ),
            table("treino", "Rota", WORKOUT).map { listOf(it["latitude"], it["longitude"], it["Sessão"]) },
        )
        assertEquals(
            emptyList<Map<String, JsonElement>>(),
            rowsOf("treino", "Rota", SEEDED, mapOf("treino" to "run-treadmill-14")),
        )
    }

    @Test
    fun `the heart rate and the speed of an exercise follow its log across sessions, leaving out what an instant did not measure`() {
        assertEquals(
            listOf(listOf("2026-08-11T12:05:00Z", 120.0), listOf("2026-08-11T12:10:00Z", 130.0), listOf("2026-08-11T12:40:00Z", 140.0)),
            series("treino", "Frequência cardíaca", "day", WORKOUT, "FC"),
        )
        assertEquals(
            listOf(listOf("2026-08-11T12:05:00Z", 18.0), listOf("2026-08-11T12:40:00Z", 22.5)),
            series("treino", "Velocidade (km/h)", "day", WORKOUT, "Velocidade"),
        )
    }

    @Test
    fun `the detail of an exercise lists each session and the swim intervals of each`() {
        assertEquals(
            listOf(listOf("1", "09:00", "Ciclismo", null), listOf("2", "09:30", "Natação em piscina", "25")),
            table("treino", "Sessões", WORKOUT).map { listOf(it["Sessão"], it["Início"], it["Tipo"], it["Piscina"]) },
        )
        assertEquals(
            listOf(listOf("2", "1", "95.5", "FREESTYLE", "36"), listOf("2", "2", "101.25", "BACKSTROKE", "40")),
            table("treino", "Intervalos de natação", WORKOUT).map {
                listOf(it["Sessão"], it["Intervalo"], it["Duração"], it["Estilo"], it["Braçadas"])
            },
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
        val result = grafana.query(target, range, GIVEN_VARIABLES[dashboard].orEmpty() + variables)
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
            // 22:00 to 22:30 of the 12th in São Paulo, already the 13th in UTC.
            night(
                "nap-12",
                start = "2026-08-13T01:00:00Z",
                end = "2026-08-13T01:30:00Z",
                duration = "1800",
                score = "50",
                Envelopes.sleepStage(start = "2026-08-13T01:00:00Z", end = "2026-08-13T01:30:00Z", stage = "LIGHT"),
            ),
            night(
                "night-14",
                start = "2026-08-14T02:00:00Z",
                end = "2026-08-14T09:00:00Z",
                duration = "25200",
                score = "76",
                offset = "+01:00",
            ),
        )
        snapshots("sleep_goal", sleepGoal("2026-08-10", "2026-08-11", bedTime = "23:00", wakeUpTime = "07:00"))
        snapshots("sleep_goal", sleepGoal("2026-08-11", "2026-08-12", bedTime = "23:00", wakeUpTime = "09:00"))
        snapshots("sleep_goal", sleepGoal("2026-08-12", "2026-08-13", bedTime = "22:30", wakeUpTime = "06:00"))
        records(
            BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE,
            BinnedEnvelopes.bloodOxygen(
                samsungUid = "oxygen-night-10",
                start = "2026-08-10T01:40:00Z",
                end = "2026-08-10T05:10:00Z",
                series = listOf(
                    BinnedEnvelopes.oxygenBin(start = "2026-08-10T01:40:00Z", end = "2026-08-10T01:50:00Z", saturation = "93"),
                    BinnedEnvelopes.oxygenBin(start = "2026-08-10T01:55:00Z", end = "2026-08-10T02:05:00Z", saturation = "94"),
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
            BinnedEnvelopes.skinTemperature(
                samsungUid = "skin-day-10",
                start = "2026-08-10T15:00:00Z",
                end = "2026-08-10T15:10:00Z",
                series = listOf(BinnedEnvelopes.skinTemperatureBin(start = "2026-08-10T15:00:00Z", end = "2026-08-10T15:10:00Z")),
            ),
        )
        records(
            BinnedEnvelopes.HEART_RATE_RECORD_TYPE,
            heartRate("heart-rate-morning-10", start = "2026-08-10T12:00:00Z", rate = "70", min = "60", max = "90"),
            heartRate("heart-rate-evening-10", start = "2026-08-10T21:00:00Z", rate = "80", min = "55", max = "120"),
            heartRate("heart-rate-11", start = "2026-08-11T15:00:00Z", rate = "66", min = null, max = null),
            heartRate("heart-rate-ranged-12", start = "2026-08-12T15:00:00Z", rate = "60", min = "50", max = "70"),
            // 22:30 of the 12th in São Paulo, already the 13th in UTC, and with no range to fall back from.
            heartRate("heart-rate-unranged-12", start = "2026-08-13T01:30:00Z", rate = "100", min = null, max = null),
        )
        records(
            MonitorEnvelopes.SLEEP_APNEA_RECORD_TYPE,
            MonitorEnvelopes.sleepApnea("apnea-detected", "DETECTED", start = "2026-08-08T02:00:00Z", end = "2026-08-08T09:00:00Z"),
            MonitorEnvelopes.sleepApnea("apnea-not-detected", "NOT_DETECTED", start = "2026-08-09T02:00:00Z", end = "2026-08-09T09:00:00Z"),
            MonitorEnvelopes.sleepApnea("apnea-undefined", "UNDEFINED", start = "2026-08-10T02:00:00Z", end = "2026-08-10T09:00:00Z"),
        )
        records(
            Envelopes.EXERCISE_RECORD_TYPE,
            exercise("run-10", "RUNNING", "2026-08-10T09:00:00Z", "2026-08-10T09:30:00Z", duration = "1800", distance = "5000", calories = "320", title = "morning loop"),
            ExerciseEnvelopes.exercise(
                samsungUid = "ride-11",
                type = "BIKING",
                customTitle = "two legs",
                start = "2026-08-11T12:00:00Z",
                end = "2026-08-11T13:00:00Z",
                duration = "3600",
                distance = "20000",
                calories = "500",
                observedAt = OBSERVED,
                sessions = listOf(
                    ExerciseEnvelopes.session(
                        start = "2026-08-11T12:00:00Z",
                        end = "2026-08-11T12:30:00Z",
                        log = listOf(
                            ExerciseEnvelopes.logEntry("2026-08-11T12:05:00Z", mapOf("heartRateBeatsPerMinute" to "120", "speedMetersPerSecond" to "5")),
                            ExerciseEnvelopes.logEntry("2026-08-11T12:10:00Z", mapOf("heartRateBeatsPerMinute" to "130", "speedMetersPerSecond" to null)),
                        ),
                        route = listOf(
                            Envelopes.routePoint("2026-08-11T12:00:00Z", latitude = "-23.5", longitude = "-46.6"),
                            Envelopes.routePoint("2026-08-11T12:15:00Z", latitude = "-23.51", longitude = "-46.61"),
                        ),
                    ),
                    ExerciseEnvelopes.session(
                        start = "2026-08-11T12:30:00Z",
                        end = "2026-08-11T13:00:00Z",
                        values = mapOf("type" to "POOL_SWIMMING"),
                        log = listOf(
                            ExerciseEnvelopes.logEntry("2026-08-11T12:40:00Z", mapOf("heartRateBeatsPerMinute" to "140", "speedMetersPerSecond" to "6.25")),
                        ),
                        swimming = ExerciseEnvelopes.swimming(
                            intervals = listOf(
                                ExerciseEnvelopes.swimmingInterval("95.5", "FREESTYLE", "36", "1"),
                                ExerciseEnvelopes.swimmingInterval("101.25", "BACKSTROKE", "40", "2"),
                            ),
                        ),
                        route = listOf(
                            Envelopes.routePoint("2026-08-11T12:30:00Z", latitude = "-23.52", longitude = "-46.6"),
                            Envelopes.routePoint("2026-08-11T12:45:00Z", latitude = "-23.53", longitude = "-46.62"),
                        ),
                    ),
                ),
            ),
            // 22:30 of the 12th in São Paulo, already the 13th in UTC.
            exercise("run-late-12", "RUNNING", "2026-08-13T01:30:00Z", "2026-08-13T01:50:00Z", duration = "1200", distance = "3000", calories = "150", title = null),
            exercise("run-treadmill-14", "RUNNING", "2026-08-14T10:00:00Z", "2026-08-14T10:10:00Z", duration = "600", distance = null, calories = "60", title = null),
        )
    }

    private fun exercise(
        samsungUid: String,
        type: String,
        start: String,
        end: String,
        duration: String,
        distance: String?,
        calories: String,
        title: String?,
    ): JsonObject = ExerciseEnvelopes.exercise(
        samsungUid = samsungUid,
        type = type,
        customTitle = title,
        start = start,
        end = end,
        duration = duration,
        distance = distance,
        calories = calories,
        observedAt = OBSERVED,
        sessions = listOf(ExerciseEnvelopes.session(start = start, end = end, values = mapOf("type" to type))),
    )

    private fun night(
        samsungUid: String,
        start: String,
        end: String,
        duration: String?,
        score: String,
        vararg stages: JsonObject,
        offset: String = "-03:00",
    ): JsonObject = Envelopes.sleep(
        samsungUid = samsungUid,
        duration = duration,
        score = score,
        start = start,
        end = end,
        offset = offset,
        sessions = listOf(Envelopes.sleepSession(start = start, end = end, durationSeconds = duration, stages = stages.toList())),
    )

    private fun heartRate(samsungUid: String, start: String, rate: String, min: String?, max: String?): JsonObject =
        BinnedEnvelopes.heartRate(
            samsungUid = samsungUid,
            rate = rate,
            min = min,
            max = max,
            series = null,
            start = start,
            end = Instant.parse(start).plusSeconds(600).toString(),
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

        val RANGE = arrayOf("Média", "Mínimo", "Máximo")

        /** The value a test gives each variable that only a link or the viewer sets, by dashboard uid. */
        val GIVEN_VARIABLES: Map<String, Map<String, String>> = mapOf(
            "noite-de-sono" to mapOf("samsung_uid" to "night-10"),
            "treino" to mapOf("treino" to "ride-11"),
        )

        fun localMidnight(day: String): Instant = Instant.parse("${day}T03:00:00Z")

        /** The 9th to the 16th of August in São Paulo, a Sunday to a Sunday, with the 10th to the 13th seeded. */
        val SEEDED = Grafana.TimeRange(localMidnight("2026-08-09"), localMidnight("2026-08-17"))

        /** The night of the 10th as its link opens it, thirty minutes wider on each side. */
        val NIGHT = Grafana.TimeRange(Instant.parse("2026-08-10T01:30:00Z"), Instant.parse("2026-08-10T10:00:00Z"))

        /** Wide enough to hold the readings of the 10th's day too, which the night must still leave out. */
        val WIDE_NIGHT = Grafana.TimeRange(localMidnight("2026-08-09"), localMidnight("2026-08-11"))

        /** The exercise of the 11th as its link opens it, five minutes wider on each side. */
        val WORKOUT = Grafana.TimeRange(Instant.parse("2026-08-11T11:55:00Z"), Instant.parse("2026-08-11T13:05:00Z"))
    }
}
