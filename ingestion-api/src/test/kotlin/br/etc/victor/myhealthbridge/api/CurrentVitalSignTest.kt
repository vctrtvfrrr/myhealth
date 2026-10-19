package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bloodPressure
import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bodyComposition
import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bodyCompositionMeasures
import br.etc.victor.myhealthbridge.api.VitalSignEnvelopes.bodyTemperature
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The Current Health Record of blood pressure, body composition and body temperature, and the view each
 * one is read through, on the terms heart rate set: the latest observation wins by when it was observed,
 * not by when it arrived, and every earlier one is kept.
 *
 * The documents the application maps are pinned on the other side too, by `VitalSignEnvelopeGoldenTest`
 * in `health-sync`, so what the mappers emit is what the read model was proven to show.
 */
@Tag("integration")
class CurrentVitalSignTest : IngestionApiTest() {

    /** One of the three categories, with a reading whose headline value is the one given. */
    private class Kind(
        val recordType: String,
        val mapperVersion: String,
        val view: String,
        val valueColumn: String,
        val envelope: (uid: String, observedAt: String, value: String) -> JsonObject,
    )

    private val bloodPressureKind = Kind(
        recordType = VitalSignEnvelopes.BLOOD_PRESSURE_RECORD_TYPE,
        mapperVersion = VitalSignEnvelopes.BLOOD_PRESSURE_MAPPER_VERSION,
        view = "current_blood_pressure",
        valueColumn = "systolic_mmhg",
    ) { uid, observedAt, value -> bloodPressure(samsungUid = uid, observedAt = observedAt, systolic = value) }

    private val bodyCompositionKind = Kind(
        recordType = VitalSignEnvelopes.BODY_COMPOSITION_RECORD_TYPE,
        mapperVersion = VitalSignEnvelopes.BODY_COMPOSITION_MAPPER_VERSION,
        view = "current_body_composition",
        valueColumn = "weight_kg",
    ) { uid, observedAt, value ->
        bodyComposition(samsungUid = uid, observedAt = observedAt, measures = bodyCompositionMeasures + ("weight" to (value to "kg")))
    }

    private val bodyTemperatureKind = Kind(
        recordType = VitalSignEnvelopes.BODY_TEMPERATURE_RECORD_TYPE,
        mapperVersion = VitalSignEnvelopes.BODY_TEMPERATURE_MAPPER_VERSION,
        view = "current_body_temperature",
        valueColumn = "temperature_celsius",
    ) { uid, observedAt, value -> bodyTemperature(samsungUid = uid, observedAt = observedAt, temperature = value) }

    private val kinds = listOf(bloodPressureKind, bodyCompositionKind, bodyTemperatureKind)

    private val earlier = "2026-08-10T08:00:30Z"
    private val later = "2026-08-11T10:00:00Z"

