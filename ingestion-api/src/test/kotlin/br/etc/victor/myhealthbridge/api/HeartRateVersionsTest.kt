package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Both versions of the heart rate mapper side by side: version 1 sent the rate alone, version 2 sends
 * it with its range and series. The API accepts both, because an application not yet updated still
 * sends version 1, and a Full Reconciliation turns every record version 1 delivered into one of
 * version 2 observed at the same instant.
 */
@Tag("integration")
class HeartRateVersionsTest : IngestionApiTest() {

    @Test
    fun `accepts a batch carrying records of both versions`() {
        val response = send(
            Envelopes.heartRate(samsungUid = "uid-version-1"),
            BinnedEnvelopes.heartRate(samsungUid = "uid-version-2"),
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted", "accepted"), statuses(response.body))
    }

    @Test
    fun `shows a rate from version 1 without a range or a series`() {
        send(Envelopes.heartRate(samsungUid = "uid-version-1", beatsPerMinute = "64"))

        assertEquals(
            listOf("64", "/min", null, null, Envelopes.MAPPER_VERSION),
            current("uid-version-1"),
        )
        assertEquals(emptyList<List<String?>>(), series("uid-version-1"))
    }

    @Test
    fun `shows a rate from version 2 with its range and one row per bin`() {
        send(
            BinnedEnvelopes.heartRate(
                samsungUid = "uid-version-2",
                rate = "72.5",
                min = "58.0",
                max = "131.0",
                series = listOf(
                    BinnedEnvelopes.heartRateBin(end = "2026-08-10T03:05:00Z", rate = "70.0", min = "61.0", max = "79.0"),
                    BinnedEnvelopes.heartRateBin(start = "2026-08-10T03:05:00Z", rate = "75.0", min = null, max = null),
                ),
            ),
        )

        assertEquals(
            listOf("72.5", "/min", "58", "131", BinnedEnvelopes.HEART_RATE_MAPPER_VERSION),
            current("uid-version-2"),
        )
        assertEquals(
            listOf(
                listOf("1", "2026-08-10 03:00:00+00", "2026-08-10 03:05:00+00", "70", "61", "79"),
                listOf("2", "2026-08-10 03:05:00+00", "2026-08-10 03:10:00+00", "75", null, null),
            ),
            series("uid-version-2"),
        )
    }

    @Test
    fun `makes the version a Full Reconciliation reads again current, observed at the same instant`() {
        send(Envelopes.heartRate(samsungUid = "uid-reread", observedAt = OBSERVED_AT, beatsPerMinute = "72.5"))

        val reread = send(BinnedEnvelopes.heartRate(samsungUid = "uid-reread", observedAt = OBSERVED_AT))

        assertEquals(listOf("accepted"), statuses(reread.body))
        assertEquals(2, versionsOf("uid-reread").size)
        assertEquals(
            listOf("72.5", "/min", "58", "131", BinnedEnvelopes.HEART_RATE_MAPPER_VERSION),
            current("uid-reread"),
        )
        assertEquals(1, series("uid-reread").size)
    }

    /** The outbox of an application updated mid-flight may still hold the version 1 it already delivered. */
    @Test
    fun `keeps version 2 current when the version 1 it replaced arrives again`() {
        val first = Envelopes.heartRate(samsungUid = "uid-resent", observedAt = OBSERVED_AT, beatsPerMinute = "72.5")
        send(first)
        send(BinnedEnvelopes.heartRate(samsungUid = "uid-resent", observedAt = OBSERVED_AT))

        val resent = send(first)

        assertEquals(listOf("already_present"), statuses(resent.body))
        assertEquals(BinnedEnvelopes.HEART_RATE_MAPPER_VERSION, current("uid-resent").last())
    }

    private fun current(samsungUid: String): List<String?> = rows(
        """
        select beats_per_minute::text, unit, min_beats_per_minute::text, max_beats_per_minute::text, mapper_version
        from read_model.current_heart_rate where samsung_uid = ?
        """,
        samsungUid,
    ).single()

    private fun series(samsungUid: String): List<List<String?>> = rows(
        """
        select position::text, start_at::text, end_at::text, beats_per_minute::text, min_beats_per_minute::text,
            max_beats_per_minute::text
        from read_model.current_heart_rate_series where samsung_uid = ? order by position
        """,
        samsungUid,
    )

    private fun rows(sql: String, samsungUid: String): List<List<String?>> = api.query { connection ->
        connection.createStatement().use { it.execute("set time zone 'UTC'") }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, samsungUid)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) })
                }
            }
        }
    }

    private companion object {
        const val OBSERVED_AT = "2026-08-10T22:00:00Z"
    }
}
