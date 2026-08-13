package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime

/**
 * The Current Health Record as the Data Owner queries it: the latest known state of every Health
 * Record, derived from the preserved observations and regenerable from them alone.
 */
@Tag("integration")
class CurrentHealthRecordTest : IngestionApiTest() {

    @Test
    fun `projects an ingested observation with identity, provenance, time and normalized unit`() {
        val uid = "uid-projected"

        send(Envelopes.heartRate(samsungUid = uid, sourceApp = "com.example.shealth", sourceDevice = "watch-1"))

        val current = currentHeartRate(uid)!!
        assertEquals("heart_rate", current["record_type"])
        assertEquals("72", current["beats_per_minute"])
        assertEquals("/min", current["unit"])
        assertEquals("-03:00", current["period_start_offset"])
        assertEquals("-03:00", current["observed_at_offset"])
        assertEquals("com.example.shealth", current["source_app"])
        assertEquals("watch-1", current["source_device"])
        assertEquals("samsung-health-heart-rate/1", current["mapper_version"])
        assertEquals(Instant.parse("2026-08-10T21:59:00Z"), instantOf(uid, "period_start"))
        assertEquals(Instant.parse("2026-08-10T22:00:00Z"), instantOf(uid, "observed_at"))
    }

    @Test
    fun `reports an unknown Source Provenance as null rather than as a missing observation`() {
        val uid = "uid-unknown-source"

        send(Envelopes.heartRate(samsungUid = uid, sourceDevice = null))

        assertNull(currentHeartRate(uid)!!["source_device"])
    }

    @Test
    fun `moves the current record to a newer observation without erasing the earlier version`() {
        val uid = "uid-updated"
        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-10T22:00:00Z", beatsPerMinute = "72"))

        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-11T22:00:00Z", beatsPerMinute = "81"))

        assertEquals("81", currentHeartRate(uid)!!["beats_per_minute"])
        assertEquals(2, versionsOf(uid).size)
        assertEquals(1, projectedRecordsOf(uid))
    }

    /** Recomputation, not last write wins: an observation that arrives late is still the older one. */
    @Test
    fun `keeps the newer observation current when an older one is ingested afterwards`() {
        val uid = "uid-late"
        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-11T22:00:00Z", beatsPerMinute = "81"))

        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-10T22:00:00Z", beatsPerMinute = "72"))

        assertEquals("81", currentHeartRate(uid)!!["beats_per_minute"])
        assertEquals(2, versionsOf(uid).size)
    }

    @Test
    fun `takes a removed health record out of the view while keeping its observed versions`() {
        val uid = "uid-gone"
        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-10T22:00:00Z"))

        send(Envelopes.removal(samsungUid = uid, observedAt = "2026-08-11T09:00:00Z"))

        assertNull(currentHeartRate(uid), "a removed Health Record is not a current measurement")
        assertEquals(2, versionsOf(uid).size)
        assertEquals(
            1,
            projectedRecordsOf(uid),
            "the removal is the current state, so the Health Record stays projected",
        )
    }

    @Test
    fun `rebuilds the whole projection from the preserved envelopes, identically`() {
        val uid = "uid-rebuilt"
        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-10T22:00:00Z", beatsPerMinute = "72"))
        send(Envelopes.heartRate(samsungUid = uid, observedAt = "2026-08-11T22:00:00Z", beatsPerMinute = "81"))
        val incremental = projection()

        val output = api.administer("projection", "rebuild")

        assertEquals(incremental.size, projected(output))
        assertEquals(incremental, projection())
        assertEquals("81", currentHeartRate(uid)!!["beats_per_minute"])
    }

    private fun projected(output: String): Int = output.lineSequence()
        .first { it.startsWith(ProjectionAdmin.PROJECTED_PREFIX) }
        .removePrefix(ProjectionAdmin.PROJECTED_PREFIX)
        .trim()
        .toInt()

    private fun projection(): List<Pair<Long, Long>> = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select health_record_identity_id, observed_record_version_id from current_health_record order by 1",
            ).use { rows ->
                buildList {
                    while (rows.next()) add(rows.getLong(1) to rows.getLong(2))
                }
            }
        }
    }

    private fun projectedRecordsOf(samsungUid: String): Int = countOf(
        """
        select count(*) from current_health_record c
        join health_record_identity i on i.id = c.health_record_identity_id
        where i.samsung_uid = '$samsungUid'
        """.trimIndent(),
    )

    /** Read as an instant, because a `timestamptz` renders in whatever zone the reader's session has. */
    private fun instantOf(samsungUid: String, column: String): Instant = api.query { connection ->
        connection.prepareStatement("select $column from read_model.current_heart_rate where samsung_uid = ?")
            .use { statement ->
                statement.setString(1, samsungUid)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no current heart rate for $samsungUid" }
                    rows.getObject(1, OffsetDateTime::class.java).toInstant()
                }
            }
    }

    private fun currentHeartRate(samsungUid: String): Map<String, String?>? = api.query { connection ->
        connection.prepareStatement("select * from read_model.current_heart_rate where samsung_uid = ?")
            .use { statement ->
                statement.setString(1, samsungUid)
                statement.executeQuery().use { rows -> if (rows.next()) rows.columns() else null }
            }
    }

    private fun ResultSet.columns(): Map<String, String?> =
        (1..metaData.columnCount).associate { metaData.getColumnLabel(it) to getString(it) }
}
