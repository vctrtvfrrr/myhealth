package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The far end of the vertical: the exact document the Android application builds for a heart rate
 * record, sent to a real API, showing up in the read model view.
 *
 * The literal is pinned on the other side too, by `HeartRateEnvelopeGoldenTest` in `health-sync`.
 * Neither half can drift without one of the two failing, which is what makes this a proof about the
 * application's mapper rather than about a fixture written here.
 */
@Tag("integration")
class AppEnvelopeReadModelTest : IngestionApiTest() {

    @Test
    fun `a record the application mapped becomes queryable through the read model`() {
        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"heart_rate","items":[$MAPPED_HEART_RATE]}""",
            token,
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))

        val row = readModelRow("uid-golden")
        assertEquals("72.5", row.getValue("beats_per_minute"))
        assertEquals("/min", row.getValue("unit"))
        assertEquals("-03:00", row.getValue("period_start_offset"))
        assertEquals("com.example.shealth", row.getValue("source_app"))
        assertEquals(null, row["source_device"])
        assertEquals("samsung-health-heart-rate/1", row.getValue("mapper_version"))
    }

    /**
     * The other half of reflecting the source: the removal the application builds for a record it had
     * already delivered takes that record out of the read model without erasing what was observed of it.
     */
    @Test
    fun `a Source Removal the application mapped takes the record out of the read model`() {
        send(Envelopes.heartRate(samsungUid = "uid-golden-removed"))

        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"heart_rate","items":[$MAPPED_REMOVAL]}""",
            token,
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals(emptyList<String>(), readModelUids("uid-golden-removed"))
        assertEquals(2, versionsOf("uid-golden-removed").size)
    }

    /**
     * The exercise carries what only exists inside it: the route reaches the read model as rows of its
     * own, projected out of the envelope the exercise was preserved in.
     */
    @Test
    fun `an exercise the application mapped becomes queryable, route and all`() {
        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"exercise","items":[$MAPPED_EXERCISE]}""",
            token,
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))

        val row = row(
            """
            select exercise_type, duration_seconds::text, distance_meters::text, calories_kilocalories::text,
                   period_start_offset, source_app, mapper_version
            from read_model.current_exercise
            where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-exercise",
        )
        assertEquals("RUNNING", row.getValue("exercise_type"))
        assertEquals("1800", row.getValue("duration_seconds"))
        assertEquals("5000", row.getValue("distance_meters"))
        assertEquals("320", row.getValue("calories_kilocalories"))
        assertEquals("-03:00", row.getValue("period_start_offset"))
        assertEquals("samsung-health-exercise/1", row.getValue("mapper_version"))

        assertEquals(2, countOf(EXERCISE_LOCATIONS))
        val first = row(
            """
            select (at at time zone 'UTC')::text as at, latitude_degrees::text, longitude_degrees::text, altitude_meters::text
            from read_model.current_exercise_location
            where samsung_uid = ? and position = 1
            """.trimIndent(),
            "uid-golden-exercise",
        )
        assertEquals("2026-08-10 09:00:00", first.getValue("at"))
        assertEquals("-23.5", first.getValue("latitude_degrees"))
        assertEquals("-46.6", first.getValue("longitude_degrees"))
        assertEquals("760", first.getValue("altitude_meters"))
    }

    /** An exercise indoors is an exercise: it reaches the read model with no location beside it. */
    @Test
    fun `an exercise without a route contributes no location`() {
        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"exercise","items":[$MAPPED_INDOOR_EXERCISE]}""",
            token,
        )

        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals(
            1,
            countOf("select count(*) from read_model.current_exercise where samsung_uid = 'uid-golden-indoor'"),
        )
        assertEquals(
            0,
            countOf(
                "select count(*) from read_model.current_exercise_location where samsung_uid = 'uid-golden-indoor'",
            ),
        )
    }

    /** A removed exercise takes its route with it, and neither erases what was observed of them. */
    @Test
    fun `a Source Removal takes the exercise and its route out of the read model`() {
        api.postBatch("""{"contractVersion":1,"recordType":"exercise","items":[$MAPPED_ROUTED]}""", token)

        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"exercise","items":[$MAPPED_EXERCISE_REMOVAL]}""",
            token,
        )

        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals(0, countOf(rowsOf("current_exercise", "uid-golden-routed")))
        assertEquals(0, countOf(rowsOf("current_exercise_location", "uid-golden-routed")))
        assertEquals(2, versionsOf("uid-golden-routed").size)
    }

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun readModelUids(samsungUid: String): List<String> = api.query { connection ->
        connection.prepareStatement("select samsung_uid from read_model.current_heart_rate where samsung_uid = ?")
            .use { statement ->
                statement.setString(1, samsungUid)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
    }

    private fun readModelRow(samsungUid: String): Map<String, String?> = row(
        """
        select beats_per_minute::text, unit, period_start_offset, source_app, source_device, mapper_version
        from read_model.current_heart_rate
        where samsung_uid = ?
        """.trimIndent(),
        samsungUid,
    )

    private fun row(sql: String, samsungUid: String): Map<String, String?> = api.query { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "the read model has no row for $samsungUid" }
                (1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getString(it) }
            }
        }
    }

    private companion object {
        const val MAPPED_HEART_RATE = """{"samsungUid":"uid-golden","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T21:59:30Z","offset":"-03:00"}},"sourcePayload":{"fields":{"heart_rate":72.5,"min":58.0,"max":131.0,"binning_data":[{"heart_rate":70.0,"start_time":"2026-08-10T21:59:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"heartRate":{"value":72.5,"unit":"/min"}}}}"""

        const val MAPPED_REMOVAL = """{"samsungUid":"uid-golden-removed","observedAt":{"instant":"2026-08-12T09:00:00Z","offset":"+00:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}"""

        const val MAPPED_EXERCISE ="""{"samsungUid":"uid-golden-exercise","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"RUNNING","custom_title":"morning loop","sessions":[{"duration":1800.0,"distance":5000.0,"calories":320.0,"route":[{"timestamp":"2026-08-10T09:00:00Z","latitude":-23.5,"longitude":-46.6,"altitude":760.0,"accuracy":4.0},{"timestamp":"2026-08-10T09:15:00Z","latitude":-23.6,"longitude":-46.7}]}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"exercise":{"type":"RUNNING","duration":{"value":1800.0,"unit":"s"},"distance":{"value":5000.0,"unit":"m"},"calories":{"value":320.0,"unit":"kcal"},"route":[{"at":"2026-08-10T09:00:00Z","latitudeDegrees":-23.5,"longitudeDegrees":-46.6,"altitudeMeters":760.0,"accuracyMeters":4.0},{"at":"2026-08-10T09:15:00Z","latitudeDegrees":-23.6,"longitudeDegrees":-46.7}]}}}}"""

        const val MAPPED_INDOOR_EXERCISE ="""{"samsungUid":"uid-golden-indoor","observedAt":{"instant":"2026-08-11T07:30:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-11T07:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-11T07:25:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"YOGA","sessions":[{"duration":1500.0,"calories":90.0}]}},"normalizedPayload":{"exercise":{"type":"YOGA","duration":{"value":1500.0,"unit":"s"},"calories":{"value":90.0,"unit":"kcal"}}}}}"""

        const val MAPPED_ROUTED ="""{"samsungUid":"uid-golden-routed","observedAt":{"instant":"2026-08-09T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-09T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-09T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"BIKING"}},"normalizedPayload":{"exercise":{"type":"BIKING","route":[{"at":"2026-08-09T09:00:00Z","latitudeDegrees":-23.5,"longitudeDegrees":-46.6}]}}}}"""

        const val MAPPED_EXERCISE_REMOVAL ="""{"samsungUid":"uid-golden-routed","observedAt":{"instant":"2026-08-12T09:00:00Z","offset":"+00:00"},"mapperVersion":"samsung-health-exercise/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}"""

        const val EXERCISE_LOCATIONS =
            "select count(*) from read_model.current_exercise_location where samsung_uid = 'uid-golden-exercise'"
    }
}
