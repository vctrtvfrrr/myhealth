package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Record Local Time in the read model (ADR 0029): every instant a view carries with an offset is also
 * exposed as the wall clock of that offset, and `local_bucket` cuts such a wall clock into the hour, day,
 * week and month a panel groups by.
 */
@Tag("integration")
class RecordLocalTimeTest : IngestionApiTest() {

    /** A category whose view exposes the [instants] of its period, each as `<instant>_local`. */
    private class Category(
        val recordType: String,
        val view: String,
        val instants: List<String> = listOf("period_start", "period_end"),
        val envelope: (uid: String) -> JsonObject,
    )

    /** An offset on each side of UTC, under which a period around UTC midnight falls on another wall clock. */
    private class Zone(val name: String, val offset: String, val localStart: String, val localEnd: String)

    private val west = Zone("west", "-03:00", localStart = "2026-08-10 20:00:00", localEnd = "2026-08-10 22:00:00")
    private val east = Zone("east", "+09:30", localStart = "2026-08-11 08:30:00", localEnd = "2026-08-11 10:30:00")

    private val categories = listOf(
        Category(Envelopes.RECORD_TYPE, "current_heart_rate") { Envelopes.heartRate(samsungUid = it) },
        Category(Envelopes.EXERCISE_RECORD_TYPE, "current_exercise") { Envelopes.exercise(samsungUid = it) },
        Category(Envelopes.SLEEP_RECORD_TYPE, "current_sleep") { Envelopes.sleep(samsungUid = it, sessions = null) },
        Category(BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE, "current_blood_oxygen") {
            BinnedEnvelopes.bloodOxygen(samsungUid = it, series = null)
        },
        Category(BinnedEnvelopes.SKIN_TEMPERATURE_RECORD_TYPE, "current_skin_temperature") {
            BinnedEnvelopes.skinTemperature(samsungUid = it, series = null)
        },
        Category(BinnedEnvelopes.BLOOD_GLUCOSE_RECORD_TYPE, "current_blood_glucose") {
            BinnedEnvelopes.bloodGlucose(samsungUid = it)
        },
        Category(VitalSignEnvelopes.BLOOD_PRESSURE_RECORD_TYPE, "current_blood_pressure") {
            VitalSignEnvelopes.bloodPressure(samsungUid = it)
        },
        Category(VitalSignEnvelopes.BODY_COMPOSITION_RECORD_TYPE, "current_body_composition") {
            VitalSignEnvelopes.bodyComposition(samsungUid = it)
        },
        Category(VitalSignEnvelopes.BODY_TEMPERATURE_RECORD_TYPE, "current_body_temperature") {
            VitalSignEnvelopes.bodyTemperature(samsungUid = it)
        },
        Category(ConsumptionEnvelopes.NUTRITION_RECORD_TYPE, "current_nutrition") {
            ConsumptionEnvelopes.nutrition(samsungUid = it)
        },
        Category(ConsumptionEnvelopes.WATER_INTAKE_RECORD_TYPE, "current_water_intake") {
            ConsumptionEnvelopes.waterIntake(samsungUid = it)
        },
        Category(ConsumptionEnvelopes.FLOORS_CLIMBED_RECORD_TYPE, "current_floors_climbed") {
            ConsumptionEnvelopes.floorsClimbed(samsungUid = it)
        },
        Category(MonitorEnvelopes.SLEEP_APNEA_RECORD_TYPE, "current_sleep_apnea") {
            MonitorEnvelopes.sleepApnea(samsungUid = it)
        },
        Category(MonitorEnvelopes.IRREGULAR_HEART_RHYTHM_RECORD_TYPE, "current_irregular_heart_rhythm", listOf("detected_at")) {
            MonitorEnvelopes.irregularHeartRhythm(samsungUid = it)
        },
        Category(MonitorEnvelopes.ENERGY_SCORE_RECORD_TYPE, "current_energy_score", listOf("period_start")) {
            MonitorEnvelopes.energyScore(samsungUid = it)
        },
    )

    @Test
    fun `exposes each instant of a period at the wall clock of its own offset, across UTC midnight`() =
        categories.forEach { category ->
            listOf(west, east).forEach { zone ->
                val uid = "uid-${zone.name}-${category.view}"
                val end = if (category.instants.size == 1) START else END
                send(category.recordType, category.envelope(uid).withPeriod(START, end, zone.offset))

                val expected = listOf(zone.localStart, zone.localEnd).take(category.instants.size)
                assertEquals(
                    expected,
                    row(category.instants.map { "${it}_local::text" }, category.view, uid),
                    "${category.view} ${zone.name}",
                )
            }
        }

