package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The far end of the steps vertical: the exact version 3 batch the Android application builds for two
 * hours of steps, one of them empty, sent to a real API and showing up in the read model.
 *
 * The literal is pinned on the other side too, by `StepsMapperTest` in `health-sync`, so neither half can
 * drift without one of the two failing.
 */
@Tag("integration")
class AppSnapshotReadModelTest : IngestionApiTest() {

    @Test
    fun `a batch of step snapshots the application mapped becomes queryable through the read model`() {
        val response = api.postBatch(MAPPED_STEPS, token)

        assertEquals(200, response.status, response.body)
        assertEquals(listOf("accepted", "accepted"), statuses(response.body))
        assertEquals(
            listOf("2026-08-10 14:00:00|1234|f"),
            rows("select window_start, steps, partial from read_model.current_hourly_steps"),
        )
        assertEquals(
            listOf("2026-08-10 14:00:00|f", "2026-08-10 15:00:00|t"),
            rows("select window_start, empty from read_model.current_observed_snapshot order by window_start"),
        )
    }

    private fun rows(sql: String): List<String> = api.query { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).joinToString("|") { rows.getString(it) })
                }
            }
        }
    }

    private companion object {
        const val MAPPED_STEPS =
            """{"snapshotType":"steps","items":[{"window":{"start":"2026-08-10T14:00:00","end":"2026-08-10T15:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-steps/1","content":{"steps":1234}},{"window":{"start":"2026-08-10T15:00:00","end":"2026-08-10T16:00:00"},"observedAt":{"instant":"2026-08-10T18:20:00Z","offset":"-03:00"},"sourceScope":"platform","mapperVersion":"samsung-health-steps/1"}],"contractVersion":3}"""
    }
}
