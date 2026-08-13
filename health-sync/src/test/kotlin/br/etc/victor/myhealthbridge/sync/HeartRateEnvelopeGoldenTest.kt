package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.HealthRecordEnvelope
import br.etc.victor.myhealthbridge.contract.IngestionContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The exact document this application sends for a heart rate record.
 *
 * The same literals are posted at `AppEnvelopeReadModelTest` in `ingestion-api`, against a real API and
 * a real PostgreSQL, so that the two halves of the vertical are pinned to one another: what the mapper
 * emits is what the read model was proven to show. Changing one here without changing it there breaks
 * that proof, which is the point.
 */
class HeartRateEnvelopeGoldenTest {

    @Test
    fun `pins the document a heart rate record is sent as`() {
        val envelope = HeartRateMapper.map(
            SourceRecord(
                uid = "uid-golden",
                startTime = Instant.parse("2026-08-10T21:59:00Z"),
                endTime = Instant.parse("2026-08-10T21:59:30Z"),
                zoneOffset = ZoneOffset.ofHours(-3),
                updateTime = Instant.parse("2026-08-10T22:00:00Z"),
                sourceAppId = "com.example.shealth",
                sourceDeviceId = null,
                clientDataId = "client-1",
                clientVersion = 3,
                fields = mapOf(
                    "heart_rate" to number("72.5"),
                    "min" to number("58.0"),
                    "max" to number("131.0"),
                    "binning_data" to SourceValue.Series(
                        listOf(mapOf("heart_rate" to number("70.0"), "start_time" to SourceValue.Text("2026-08-10T21:59:00Z"))),
                    ),
                ),
            ),
        )

        assertEquals(
            GOLDEN,
            IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope),
        )
    }

    @Test
    fun `pins the document a Source Removal is sent as`() {
        val envelope = HeartRateMapper.removalOf(
            uid = "uid-golden-removed",
            changedAt = Instant.parse("2026-08-12T09:00:00Z"),
        )

        assertEquals(
            GOLDEN_REMOVAL,
            IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), envelope),
        )
    }

    private companion object {
        const val GOLDEN ="""{"samsungUid":"uid-golden","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T21:59:30Z","offset":"-03:00"}},"sourcePayload":{"fields":{"heart_rate":72.5,"min":58.0,"max":131.0,"binning_data":[{"heart_rate":70.0,"start_time":"2026-08-10T21:59:00Z"}]},"client":{"dataId":"client-1","version":3}},"normalizedPayload":{"heartRate":{"value":72.5,"unit":"/min"}}}}"""

        const val GOLDEN_REMOVAL =
            """{"samsungUid":"uid-golden-removed","observedAt":{"instant":"2026-08-12T09:00:00Z","offset":"+00:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}"""
    }
}
