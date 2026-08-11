package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigInteger

class IngestionContractTest {

    @Test
    fun `pins the wire version so that a bump is a deliberate change`() {
        assertEquals(1, IngestionContract.CURRENT_VERSION)
    }

    @Test
    fun `answers no incompatibility inside the supported contract range`() {
        (IngestionContract.MINIMUM_VERSION..IngestionContract.CURRENT_VERSION).forEach { version ->
            assertNull(
                IngestionContract.incompatibilityOf(version.toBigInteger()),
                "version $version must be accepted",
            )
        }
    }

    @Test
    fun `tells the two sides of the range apart, because the remediation is opposite`() {
        assertEquals(
            BatchErrorCode.CONTRACT_VERSION_TOO_OLD,
            IngestionContract.incompatibilityOf((IngestionContract.MINIMUM_VERSION - 1).toBigInteger()),
        )
        assertEquals(
            BatchErrorCode.CONTRACT_VERSION_TOO_NEW,
            IngestionContract.incompatibilityOf((IngestionContract.CURRENT_VERSION + 1).toBigInteger()),
        )
    }

    /** JSON integers have no width limit, and one too wide for an Int is still above the range. */
    @Test
    fun `reads a version beyond an Int as above the range, not as malformed`() {
        assertEquals(
            BatchErrorCode.CONTRACT_VERSION_TOO_NEW,
            IngestionContract.incompatibilityOf(BigInteger("99999999999")),
        )
        assertEquals(
            BatchErrorCode.CONTRACT_VERSION_TOO_OLD,
            IngestionContract.incompatibilityOf(BigInteger("-99999999999")),
        )
    }

    @Test
    fun `publishes both bounds of the supported contract range`() {
        assertEquals(
            """{"minimumVersion":1,"maximumVersion":1,"recommendedVersion":1}""",
            IngestionContract.json.encodeToString(
                SupportedContractRange.serializer(),
                SupportedContractRange.PUBLISHED,
            ),
        )
    }

    /**
     * The wire value is used outside JSON too, by the log and by the database, so it must not be able
     * to drift away from what the serializer writes.
     */
    @Test
    fun `keeps every wire value equal to what the serializer writes`() {
        RejectionCode.entries.forEach { code ->
            assertEquals(
                """"${code.wireValue}"""",
                IngestionContract.json.encodeToString(RejectionCode.serializer(), code),
            )
        }
        ItemStatus.entries.forEach { status ->
            assertEquals(
                """"${status.wireValue}"""",
                IngestionContract.json.encodeToString(ItemStatus.serializer(), status),
            )
        }
    }

    @Test
    fun `writes a batch that names its record type only at the root`() {
        val batch = IngestionBatch(recordType = "heart_rate", items = listOf(present()))

        val encoded = IngestionContract.json.encodeToString(IngestionBatch.serializer(), batch)

        assertEquals(
            """
            {"recordType":"heart_rate","items":[{"samsungUid":"uid-1","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"}},"sourcePayload":{"heart_rate":"72"},"normalizedPayload":{"heartRate":{"value":72,"unit":"/min"}}}}],"contractVersion":1}
            """.trimIndent(),
            encoded,
        )
    }

    @Test
    fun `ignores properties a newer client added`() {
        val decoded = IngestionContract.json.decodeFromString(
            HealthRecordEnvelope.serializer(),
            """
            {"samsungUid":"uid-1","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00","era":"whatever"},
             "mapperVersion":"samsung-health-heart-rate/1","futureField":[1,2],
             "sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},
             "state":{"kind":"removed"}}
            """.trimIndent(),
        )

        assertEquals("uid-1", decoded.samsungUid)
        assertEquals(RecordState.Removed(period = null), decoded.state)
    }

    @Test
    fun `omits the period of a removal that only knows its identity`() {
        val removal = HealthRecordEnvelope(
            samsungUid = "uid-1",
            observedAt = ZonedInstant("2026-08-11T09:00:00Z", "-03:00"),
            mapperVersion = "samsung-health-heart-rate/1",
            sourceProvenance = SourceProvenance(SourceIdentity.Unknown, SourceIdentity.Unknown),
            state = RecordState.Removed(),
        )

        val encoded = IngestionContract.json.encodeToString(HealthRecordEnvelope.serializer(), removal)

        assertEquals(
            """{"samsungUid":"uid-1","observedAt":{"instant":"2026-08-11T09:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}""",
            encoded,
        )
    }

    @Test
    fun `writes a rejected result with its ordered codes and nothing else`() {
        val response = IngestionResponse(
            ingestionId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            results = listOf(
                ItemResult(0, ItemStatus.ACCEPTED),
                ItemResult(1, ItemStatus.ALREADY_PRESENT),
                ItemResult(2, ItemStatus.REJECTED, listOf(RejectionCode.INVALID_IDENTITY, RejectionCode.INVALID_UNIT)),
            ),
        )

        val encoded = IngestionContract.json.encodeToString(IngestionResponse.serializer(), response)

        assertEquals(
            """{"ingestionId":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","results":[{"index":0,"status":"accepted"},{"index":1,"status":"already_present"},{"index":2,"status":"rejected","codes":["invalid_identity","invalid_unit"]}]}""",
            encoded,
        )
    }

    private fun present() = HealthRecordEnvelope(
        samsungUid = "uid-1",
        observedAt = ZonedInstant("2026-08-10T22:00:00Z", "-03:00"),
        mapperVersion = "samsung-health-heart-rate/1",
        sourceProvenance = SourceProvenance(
            sourceApp = SourceIdentity.Known("com.example.shealth"),
            sourceDevice = SourceIdentity.Unknown,
        ),
        state = RecordState.Present(
            period = SourcePeriod(
                start = ZonedInstant("2026-08-10T21:59:00Z", "-03:00"),
                end = ZonedInstant("2026-08-10T21:59:00Z", "-03:00"),
            ),
            sourcePayload = buildJsonObject { put("heart_rate", "72") },
            normalizedPayload = buildJsonObject {
                put(
                    "heartRate",
                    buildJsonObject {
                        put("value", 72)
                        put("unit", "/min")
                    },
                )
            },
        ),
    )
}
