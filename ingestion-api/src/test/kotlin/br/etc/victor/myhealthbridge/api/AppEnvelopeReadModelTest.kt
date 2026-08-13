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

    private fun readModelUids(samsungUid: String): List<String> = api.query { connection ->
        connection.prepareStatement("select samsung_uid from read_model.current_heart_rate where samsung_uid = ?")
            .use { statement ->
                statement.setString(1, samsungUid)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
    }

    private fun readModelRow(samsungUid: String): Map<String, String?> = api.query { connection ->
        connection.prepareStatement(
            """
            select beats_per_minute::text, unit, period_start_offset, source_app, source_device, mapper_version
            from read_model.current_heart_rate
            where samsung_uid = ?
            """.trimIndent(),
        ).use { statement ->
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
    }
}