    @Test
    fun `a blood pressure the application mapped becomes queryable`() {
        post(VitalSignEnvelopes.BLOOD_PRESSURE_RECORD_TYPE, MAPPED_BLOOD_PRESSURE)

        val row = row(
            """
            select systolic_mmhg::text, diastolic_mmhg::text, mean_mmhg::text, pulse_rate::text, medication_taken::text,
                period_start_offset, observed_at_offset, source_app, source_device, mapper_version
            from read_model.current_blood_pressure where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-blood-pressure",
        )
        assertEquals(
            mapOf(
                "systolic_mmhg" to "118",
                "diastolic_mmhg" to "76",
                "mean_mmhg" to "90",
                "pulse_rate" to "64",
                "medication_taken" to "true",
                "period_start_offset" to "-03:00",
                "observed_at_offset" to "-03:00",
                "source_app" to "com.example.shealth",
                "source_device" to "device-1",
                "mapper_version" to "samsung-health-blood-pressure/1",
            ),
            row,
        )
    }

    @Test
    fun `a body composition the application mapped becomes queryable, measure by measure`() {
        post(VitalSignEnvelopes.BODY_COMPOSITION_RECORD_TYPE, MAPPED_BODY_COMPOSITION)

        val row = row(
            """
            select weight_kg::text, height_cm::text, body_fat_percent::text, skeletal_muscle_percent::text,
                muscle_mass_percent::text, basal_metabolic_rate_kcal_per_day::text, body_fat_mass_kg::text,
                fat_free_mass_kg::text, fat_free_percent::text, skeletal_muscle_mass_kg::text, total_body_water_liters::text,
                body_mass_index::text, mapper_version
            from read_model.current_body_composition where samsung_uid = ?
            """.trimIndent(),
            "uid-golden-body-composition",
        )
        assertEquals(
            mapOf(
                "weight_kg" to "70.5",
                "height_cm" to "175",
                "body_fat_percent" to "18.5",
                "skeletal_muscle_percent" to "42",
                "muscle_mass_percent" to "45.5",
                "basal_metabolic_rate_kcal_per_day" to "1650",
                "body_fat_mass_kg" to "13",
                "fat_free_mass_kg" to "57.5",
                "fat_free_percent" to "81.5",
                "skeletal_muscle_mass_kg" to "29.6",
                "total_body_water_liters" to "42.1",
                "body_mass_index" to "23",
                "mapper_version" to "samsung-health-body-composition/1",
            ),
            row,
        )
    }

    @Test
    fun `a body temperature the application mapped becomes queryable`() {
        post(VitalSignEnvelopes.BODY_TEMPERATURE_RECORD_TYPE, MAPPED_BODY_TEMPERATURE)

        val row = row(
            "select temperature_celsius::text, mapper_version from read_model.current_body_temperature where samsung_uid = ?",
            "uid-golden-body-temperature",
        )
        assertEquals(
            mapOf("temperature_celsius" to "36.6", "mapper_version" to "samsung-health-body-temperature/1"),
            row,
        )
    }

    @Test
    fun `leaves a measure the source did not report null`() {
        send(
            bodyCompositionKind,
            bodyComposition(samsungUid = "uid-weighing-only", measures = mapOf("weight" to ("70.5" to "kg")), bodyMassIndex = null),
        )
        send(bloodPressureKind, bloodPressure(samsungUid = "uid-pressures-only", mean = null, pulseRate = null, medicationTaken = null))

        assertEquals(
            listOf(null),
            column("select height_cm::text from read_model.current_body_composition where samsung_uid = ?", "uid-weighing-only"),
        )
        assertEquals(
            listOf(null),
            column("select pulse_rate::text from read_model.current_blood_pressure where samsung_uid = ?", "uid-pressures-only"),
        )
    }

    @Test
    fun `answers a reading it already holds as already present`() = kinds.forEach { kind ->
        val uid = "uid-repeated-${kind.recordType}"
        val reading = kind.envelope(uid, earlier, "36.5")
        send(kind, reading)

        val response = send(kind, reading)

        assertEquals(listOf("already_present"), statuses(response.body), kind.recordType)
        assertEquals(1, versionsOf(uid).size, kind.recordType)
        assertEquals(1, countOf(rowsOf(kind.view, uid)), kind.recordType)
    }

    @Test
    fun `moves the current reading to a newer observation without erasing the earlier version`() = kinds.forEach { kind ->
        val uid = "uid-edited-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, "36.5"))

        send(kind, kind.envelope(uid, later, "37.25"))

        assertEquals("37.25", valueOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer reading current when an older one is ingested afterwards`() = kinds.forEach { kind ->
        val uid = "uid-late-${kind.recordType}"
        send(kind, kind.envelope(uid, later, "37.25"))

        send(kind, kind.envelope(uid, earlier, "36.5"))

        assertEquals("37.25", valueOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `takes a removed reading out of the read model, keeping what was observed`() = kinds.forEach { kind ->
        val uid = "uid-removed-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, "36.5"))

        send(kind, Envelopes.removal(samsungUid = uid, mapperVersion = kind.mapperVersion))

        assertEquals(0, countOf(rowsOf(kind.view, uid)), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `rebuilds the read model of every reading from the preserved envelopes, identically`() {
        kinds.forEach { kind ->
            val uid = "uid-rebuilt-${kind.recordType}"
            send(kind, kind.envelope(uid, earlier, "36.5"))
            send(kind, kind.envelope(uid, later, "37.25"))
        }
        val before = kinds.map { valueOf(it, "uid-rebuilt-${it.recordType}") }

        api.administer("projection", "rebuild")

        assertEquals(before, kinds.map { valueOf(it, "uid-rebuilt-${it.recordType}") })
        assertEquals(listOf("37.25", "37.25", "37.25"), before)
    }

    private fun post(recordType: String, item: String) {
        val response = api.postBatch("""{"contractVersion":1,"recordType":"$recordType","items":[$item]}""", token)
        assertEquals(listOf("accepted"), statuses(response.body), recordType)
    }

    private fun send(kind: Kind, item: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = kind.recordType, items = listOf(item)), token)

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun valueOf(kind: Kind, samsungUid: String): String? = column(
        "select ${kind.valueColumn}::text from read_model.${kind.view} where samsung_uid = ?",
        samsungUid,
    ).single()

    private fun column(sql: String, samsungUid: String): List<String?> = api.query { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

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
        const val MAPPED_BLOOD_PRESSURE = """{"samsungUid":"uid-golden-blood-pressure","observedAt":{"instant":"2026-08-10T10:01:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-blood-pressure/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T10:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"systolic":118.0,"diastolic":76.0,"mean":90.0,"pulse":64,"medication":true},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bloodPressure":{"systolic":{"value":118.0,"unit":"mm[Hg]"},"diastolic":{"value":76.0,"unit":"mm[Hg]"},"mean":{"value":90.0,"unit":"mm[Hg]"},"pulseRate":64,"medicationTaken":true}}}}"""

        const val MAPPED_BODY_COMPOSITION = """{"samsungUid":"uid-golden-body-composition","observedAt":{"instant":"2026-08-10T07:02:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-body-composition/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T07:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T07:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"weight":70.5,"height":175.0,"body_fat":18.5,"skeletal_muscle":42.0,"muscle_mass":45.5,"basal_metabolic_rate":1650,"body_fat_mass":13.0,"fat_free_mass":57.5,"fat_free":81.5,"skeletal_muscle_mass":29.6,"total_body_water":42.1,"bmi":23.0},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bodyComposition":{"weight":{"value":70.5,"unit":"kg"},"height":{"value":175.0,"unit":"cm"},"bodyFat":{"value":18.5,"unit":"%"},"skeletalMuscle":{"value":42.0,"unit":"%"},"muscleMass":{"value":45.5,"unit":"%"},"basalMetabolicRate":{"value":1650,"unit":"kcal/d"},"bodyFatMass":{"value":13.0,"unit":"kg"},"fatFreeMass":{"value":57.5,"unit":"kg"},"fatFree":{"value":81.5,"unit":"%"},"skeletalMuscleMass":{"value":29.6,"unit":"kg"},"totalBodyWater":{"value":42.1,"unit":"L"},"bodyMassIndex":23.0}}}}"""

        const val MAPPED_BODY_TEMPERATURE = """{"samsungUid":"uid-golden-body-temperature","observedAt":{"instant":"2026-08-10T08:00:30Z","offset":"-03:00"},"mapperVersion":"samsung-health-body-temperature/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T08:00:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T08:00:00Z","offset":"-03:00"}},"sourcePayload":{"fields":{"temperature":36.6},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"bodyTemperature":{"temperature":{"value":36.6,"unit":"Cel"}}}}}"""
    }
}