    @Test
    fun `shifts the end of a period by the offset of the end, not of the start`() {
        val record = Envelopes.heartRate(samsungUid = "uid-offset-changed")
            .withPeriod(START, END, startOffset = "-03:00", endOffset = "-02:00")
        send(Envelopes.RECORD_TYPE, record)

        assertEquals(
            listOf("2026-08-10 20:00:00", "2026-08-10 23:00:00"),
            row(listOf("period_start_local::text", "period_end_local::text"), "current_heart_rate", "uid-offset-changed"),
        )
    }

    @Test
    fun `appends every local column to its view as a timestamp without time zone`() {
        val expected = categories.associate { category -> category.view to category.instants.map { "${it}_local" } } +
            listOf(
                "current_sleep_session",
                "current_sleep_stage",
                "current_heart_rate_series",
                "current_blood_oxygen_series",
                "current_skin_temperature_series",
                "current_exercise_session",
            ).associateWith { listOf("start_at_local", "end_at_local") } +
            listOf("current_blood_glucose_series", "current_exercise_location", "current_exercise_log")
                .associateWith { listOf("at_local") }

        val columns = api.query { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select table_name, column_name, data_type from information_schema.columns
                    where table_schema = 'read_model'
                    order by table_name, ordinal_position
                    """,
                ).use { rows ->
                    buildList { while (rows.next()) add(Triple(rows.getString(1), rows.getString(2), rows.getString(3))) }
                }
            }
        }.groupBy({ it.first }, { it.second to it.third })

        assertEquals(expected.keys, columns.filterValues { view -> view.any { it.first.endsWith("_local") } }.keys)
        expected.forEach { (view, locals) ->
            assertEquals(
                locals.map { it to "timestamp without time zone" },
                columns.getValue(view).takeLast(locals.size),
                view,
            )
        }
    }

    @Test
    fun `places what a record carries inside it at the wall clock of the offset the record starts at`() =
        listOf(west, east).forEach { zone ->
            val uid = "uid-children-${zone.name}"
            send(
                Envelopes.SLEEP_RECORD_TYPE,
                Envelopes.sleep(
                    samsungUid = uid,
                    sessions = listOf(
                        Envelopes.sleepSession(
                            start = CHILD_START,
                            end = CHILD_END,
                            stages = listOf(Envelopes.sleepStage(start = CHILD_START, end = CHILD_END)),
                        ),
                    ),
                ).withPeriod(START, END, zone.offset, endOffset = "+00:00"),
            )
            send(
                BinnedEnvelopes.HEART_RATE_RECORD_TYPE,
                BinnedEnvelopes.heartRate(samsungUid = uid, series = listOf(BinnedEnvelopes.heartRateBin(CHILD_START, CHILD_END)))
                    .withPeriod(START, END, zone.offset, endOffset = "+00:00"),
            )
            send(
                BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE,
                BinnedEnvelopes.bloodOxygen(samsungUid = uid, series = listOf(BinnedEnvelopes.oxygenBin(CHILD_START, CHILD_END)))
                    .withPeriod(START, END, zone.offset, endOffset = "+00:00"),
            )
            send(
                BinnedEnvelopes.SKIN_TEMPERATURE_RECORD_TYPE,
                BinnedEnvelopes.skinTemperature(
                    samsungUid = uid,
                    series = listOf(BinnedEnvelopes.skinTemperatureBin(CHILD_START, CHILD_END)),
                ).withPeriod(START, END, zone.offset, endOffset = "+00:00"),
            )
            send(
                BinnedEnvelopes.BLOOD_GLUCOSE_RECORD_TYPE,
                BinnedEnvelopes.bloodGlucose(samsungUid = uid, series = listOf(BinnedEnvelopes.glucoseReading(at = CHILD_START)))
                    .withPeriod(START, END, zone.offset, endOffset = "+00:00"),
            )
            send(
                Envelopes.EXERCISE_RECORD_TYPE,
                ExerciseEnvelopes.exercise(
                    samsungUid = uid,
                    sessions = listOf(
                        ExerciseEnvelopes.session(
                            start = CHILD_START,
                            end = CHILD_END,
                            log = listOf(ExerciseEnvelopes.logEntry(at = CHILD_START)),
                            route = listOf(Envelopes.routePoint(at = CHILD_START)),
                        ),
                    ),
                ).withPeriod(START, END, zone.offset, endOffset = "+00:00"),
            )

            val localChildStart = if (zone == west) "2026-08-10 20:30:00" else "2026-08-11 09:00:00"
            val localChildEnd = if (zone == west) "2026-08-10 21:30:00" else "2026-08-11 10:00:00"
            listOf(
                "current_sleep_session",
                "current_sleep_stage",
                "current_heart_rate_series",
                "current_blood_oxygen_series",
                "current_skin_temperature_series",
                "current_exercise_session",
            ).forEach { view ->
                assertEquals(
                    listOf(localChildStart, localChildEnd),
                    row(listOf("start_at_local::text", "end_at_local::text"), view, uid),
                    "$view ${zone.name}",
                )
            }
            listOf("current_blood_glucose_series", "current_exercise_location", "current_exercise_log").forEach { view ->
                assertEquals(listOf(localChildStart), row(listOf("at_local::text"), view, uid), "$view ${zone.name}")
            }
        }

    @Test
    fun `cuts a wall clock into the start of its local hour, day, week and month`() {
        mapOf(
            ("hour" to "2026-08-11 09:47:12") to "2026-08-11 09:00:00",
            ("day" to "2026-08-11 09:47:12") to "2026-08-11 00:00:00",
            ("month" to "2026-08-31 23:59:59") to "2026-08-01 00:00:00",
            ("week" to "2026-08-11 09:47:12") to "2026-08-09 00:00:00",
            ("week" to "2026-08-09 00:00:00") to "2026-08-09 00:00:00",
            ("week" to "2026-08-15 23:59:59") to "2026-08-09 00:00:00",
            ("week" to "2026-08-16 00:00:00") to "2026-08-16 00:00:00",
            ("week" to "2026-09-02 12:00:00") to "2026-08-30 00:00:00",
        ).forEach { (input, expected) ->
            val (granularity, localTime) = input
            assertEquals(expected, bucket(granularity, localTime), "$granularity of $localTime")
        }
    }

    @Test
    fun `declares the bucket function immutable and refuses a granularity it does not know`() {
        assertEquals(
            "i",
            api.query { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select provolatile from pg_proc where oid = 'read_model.local_bucket(text, timestamp)'::regprocedure",
                    ).use { rows -> rows.next(); rows.getString(1) }
                }
            },
        )
        assertTrue(runCatching { bucket("fortnight", "2026-08-11 09:47:12") }.isFailure)
    }

    /** A Snapshot Window is already local time, so the snapshot views gain nothing. */
    @Test
    fun `leaves the snapshot views without a local column`() {
        val localColumns = api.query { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select table_name || '.' || column_name from information_schema.columns
                    where table_schema = 'read_model' and column_name like '%\_local'
                      and table_name in ('current_observed_snapshot', 'current_hourly_steps', 'user_profile',
                          'current_hourly_activity_summary', 'current_daily_goal')
                    """,
                ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

        assertEquals(emptyList<String>(), localColumns)
    }

    private fun send(recordType: String, item: JsonObject) {
        val response = api.postBatch(Envelopes.batch(recordType = recordType, items = listOf(item)), token)
        assertEquals(listOf("accepted"), statuses(response.body), "$recordType: ${response.body}")
    }

    private fun row(columns: List<String>, view: String, samsungUid: String): List<String?> = api.query { connection ->
        connection.prepareStatement("select ${columns.joinToString()} from read_model.$view where samsung_uid = ?").use {
            it.setString(1, samsungUid)
            it.executeQuery().use { rows ->
                assertTrue(rows.next(), "$view has no row for $samsungUid")
                columns.indices.map { index -> rows.getString(index + 1) }
            }
        }
    }

    private fun bucket(granularity: String, localTime: String): String = api.query { connection ->
        connection.prepareStatement("select read_model.local_bucket(?, cast(? as timestamp))::text").use {
            it.setString(1, granularity)
            it.setString(2, localTime)
            it.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
    }

    private fun JsonObject.withPeriod(
        start: String,
        end: String,
        startOffset: String,
        endOffset: String = startOffset,
    ): JsonObject {
        val period = buildJsonObject {
            putJsonObject("start") {
                put("instant", start)
                put("offset", startOffset)
            }
            putJsonObject("end") {
                put("instant", end)
                put("offset", endOffset)
            }
        }
        return JsonObject(this + ("state" to JsonObject(getValue("state").jsonObject + ("period" to period))))
    }

    private companion object {
        const val START = "2026-08-10T23:00:00Z"
        const val END = "2026-08-11T01:00:00Z"
        const val CHILD_START = "2026-08-10T23:30:00Z"
        const val CHILD_END = "2026-08-11T00:30:00Z"
    }
}
