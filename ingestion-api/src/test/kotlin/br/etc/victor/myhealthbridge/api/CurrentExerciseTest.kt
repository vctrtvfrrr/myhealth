package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The Current Health Record of an exercise, route included, on the terms heart rate set: the latest
 * observation wins by when it was observed, not by when it arrived, and every earlier one is kept.
 */
@Tag("integration")
class CurrentExerciseTest : IngestionApiTest() {

    private fun sendExercises(vararg items: JsonObject): IngestionApi.Response =
        api.postBatch(Envelopes.batch(recordType = Envelopes.EXERCISE_RECORD_TYPE, items = items.toList()), token)

    @Test
    fun `answers an exercise it already holds as already present`() {
        val exercise = Envelopes.exercise(samsungUid = "uid-repeated", route = listOf(Envelopes.routePoint()))
        sendExercises(exercise)

        val response = sendExercises(exercise)

        assertEquals(listOf("already_present"), statuses(response.body))
        assertEquals(1, versionsOf("uid-repeated").size)
        assertEquals(1, countOf(rowsOf("current_exercise_location", "uid-repeated")))
    }

    @Test
    fun `moves the current exercise and its route to a newer observation without erasing the earlier version`() {
        val uid = "uid-edited"
        sendExercises(Envelopes.exercise(samsungUid = uid, distance = "5000.0", route = listOf(Envelopes.routePoint())))

        sendExercises(
            Envelopes.exercise(
                samsungUid = uid,
                observedAt = "2026-08-11T10:00:00Z",
                distance = "5200.0",
                route = listOf(
                    Envelopes.routePoint(latitude = "-22.9", longitude = "-43.2"),
                    Envelopes.routePoint(at = "2026-08-10T09:10:00Z", latitude = "-22.8", longitude = "-43.1"),
                ),
            ),
        )

        assertEquals("5200", distanceOf(uid))
        assertEquals(listOf("-22.9", "-22.8"), latitudesOf(uid))
        assertEquals(2, versionsOf(uid).size)
    }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer exercise current when an older one is ingested afterwards`() {
        val uid = "uid-late-exercise"
        sendExercises(
            Envelopes.exercise(
                samsungUid = uid,
                observedAt = "2026-08-11T10:00:00Z",
                distance = "5200.0",
                route = listOf(Envelopes.routePoint(latitude = "-22.9")),
            ),
        )

        sendExercises(Envelopes.exercise(samsungUid = uid, distance = "5000.0", route = listOf(Envelopes.routePoint())))

        assertEquals("5200", distanceOf(uid))
        assertEquals(listOf("-22.9"), latitudesOf(uid))
        assertEquals(2, versionsOf(uid).size)
    }

    @Test
    fun `rebuilds the exercise read model from the preserved envelopes, identically`() {
        val uid = "uid-rebuilt-exercise"
        sendExercises(Envelopes.exercise(samsungUid = uid, route = listOf(Envelopes.routePoint())))
        sendExercises(
            Envelopes.exercise(
                samsungUid = uid,
                observedAt = "2026-08-11T10:00:00Z",
                distance = "5200.0",
                route = listOf(Envelopes.routePoint(latitude = "-22.9")),
            ),
        )
        val before = distanceOf(uid) to latitudesOf(uid)

        api.administer("projection", "rebuild")

        assertEquals(before, distanceOf(uid) to latitudesOf(uid))
        assertEquals("5200", before.first)
    }

    private fun rowsOf(view: String, samsungUid: String): String =
        "select count(*) from read_model.$view where samsung_uid = '$samsungUid'"

    private fun distanceOf(samsungUid: String): String? = column(
        "select distance_meters::text from read_model.current_exercise where samsung_uid = ?",
        samsungUid,
    ).single()

    private fun latitudesOf(samsungUid: String): List<String?> = column(
        "select latitude_degrees::text from read_model.current_exercise_location where samsung_uid = ? order by position",
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
