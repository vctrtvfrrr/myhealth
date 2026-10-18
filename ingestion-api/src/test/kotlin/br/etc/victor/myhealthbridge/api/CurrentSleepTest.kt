package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The Current Health Record of a night, stages included, on the terms heart rate set: the latest
 * observation wins by when it was observed, not by when it arrived, and every earlier one is kept.
 */
@Tag("integration")
class CurrentSleepTest : IngestionApiTest() {

    private fun sendNights(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = Envelopes.SLEEP_RECORD_TYPE, items = items.toList()), token)

    private fun staged(vararg stages: String): List<JsonObject> =
        listOf(Envelopes.sleepSession(stages = stages.map { Envelopes.sleepStage(stage = it) }))

    @Test
    fun `answers a night it already holds as already present`() {
        val night = Envelopes.sleep(samsungUid = "uid-repeated-night", sessions = staged("LIGHT", "DEEP"))
        sendNights(night)

        val response = sendNights(night)

        assertEquals(listOf("already_present"), statuses(response.body))
        assertEquals(1, versionsOf("uid-repeated-night").size)
        assertEquals(2, countOf(rowsOf("current_sleep_stage", "uid-repeated-night")))
    }

    @Test
    fun `holds a night with no stages as a session without stage rows`() {
        sendNights(Envelopes.sleep(samsungUid = "uid-unstaged-night"))

        assertEquals(1, countOf(rowsOf("current_sleep", "uid-unstaged-night")))
        assertEquals(1, countOf(rowsOf("current_sleep_session", "uid-unstaged-night")))
        assertEquals(0, countOf(rowsOf("current_sleep_stage", "uid-unstaged-night")))
    }

    @Test
    fun `moves the current night and its stages to a newer observation without erasing the earlier version`() {
        val uid = "uid-edited-night"
        sendNights(Envelopes.sleep(samsungUid = uid, score = "70", sessions = staged("LIGHT")))

        sendNights(
            Envelopes.sleep(
                samsungUid = uid,
                observedAt = "2026-08-11T10:00:00Z",
                score = "85",
                sessions = staged("LIGHT", "REM"),
            ),
        )

        assertEquals("85", scoreOf(uid))
        assertEquals(listOf("LIGHT", "REM"), stagesOf(uid))
        assertEquals(2, versionsOf(uid).size)
    }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer night current when an older one is ingested afterwards`() {
        val uid = "uid-late-night"
        sendNights(
            Envelopes.sleep(samsungUid = uid, observedAt = "2026-08-11T10:00:00Z", score = "85", sessions = staged("REM")),
        )

        sendNights(Envelopes.sleep(samsungUid = uid, score = "70", sessions = staged("LIGHT", "DEEP")))

        assertEquals("85", scoreOf(uid))
        assertEquals(listOf("REM"), stagesOf(uid))
        assertEquals(2, versionsOf(uid).size)
    }

    @Test
    fun `takes a removed night and its stages out of the read model, keeping what was observed`() {
        val uid = "uid-removed-night"
        sendNights(Envelopes.sleep(samsungUid = uid, sessions = staged("LIGHT")))

        sendNights(Envelopes.removal(samsungUid = uid, mapperVersion = Envelopes.SLEEP_MAPPER_VERSION))

        assertEquals(0, countOf(rowsOf("current_sleep", uid)))
        assertEquals(0, countOf(rowsOf("current_sleep_session", uid)))
        assertEquals(0, countOf(rowsOf("current_sleep_stage", uid)))
        assertEquals(2, versionsOf(uid).size)
    }

    @Test
    fun `rebuilds the sleep read model from the preserved envelopes, identically`() {
        val uid = "uid-rebuilt-night"
        sendNights(Envelopes.sleep(samsungUid = uid, score = "70", sessions = staged("LIGHT")))
        sendNights(
            Envelopes.sleep(samsungUid = uid, observedAt = "2026-08-11T10:00:00Z", score = "85", sessions = staged("DEEP")),
        )
        val before = scoreOf(uid) to stagesOf(uid)

        api.administer("projection", "rebuild")

        assertEquals(before, scoreOf(uid) to stagesOf(uid))
        assertEquals("85", before.first)
    }

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun scoreOf(samsungUid: String): String? = column(
        "select sleep_score::text from read_model.current_sleep where samsung_uid = ?",
        samsungUid,
    ).single()

    private fun stagesOf(samsungUid: String): List<String?> = column(
        "select stage from read_model.current_sleep_stage where samsung_uid = ? order by session_position, position",
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
