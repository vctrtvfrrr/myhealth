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
        assertEquals("58", row.getValue("min_beats_per_minute"))
        assertEquals("131", row.getValue("max_beats_per_minute"))
        assertEquals("-03:00", row.getValue("period_start_offset"))
        assertEquals("com.example.shealth", row.getValue("source_app"))
        assertEquals(null, row["source_device"])
        assertEquals("samsung-health-heart-rate/2", row.getValue("mapper_version"))

        val bin = row(
            """
            select start_at_local::text, end_at_local::text, beats_per_minute::text, min_beats_per_minute::text,
                max_beats_per_minute::text
            from read_model.current_heart_rate_series where samsung_uid = ?
            """.trimIndent(),
            "uid-golden",
        )
        assertEquals(
            listOf("2026-08-10 18:59:00", "2026-08-10 18:59:30", "70", "61", "79"),
            bin.values.toList(),
        )
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
     * The exercise carries what only exists inside it: its sessions, their logs and their legs of the route
     * reach the read model as rows of their own, projected out of the envelope the exercise was preserved
     * in.
     */
    @Test
    fun `an exercise the application mapped becomes queryable, sessions and route and all`() {
        val response = api.postBatch(
            """{"contractVersion":1,"recordType":"exercise","items":[$MAPPED_EXERCISE]}""",
            token,
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))

        val row = row(
            """
            select exercise_type, exercise_label, custom_title, duration_seconds::text, distance_meters::text,
                   calories_kilocalories::text, period_start_offset, source_app, mapper_version
            from read_model.current_exercise
            where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-exercise",
        )
        assertEquals("RUNNING", row.getValue("exercise_type"))
        assertEquals("Corrida", row.getValue("exercise_label"))
        assertEquals("morning loop", row.getValue("custom_title"))
        assertEquals("1800", row.getValue("duration_seconds"))
        assertEquals("5000", row.getValue("distance_meters"))
        assertEquals("320", row.getValue("calories_kilocalories"))
        assertEquals("-03:00", row.getValue("period_start_offset"))
        assertEquals("samsung-health-exercise/2", row.getValue("mapper_version"))

        val second = row(
            """
            select start_at_local::text, end_at_local::text, exercise_label, duration_seconds::text, comment
            from read_model.current_exercise_session
            where samsung_uid = ? and position = 2
            """.trimIndent(),
            "uid-golden-exercise",
        )
        assertEquals(
            listOf("2026-08-10 06:12:00", "2026-08-10 06:30:00", "Caminhada", "1080", "cool down"),
            second.values.toList(),
        )

        val log = row(
            """
            select at_local::text, heart_rate_beats_per_minute::text, speed_meters_per_second::text, power_watts::text
            from read_model.current_exercise_log
            where samsung_uid = ? and session_position = 1
            """.trimIndent(),
            "uid-golden-exercise",
        )
        assertEquals(listOf("2026-08-10 06:01:00", "128", "6.25", null), log.values.toList())

        assertEquals(2, countOf(EXERCISE_LOCATIONS))
        val first = row(
            """
            select (at at time zone 'UTC')::text as at, latitude_degrees::text, longitude_degrees::text, altitude_meters::text
            from read_model.current_exercise_location
            where samsung_uid = ? and session_position = 1 and position = 1
            """.trimIndent(),
            "uid-golden-exercise",
        )
        assertEquals("2026-08-10 09:00:00", first.getValue("at"))
        assertEquals("-23.5", first.getValue("latitude_degrees"))
        assertEquals("-46.6", first.getValue("longitude_degrees"))
        assertEquals("760", first.getValue("altitude_meters"))
    }

    @Test
    fun `a swim the application mapped becomes queryable, intervals and all`() {
        val response = api.postBatch("""{"contractVersion":1,"recordType":"exercise","items":[$MAPPED_SWIM]}""", token)

        assertEquals(listOf("accepted"), statuses(response.body))
        val session = row(
            """
            select exercise_label, count::text, count_type, pool_length_meters::text, swimming_distance_meters::text,
                   swimming_duration_seconds::text
            from read_model.current_exercise_session
            where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-swim",
        )
        assertEquals(listOf(null, "720", "STROKE", "25", "1000", "1500"), session.values.toList())

        val interval = row(
            """
            select duration_seconds::text, stroke_type, stroke_count::text, interval_number::text
            from read_model.current_exercise_swimming_interval
            where samsung_uid = ? and position = 2
            """.trimIndent(),
            "uid-golden-swim",
        )
        assertEquals(listOf("101.25", "BACKSTROKE", "40", "2"), interval.values.toList())
        assertEquals(
            0,
            countOf("select count(*) from read_model.current_exercise_location where samsung_uid = 'uid-golden-swim'"),
        )
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

    @Test
    fun `meals, water and climbs the application mapped become queryable`() {
        listOf("nutrition" to MAPPED_NUTRITION, "water_intake" to MAPPED_WATER_INTAKE, "floors_climbed" to MAPPED_FLOORS_CLIMBED)
            .forEach { (recordType, item) ->
                val response = api.postBatch("""{"contractVersion":1,"recordType":"$recordType","items":[$item]}""", token)
                assertEquals(listOf("accepted"), statuses(response.body), recordType)
            }

        val meal = row(
            """
            select meal_type, title, calories_kcal::text, total_fat_g::text, protein_g::text, sodium_mg::text, vitamin_a_ug::text,
                sugar_g::text, period_start_offset, mapper_version
            from read_model.current_nutrition where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-nutrition",
        )
        assertEquals("LUNCH", meal.getValue("meal_type"))
        assertEquals("Synthetic lunch", meal.getValue("title"))
        assertEquals("640", meal.getValue("calories_kcal"))
        assertEquals("21", meal.getValue("total_fat_g"))
        assertEquals("32.5", meal.getValue("protein_g"))
        assertEquals("780", meal.getValue("sodium_mg"))
        assertEquals("120", meal.getValue("vitamin_a_ug"))
        assertEquals(null, meal["sugar_g"])
        assertEquals("-03:00", meal.getValue("period_start_offset"))
        assertEquals("samsung-health-nutrition/1", meal.getValue("mapper_version"))

        val water = row(
            "select amount_ml::text, source_app from read_model.current_water_intake where samsung_uid = ?",
            "uid-golden-water-intake",
        )
        assertEquals("250", water.getValue("amount_ml"))
        assertEquals("com.example.shealth", water.getValue("source_app"))

        val climb = row(
            "select floors::text from read_model.current_floors_climbed where samsung_uid = ?",
            "uid-golden-floors-climbed",
        )
        assertEquals("3", climb.getValue("floors"))
    }

    @Test
    fun `notifications, assessments and scores the application mapped become queryable`() {
        listOf(
            "irregular_heart_rhythm" to MAPPED_IRREGULAR_HEART_RHYTHM,
            "sleep_apnea" to MAPPED_SLEEP_APNEA,
            "energy_score" to MAPPED_ENERGY_SCORE,
        ).forEach { (recordType, item) ->
            val response = api.postBatch("""{"contractVersion":1,"recordType":"$recordType","items":[$item]}""", token)
            assertEquals(listOf("accepted"), statuses(response.body), recordType)
        }

        val notification = row(
            """
            select status, detected_at, detected_at_offset, mapper_version
            from read_model.current_irregular_heart_rhythm where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-irregular-heart-rhythm",
        )
        assertEquals("DETECTED", notification.getValue("status"))
        assertEquals("-03:00", notification.getValue("detected_at_offset"))
        assertEquals("samsung-health-irregular-heart-rhythm/1", notification.getValue("mapper_version"))

        val assessment = row(
            "select result, period_end_offset from read_model.current_sleep_apnea where samsung_uid = ?",
            "uid-golden-sleep-apnea",
        )
        assertEquals(null, assessment["result"])
        assertEquals("-03:00", assessment.getValue("period_end_offset"))

        val score = row(
            "select local_date::text, score::text from read_model.current_energy_score where samsung_uid = ?",
            "uid-golden-energy-score",
        )
        assertEquals("2026-08-10", score.getValue("local_date"))
        assertEquals("82", score.getValue("score"))
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
        select beats_per_minute::text, unit, min_beats_per_minute::text, max_beats_per_minute::text,
            period_start_offset, source_app, source_device, mapper_version
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
        const val MAPPED_HEART_RATE = """{"samsungUid":"uid-golden","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/2","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T21:59:30Z","offset":"-03:00"}},"sourcePayload":{"fields":{"heart_rate":72.5,"min":58.0,"max":131.0,"binning_data":[{"heart_rate":70.0,"min":61.0,"max":79.0,"start_time":"2026-08-10T21:59:00Z","end_time":"2026-08-10T21:59:30Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"heartRate":{"rate":{"value":72.5,"unit":"/min"},"min":{"value":58.0,"unit":"/min"},"max":{"value":131.0,"unit":"/min"},"series":[{"start":"2026-08-10T21:59:00Z","end":"2026-08-10T21:59:30Z","rateBeatsPerMinute":70.0,"minBeatsPerMinute":61.0,"maxBeatsPerMinute":79.0}]}}}}"""

        const val MAPPED_REMOVAL = """{"samsungUid":"uid-golden-removed","observedAt":{"instant":"2026-08-12T09:00:00Z","offset":"+00:00"},"mapperVersion":"samsung-health-heart-rate/2","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}"""

        const val MAPPED_EXERCISE =
            """{"samsungUid":"uid-golden-exercise","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/2","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"RUNNING","custom_title":"morning loop","sessions":[{"start_time":"2026-08-10T09:00:00Z","end_time":"2026-08-10T09:12:00Z","duration":720.0,"distance":2000.0,"calories":130.0,"route":[{"timestamp":"2026-08-10T09:00:00Z","latitude":-23.5,"longitude":-46.6,"altitude":760.0,"accuracy":4.0}],"exercise_type":"RUNNING","mean_speed":2.75,"max_speed":4.5,"mean_heart_rate":141.0,"auto_detected":false,"log":[{"timestamp":"2026-08-10T09:01:00Z","heart_rate":128.0,"cadence":82.0,"count":3,"speed":6.25}]},{"start_time":"2026-08-10T09:12:00Z","end_time":"2026-08-10T09:30:00Z","duration":1080.0,"distance":3000.0,"calories":190.0,"route":[{"timestamp":"2026-08-10T09:15:00Z","latitude":-23.6,"longitude":-46.7}],"exercise_type":"WALKING","comment":"cool down"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"exercise":{"type":"RUNNING","customTitle":"morning loop","duration":{"value":1800.0,"unit":"s"},"distance":{"value":5000.0,"unit":"m"},"calories":{"value":320.0,"unit":"kcal"},"sessions":[{"start":"2026-08-10T09:00:00Z","end":"2026-08-10T09:12:00Z","type":"RUNNING","durationSeconds":720.0,"caloriesKilocalories":130.0,"distanceMeters":2000.0,"meanSpeedMetersPerSecond":2.75,"maxSpeedMetersPerSecond":4.5,"meanHeartRateBeatsPerMinute":141.0,"autoDetected":false,"log":[{"at":"2026-08-10T09:01:00Z","heartRateBeatsPerMinute":128.0,"cadencePerMinute":82.0,"count":3,"speedMetersPerSecond":6.25}],"route":[{"at":"2026-08-10T09:00:00Z","latitudeDegrees":-23.5,"longitudeDegrees":-46.6,"altitudeMeters":760.0,"accuracyMeters":4.0}]},{"start":"2026-08-10T09:12:00Z","end":"2026-08-10T09:30:00Z","type":"WALKING","durationSeconds":1080.0,"caloriesKilocalories":190.0,"distanceMeters":3000.0,"comment":"cool down","route":[{"at":"2026-08-10T09:15:00Z","latitudeDegrees":-23.6,"longitudeDegrees":-46.7}]}]}}}}"""

        const val MAPPED_SWIM =
            """{"samsungUid":"uid-golden-swim","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-exercise/2","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"exercise_type":"POOL_SWIMMING","sessions":[{"start_time":"2026-08-10T09:00:00Z","end_time":"2026-08-10T09:30:00Z","duration":1800.0,"distance":1000.0,"calories":320.0,"count_type":"STROKE","count":720,"swimming_log":{"pool_length":25,"pool_length_unit":"meter","total_distance":1000.0,"total_duration":1500.0,"swimming_intervals":[{"duration":95.5,"stroke_type":"FREESTYLE","stroke_count":36,"interval":1},{"duration":101.25,"stroke_type":"BACKSTROKE","stroke_count":40,"interval":2}]}}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"exercise":{"type":"POOL_SWIMMING","duration":{"value":1800.0,"unit":"s"},"distance":{"value":1000.0,"unit":"m"},"calories":{"value":320.0,"unit":"kcal"},"sessions":[{"start":"2026-08-10T09:00:00Z","end":"2026-08-10T09:30:00Z","countType":"STROKE","durationSeconds":1800.0,"count":720,"caloriesKilocalories":320.0,"distanceMeters":1000.0,"swimming":{"poolLengthMeters":25,"totalDistanceMeters":1000.0,"totalDurationSeconds":1500.0,"intervals":[{"durationSeconds":95.5,"strokeType":"FREESTYLE","strokeCount":36,"interval":1},{"durationSeconds":101.25,"strokeType":"BACKSTROKE","strokeCount":40,"interval":2}]}}]}}}}"""

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

        const val MAPPED_NUTRITION = """{"samsungUid":"uid-golden-nutrition","observedAt":{"instant":"2026-08-10T15:02:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-nutrition/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T15:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T15:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"meal_type":"LUNCH","title":"Synthetic lunch","calories":640.0,"total_fat":21.0,"protein":32.5,"sodium":780.0,"vitamin_a":120.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"nutrition":{"mealType":"LUNCH","title":"Synthetic lunch","calories":{"value":640.0,"unit":"kcal"},"totalFat":{"value":21.0,"unit":"g"},"protein":{"value":32.5,"unit":"g"},"sodium":{"value":780.0,"unit":"mg"},"vitaminA":{"value":120.0,"unit":"ug"}}}}}"""

        const val MAPPED_WATER_INTAKE = """{"samsungUid":"uid-golden-water-intake","observedAt":{"instant":"2026-08-10T12:31:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-water-intake/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T12:30:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T12:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"amount":250.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"waterIntake":{"amount":{"value":250.0,"unit":"mL"}}}}}"""

        const val MAPPED_FLOORS_CLIMBED = """{"samsungUid":"uid-golden-floors-climbed","observedAt":{"instant":"2026-08-10T09:11:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-floors-climbed/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T09:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:10:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"floor":3.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"floorsClimbed":{"floors":3.0}}}}"""

        const val MAPPED_IRREGULAR_HEART_RHYTHM = """{"samsungUid":"uid-golden-irregular-heart-rhythm","observedAt":{"instant":"2026-08-10T04:25:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-irregular-heart-rhythm/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T04:20:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T04:20:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"status":"DETECTED"},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"irregularHeartRhythm":{"status":"DETECTED"}}}}"""

        const val MAPPED_SLEEP_APNEA = """{"samsungUid":"uid-golden-sleep-apnea","observedAt":{"instant":"2026-08-10T09:45:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-sleep-apnea/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T02:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T09:30:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"result":"UNDEFINED"},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"sleepApnea":{}}}}"""

        const val MAPPED_ENERGY_SCORE = """{"samsungUid":"uid-golden-energy-score","observedAt":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-energy-score/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T03:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"total_score":82.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"energyScore":{"score":82.0}}}}"""
    }
}
