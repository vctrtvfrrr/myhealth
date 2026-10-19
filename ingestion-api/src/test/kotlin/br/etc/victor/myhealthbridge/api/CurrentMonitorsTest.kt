package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.api.MonitorEnvelopes.energyScore
import br.etc.victor.myhealthbridge.api.MonitorEnvelopes.irregularHeartRhythm
import br.etc.victor.myhealthbridge.api.MonitorEnvelopes.sleepApnea
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The Current Health Record of the irregular heart rhythm notifications, the sleep apnea assessments and
 * the energy scores, on the terms heart rate set: the latest observation wins by when it was observed,
 * not by when it arrived, and every earlier one is kept.
 */
@Tag("integration")
class CurrentMonitorsTest : IngestionApiTest() {

    /** One of the three categories, whose record states [value] in the column [valueColumn] of its view. */
    private class Kind(
        val recordType: String,
        val mapperVersion: String,
        val view: String,
        val valueColumn: String,
        val first: String,
        val second: String,
        val envelope: (uid: String, observedAt: String, value: String) -> JsonObject,
    )

    private val kinds = listOf(
        Kind(
            recordType = MonitorEnvelopes.IRREGULAR_HEART_RHYTHM_RECORD_TYPE,
            mapperVersion = MonitorEnvelopes.IRREGULAR_HEART_RHYTHM_MAPPER_VERSION,
            view = "current_irregular_heart_rhythm",
            valueColumn = "status",
            first = "DETECTED",
            second = "SOMETHING_NEWER",
        ) { uid, observedAt, value -> irregularHeartRhythm(samsungUid = uid, observedAt = observedAt, status = value) },
        Kind(
            recordType = MonitorEnvelopes.SLEEP_APNEA_RECORD_TYPE,
            mapperVersion = MonitorEnvelopes.SLEEP_APNEA_MAPPER_VERSION,
            view = "current_sleep_apnea",
            valueColumn = "result",
            first = "NOT_DETECTED",
            second = "DETECTED",
        ) { uid, observedAt, value -> sleepApnea(samsungUid = uid, observedAt = observedAt, result = value) },
        Kind(
            recordType = MonitorEnvelopes.ENERGY_SCORE_RECORD_TYPE,
            mapperVersion = MonitorEnvelopes.ENERGY_SCORE_MAPPER_VERSION,
            view = "current_energy_score",
            valueColumn = "score",
            first = "82",
            second = "77.5",
        ) { uid, observedAt, value -> energyScore(samsungUid = uid, observedAt = observedAt, score = value) },
    )

    private val earlier = "2026-08-10T10:00:00Z"
    private val later = "2026-08-11T10:00:00Z"

    @Test
    fun `answers a record it already holds as already present`() = kinds.forEach { kind ->
        val uid = "uid-repeated-${kind.recordType}"
        val record = kind.envelope(uid, earlier, kind.first)
        send(kind, record)

        val response = send(kind, record)

        assertEquals(listOf("already_present"), statuses(response.body), kind.recordType)
        assertEquals(1, versionsOf(uid).size, kind.recordType)
        assertEquals(kind.first, valueOf(kind, uid), kind.recordType)
    }

    @Test
    fun `moves the current record to a newer observation without erasing the earlier version`() = kinds.forEach { kind ->
        val uid = "uid-edited-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, kind.first))

        send(kind, kind.envelope(uid, later, kind.second))

        assertEquals(kind.second, valueOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer record current when an older one is ingested afterwards`() = kinds.forEach { kind ->
        val uid = "uid-late-${kind.recordType}"
        send(kind, kind.envelope(uid, later, kind.second))

        send(kind, kind.envelope(uid, earlier, kind.first))

        assertEquals(kind.second, valueOf(kind, uid), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `takes a removed record out of the read model, keeping what was observed`() = kinds.forEach { kind ->
        val uid = "uid-removed-${kind.recordType}"
        send(kind, kind.envelope(uid, earlier, kind.first))

        send(kind, Envelopes.removal(samsungUid = uid, mapperVersion = kind.mapperVersion))

        assertEquals(0, countOf("select count(*) from read_model.${kind.view} where samsung_uid = '$uid'"), kind.recordType)
        assertEquals(2, versionsOf(uid).size, kind.recordType)
    }

    @Test
    fun `holds a notification and an assessment the source stated no constant for, with no value`() {
        send(kinds[0], irregularHeartRhythm(samsungUid = "uid-unstated-ihr", status = null))
        send(kinds[1], sleepApnea(samsungUid = "uid-unstated-apnea", result = null))

        assertEquals(listOf(null), column("select status from read_model.current_irregular_heart_rhythm where samsung_uid = ?", "uid-unstated-ihr"))
        assertEquals(listOf(null), column("select result from read_model.current_sleep_apnea where samsung_uid = ?", "uid-unstated-apnea"))
    }

    /** The day a score belongs to is the local date its period starts at, under the offset it was produced in. */
    @Test
    fun `dates each energy score by the local day it scores`() {
        send(kinds[2], energyScore(samsungUid = "uid-day-west", day = "2026-08-10T03:00:00Z", offset = "-03:00"))
        send(kinds[2], energyScore(samsungUid = "uid-day-east", day = "2026-08-09T15:00:00Z", offset = "+09:00"))

        assertEquals(listOf("2026-08-10"), column("select local_date::text from read_model.current_energy_score where samsung_uid = ?", "uid-day-west"))
        assertEquals(listOf("2026-08-10"), column("select local_date::text from read_model.current_energy_score where samsung_uid = ?", "uid-day-east"))
    }

    private fun send(kind: Kind, item: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = kind.recordType, items = listOf(item)), token)

    private fun valueOf(kind: Kind, samsungUid: String): String? =
        column("select ${kind.valueColumn}::text from read_model.${kind.view} where samsung_uid = ?", samsungUid).single()

    private fun column(sql: String, samsungUid: String): List<String?> = api.query { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }
}
