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

    /**
     * A night carries the stages that only exist inside it: its sessions and their stages reach the read
     * model as rows of their own, projected out of the envelope the sleep was preserved in.
     */
    @Test
    fun `a night the application mapped becomes queryable, sessions and stages and all`() {
        val response = api.postBatch("""{"contractVersion":1,"recordType":"sleep","items":[$MAPPED_SLEEP]}""", token)

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))

        val sleep = row(
            """
            select duration_seconds::text, sleep_score::text, period_start, period_start_offset, source_app,
                   mapper_version
            from read_model.current_sleep
            where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-sleep",
        )
        assertEquals("25200", sleep.getValue("duration_seconds"))
        assertEquals("82", sleep.getValue("sleep_score"))
        assertEquals("-03:00", sleep.getValue("period_start_offset"))
        assertEquals("com.example.shealth", sleep.getValue("source_app"))
        assertEquals("samsung-health-sleep/1", sleep.getValue("mapper_version"))

        assertEquals(2, countOf(rowsOf("current_sleep_session", "uid-golden-sleep")))
        val session = row(
            """
            select start_at, end_at, duration_seconds::text
            from read_model.current_sleep_session
            where samsung_uid = ? and position = 2
            """.trimIndent(),
            "uid-golden-sleep",
        )
        assertEquals("14400", session.getValue("duration_seconds"))

        val stages = column(
            """
            select stage from read_model.current_sleep_stage
            where samsung_uid = ? order by session_position, position
            """.trimIndent(),
            "uid-golden-sleep",
        )
        assertEquals(listOf("LIGHT", "DEEP"), stages)
    }

    /** A removed night takes its sessions and stages with it, and none of it erases what was observed. */
    @Test
    fun `a Source Removal takes the night and its stages out of the read model`() {
        api.postBatch(
            Envelopes.batch(
                recordType = Envelopes.SLEEP_RECORD_TYPE,
                items = listOf(
                    Envelopes.sleep(
                        samsungUid = "uid-golden-removed-sleep",
                        sessions = listOf(Envelopes.sleepSession(stages = listOf(Envelopes.sleepStage()))),
                    ),
                ),
            ),
            token,
        )
        assertEquals(1, countOf(rowsOf("current_sleep_stage", "uid-golden-removed-sleep")))

        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"sleep","items":[$MAPPED_SLEEP_REMOVAL]}""",
            token,
        )

        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals(0, countOf(rowsOf("current_sleep", "uid-golden-removed-sleep")))
        assertEquals(0, countOf(rowsOf("current_sleep_session", "uid-golden-removed-sleep")))
        assertEquals(0, countOf(rowsOf("current_sleep_stage", "uid-golden-removed-sleep")))
        assertEquals(2, versionsOf("uid-golden-removed-sleep").size)
    }

    /**
     * The measurements that carry a binned series reach the read model with their series as rows of
     * their own, projected out of the envelope the measurement was preserved in.
     */
    @Test
    fun `measurements the application mapped become queryable with their binned series`() {
        listOf("blood_oxygen" to MAPPED_BLOOD_OXYGEN, "skin_temperature" to MAPPED_SKIN_TEMPERATURE, "blood_glucose" to MAPPED_BLOOD_GLUCOSE)
            .forEach { (recordType, item) ->
                val response = api.postBatch("""{"contractVersion":1,"recordType":"$recordType","items":[$item]}""", token)
                assertEquals(listOf("accepted"), statuses(response.body), recordType)
            }

        val oxygen = row(
            """
            select oxygen_saturation_percent::text, min_oxygen_saturation_percent::text, period_end_offset, mapper_version
            from read_model.current_blood_oxygen where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-blood-oxygen",
        )
        assertEquals("96", oxygen.getValue("oxygen_saturation_percent"))
        assertEquals("93", oxygen.getValue("min_oxygen_saturation_percent"))
        assertEquals("-03:00", oxygen.getValue("period_end_offset"))
        assertEquals("samsung-health-blood-oxygen/1", oxygen.getValue("mapper_version"))
        assertEquals(
            listOf("95", "97"),
            column(
                "select oxygen_saturation_percent::text from read_model.current_blood_oxygen_series where samsung_uid = ? order by position",
                "uid-golden-blood-oxygen",
            ),
        )
        assertEquals(
            listOf(null),
            column(
                "select max_oxygen_saturation_percent::text from read_model.current_blood_oxygen_series where samsung_uid = ? and position = 2",
                "uid-golden-blood-oxygen",
            ),
        )

        val skin = row(
            "select temperature_celsius::text, max_temperature_celsius::text from read_model.current_skin_temperature where samsung_uid = ?",
            "uid-golden-skin-temperature",
        )
        assertEquals("33.5", skin.getValue("temperature_celsius"))
        assertEquals("33.9", skin.getValue("max_temperature_celsius"))
        assertEquals(
            listOf("33.5"),
            column(
                """
                select temperature_celsius::text from read_model.current_skin_temperature_series
                where samsung_uid = ? and start_at = '2026-08-10T03:00:00Z' and end_at = '2026-08-10T03:10:00Z'
                """.trimIndent(),
                "uid-golden-skin-temperature",
            ),
        )

        val glucose = row(
            """
            select glucose_mmol_per_liter::text, measurement_type, sample_source_type, meal_status, meal_at, insulin_injected::text,
                medication_taken::text
            from read_model.current_blood_glucose where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-blood-glucose",
        )
        assertEquals("5.4", glucose.getValue("glucose_mmol_per_liter"))
        assertEquals("PLASMA", glucose.getValue("measurement_type"))
        assertEquals(null, glucose["sample_source_type"])
        assertEquals("AFTER_LUNCH", glucose.getValue("meal_status"))
        assertEquals("4", glucose.getValue("insulin_injected"))
        assertEquals("true", glucose.getValue("medication_taken"))
        assertEquals(
            listOf("5.4", "5.9"),
            column(
                "select glucose_mmol_per_liter::text from read_model.current_blood_glucose_series where samsung_uid = ? order by position",
                "uid-golden-blood-glucose",
            ),
        )
    }

    private fun column(sql: String, samsungUid: String): List<String?> = api.query { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
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

        const val MAPPED_SLEEP ="""{"samsungUid":"uid-golden-sleep","observedAt":{"instant":"2026-08-10T09:35:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-sleep/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T02:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"sleep_duration":25200.0,"sleep_score":82,"sessions":[{"start_time":"2026-08-10T02:00:00Z","end_time":"2026-08-10T05:00:00Z","duration":10800.0,"stages":[{"start_time":"2026-08-10T02:00:00Z","end_time":"2026-08-10T02:40:00Z","stage":"LIGHT"},{"start_time":"2026-08-10T02:40:00Z","end_time":"2026-08-10T03:30:00Z","stage":"DEEP"}]},{"start_time":"2026-08-10T05:30:00Z","end_time":"2026-08-10T09:30:00Z","duration":14400.0}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"sleep":{"duration":{"value":25200.0,"unit":"s"},"score":82,"sessions":[{"start":"2026-08-10T02:00:00Z","end":"2026-08-10T05:00:00Z","durationSeconds":10800.0,"stages":[{"start":"2026-08-10T02:00:00Z","end":"2026-08-10T02:40:00Z","stage":"LIGHT"},{"start":"2026-08-10T02:40:00Z","end":"2026-08-10T03:30:00Z","stage":"DEEP"}]},{"start":"2026-08-10T05:30:00Z","end":"2026-08-10T09:30:00Z","durationSeconds":14400.0}]}}}}"""

        const val MAPPED_SLEEP_REMOVAL ="""{"samsungUid":"uid-golden-removed-sleep","observedAt":{"instant":"2026-08-12T09:00:00Z","offset":"+00:00"},"mapperVersion":"samsung-health-sleep/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}"""

        const val EXERCISE_LOCATIONS =
            "select count(*) from read_model.current_exercise_location where samsung_uid = 'uid-golden-exercise'"
    

        const val MAPPED_BLOOD_OXYGEN = """{"samsungUid":"uid-golden-blood-oxygen","observedAt":{"instant":"2026-08-10T03:15:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-blood-oxygen/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T03:10:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"spo2":96.0,"min":93.0,"max":98.0,"binning_data":[{"oxygen_saturation":95.0,"min":93.0,"max":98.0,"start_time":"2026-08-10T03:00:00Z","end_time":"2026-08-10T03:05:00Z"},{"oxygen_saturation":97.0,"start_time":"2026-08-10T03:05:00Z","end_time":"2026-08-10T03:10:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bloodOxygen":{"saturation":{"value":96.0,"unit":"%"},"min":{"value":93.0,"unit":"%"},"max":{"value":98.0,"unit":"%"},"series":[{"start":"2026-08-10T03:00:00Z","end":"2026-08-10T03:05:00Z","saturationPercent":95.0,"minPercent":93.0,"maxPercent":98.0},{"start":"2026-08-10T03:05:00Z","end":"2026-08-10T03:10:00Z","saturationPercent":97.0}]}}}}"""

        const val MAPPED_SKIN_TEMPERATURE = """{"samsungUid":"uid-golden-skin-temperature","observedAt":{"instant":"2026-08-10T03:15:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-skin-temperature/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T03:10:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"temperature":33.5,"min":33.1,"max":33.9,"binning_data":[{"skin_temperature":33.5,"min":33.1,"max":33.9,"start_time":"2026-08-10T03:00:00Z","end_time":"2026-08-10T03:10:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"skinTemperature":{"temperature":{"value":33.5,"unit":"Cel"},"min":{"value":33.1,"unit":"Cel"},"max":{"value":33.9,"unit":"Cel"},"series":[{"start":"2026-08-10T03:00:00Z","end":"2026-08-10T03:10:00Z","temperatureCelsius":33.5,"minCelsius":33.1,"maxCelsius":33.9}]}}}}"""

        const val MAPPED_BLOOD_GLUCOSE = """{"samsungUid":"uid-golden-blood-glucose","observedAt":{"instant":"2026-08-10T11:05:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-blood-glucose/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T11:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T11:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"glucose":5.4,"measurement_type":"PLASMA","sample_source_type":"UNDEFINED","meal_type":"AFTER_LUNCH","meal_time":"2026-08-10T10:00:00Z","insulin_injected":4.0,"medication":true,"binning_data":[{"glucose":5.4,"timestamp":"2026-08-10T11:00:00Z"},{"glucose":5.9,"timestamp":"2026-08-10T11:05:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bloodGlucose":{"glucose":{"value":5.4,"unit":"mmol/L"},"measurementType":"PLASMA","mealStatus":"AFTER_LUNCH","mealTime":"2026-08-10T10:00:00Z","insulinInjected":4.0,"medicationTaken":true,"series":[{"at":"2026-08-10T11:00:00Z","glucoseMmolPerLiter":5.4},{"at":"2026-08-10T11:05:00Z","glucoseMmolPerLiter":5.9}]}}}}"""
    }
}
