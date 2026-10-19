package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.bloodGlucose
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.bloodOxygen
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.glucoseReading
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.oxygenBin
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.skinTemperature
import br.etc.victor.myhealthbridge.api.BinnedEnvelopes.skinTemperatureBin
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The Current Health Record of the measurements that carry a binned series, series included, on the
 * terms heart rate set: the latest observation wins by when it was observed, not by when it arrived,
 * and every earlier one is kept.
 */
@Tag("integration")
class CurrentBinnedSeriesTest : IngestionApiTest() {

    /** One of the three categories, with a measurement of [value] binned into one bin per value of [series]. */
    private class Kind(
        val recordType: String,
        val mapperVersion: String,
        val view: String,
        val valueColumn: String,
        val seriesView: String,
        val seriesColumn: String,
        val envelope: (uid: String, observedAt: String, value: String, series: List<String>) -> JsonObject,
    )

    private val kinds = listOf(
        Kind(
            recordType = BinnedEnvelopes.BLOOD_OXYGEN_RECORD_TYPE,
            mapperVersion = BinnedEnvelopes.BLOOD_OXYGEN_MAPPER_VERSION,
            view = "current_blood_oxygen",
            valueColumn = "oxygen_saturation_percent",
            seriesView = "current_blood_oxygen_series",
            seriesColumn = "oxygen_saturation_percent",
        ) { uid, observedAt, value, series ->
            bloodOxygen(samsungUid = uid, observedAt = observedAt, saturation = value, series = series.map { oxygenBin(saturation = it) })
        },
        Kind(
            recordType = BinnedEnvelopes.SKIN_TEMPERATURE_RECORD_TYPE,
            mapperVersion = BinnedEnvelopes.SKIN_TEMPERATURE_MAPPER_VERSION,
            view = "current_skin_temperature",
            valueColumn = "temperature_celsius",
            seriesView = "current_skin_temperature_series",
            seriesColumn = "temperature_celsius",
        ) { uid, observedAt, value, series ->
            skinTemperature(
                samsungUid = uid,
                observedAt = observedAt,
                temperature = value,
                series = series.map { skinTemperatureBin(temperature = it) },
            )
        },
        Kind(
            recordType = BinnedEnvelopes.BLOOD_GLUCOSE_RECORD_TYPE,
            mapperVersion = BinnedEnvelopes.BLOOD_GLUCOSE_MAPPER_VERSION,
            view = "current_blood_glucose",
            valueColumn = "glucose_mmol_per_liter",
            seriesView = "current_blood_glucose_series",
            seriesColumn = "glucose_mmol_per_liter",
        ) { uid, observedAt, value, series ->
            bloodGlucose(samsungUid = uid, observedAt = observedAt, glucose = value, series = series.map { glucoseReading(glucose = it) })
        },
    )

    private val earlier = "2026-08-10T03:15:00Z"
    private val later = "2026-08-11T10:00:00Z"

    @Test
    fun `answers a measurement it already holds as already present`() = kinds.forEach { kind ->
        val uid = "uid-repeated-${kind.recordType}"
        val measurement = kind.envelope(uid, earlier, "30.5", listOf("30.5", "31.5"))
        send(kind, measurement)

        val response = send(kind, measurement)

        assertEquals(listOf("already_present"), statuses(response.body), kind.recordType)
        assertEquals(1, versionsOf(uid).size, kind.recordType)
        assertEquals(2, countOf(rowsOf(kind.seriesView, uid)), kind.recordType)
    }

    @Test
    fun `holds a measurement with no series as a row without series rows`() = kinds.forEach { kind ->
        val uid = "uid-unbinned-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, "30.5", emptyList()))

        assertEquals(1, countOf(rowsOf(kind.view, uid)), kind.recordType)
        assertEquals(0, countOf(rowsOf(kind.seriesView, uid)), kind.recordType)
    }

    @Test
    fun `moves the current measurement and its series to a newer observation without erasing the earlier version`() =
        kinds.forEach { kind ->
            val uid = "uid-edited-${kind.recordType}"
            send(kind, kind.envelope(uid, earlier, "30.5", listOf("30.5")))

            send(kind, kind.envelope(uid, later, "32.5", listOf("32.25", "33.75")))

            assertEquals("32.5", valueOf(kind, uid), kind.recordType)
            assertEquals(listOf("32.25", "33.75"), seriesOf(kind, uid), kind.recordType)
            assertEquals(2, versionsOf(uid).size, kind.recordType)
        }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer measurement current when an older one is ingested afterwards`() = kinds.forEach { kind ->
        val uid = "uid-late-${kind.recordType}"
        send(kind, kind.envelope(uid, later, "32.5", listOf("32.5")))

        send(kind, kind.envelope(uid, earlier, "30.5", listOf("30.5", "31.5")))

        assertEquals("32.5", valueOf(kind, uid), kind.recordType)
        assertEquals(listOf("32.5"), seriesOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `takes a removed measurement and its series out of the read model, keeping what was observed`() =
        kinds.forEach { kind ->
            val uid = "uid-removed-${kind.recordType}"
            send(kind, kind.envelope(uid, earlier, "30.5", listOf("30.5")))

            send(kind, Envelopes.removal(samsungUid = uid, mapperVersion = kind.mapperVersion))

            assertEquals(0, countOf(rowsOf(kind.view, uid)), kind.recordType)
            assertEquals(0, countOf(rowsOf(kind.seriesView, uid)), kind.recordType)
            assertEquals(2, versionsOf(uid).size, kind.recordType)
        }

    @Test
    fun `rebuilds the read model of every series from the preserved envelopes, identically`() {
        kinds.forEach { kind ->
            val uid = "uid-rebuilt-${kind.recordType}"
            send(kind, kind.envelope(uid, earlier, "30.5", listOf("30.5")))
            send(kind, kind.envelope(uid, later, "32.5", listOf("32.25", "33.75")))
        }
        val before = kinds.map { valueOf(it, "uid-rebuilt-${it.recordType}") to seriesOf(it, "uid-rebuilt-${it.recordType}") }

        api.administer("projection", "rebuild")

        assertEquals(before, kinds.map { valueOf(it, "uid-rebuilt-${it.recordType}") to seriesOf(it, "uid-rebuilt-${it.recordType}") })
        assertEquals("32.5", before.first().first)
    }

    private fun send(kind: Kind, item: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = kind.recordType, items = listOf(item)), token)

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun valueOf(kind: Kind, samsungUid: String): String? = column(
        "select ${kind.valueColumn}::text from read_model.${kind.view} where samsung_uid = ?",
        samsungUid,
    ).single()

    private fun seriesOf(kind: Kind, samsungUid: String): List<String?> = column(
        "select ${kind.seriesColumn}::text from read_model.${kind.seriesView} where samsung_uid = ? order by position",
        samsungUid,
    )

    private fun column(sql: String, samsungUid: String): List<String?> = api.query { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }
}
