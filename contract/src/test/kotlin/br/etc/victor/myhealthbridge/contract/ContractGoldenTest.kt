package br.etc.victor.myhealthbridge.contract

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The guard over version 1 of the wire contract.
 *
 * The incompatible change that actually threatens this system is not the one a client sends, it is the
 * one we make here. These goldens exist to fail in the face of whoever makes it, so that changing the
 * shape becomes a deliberate decision about the Supported Contract Range rather than an edit nobody
 * noticed. See ADR 0007.
 */
class ContractGoldenTest {

    @Test
    fun `pins the version 1 request document`() {
        val batch = IngestionBatch(
            recordType = "heart_rate",
            items = listOf(present(), removalWithPeriod(), removalWithoutPeriod()),
        )

        assertEquals(
            """{"recordType":"heart_rate","items":[{"samsungUid":"uid-1","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"known","id":"com.example.shealth"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"present","period":{"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"}},"sourcePayload":{"heart_rate":"72"},"normalizedPayload":{"heartRate":{"value":72,"unit":"/min"}}}},{"samsungUid":"uid-2","observedAt":{"instant":"2026-08-11T09:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"known","id":"device-1"}},"state":{"kind":"removed","period":{"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"end":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"}}}},{"samsungUid":"uid-3","observedAt":{"instant":"2026-08-11T09:00:00Z","offset":"-03:00"},"mapperVersion":"samsung-health-heart-rate/1","sourceProvenance":{"sourceApp":{"kind":"unknown"},"sourceDevice":{"kind":"unknown"}},"state":{"kind":"removed"}}],"contractVersion":1}""",
            IngestionContract.json.encodeToString(IngestionBatch.serializer(), batch),
            BUMP,
        )
    }

    @Test
    fun `pins the version 1 response document`() {
        val response = IngestionResponse(
            ingestionId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            results = listOf(
                ItemResult(0, ItemStatus.ACCEPTED),
                ItemResult(1, ItemStatus.ALREADY_PRESENT),
                ItemResult(2, ItemStatus.REJECTED, listOf(RejectionCode.INVALID_IDENTITY)),
            ),
        )

        assertEquals(
            """{"ingestionId":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","results":[{"index":0,"status":"accepted"},{"index":1,"status":"already_present"},{"index":2,"status":"rejected","codes":["invalid_identity"]}]}""",
            IngestionContract.json.encodeToString(IngestionResponse.serializer(), response),
            BUMP,
        )
    }

    /**
     * Titles are deliberately absent: they are human text and may be reworded freely. A code's wire
     * name and its status are what a client branches on.
     */
    @Test
    fun `pins every published batch error code and its status`() {
        assertEquals(
            listOf(
                "invalid_device_token" to 401,
                "unsupported_media_type" to 415,
                "batch_too_large" to 413,
                "invalid_request" to 400,
                "too_many_items" to 422,
                "invalid_batch" to 422,
                "contract_version_too_old" to 422,
                "contract_version_too_new" to 422,
                "ingestion_temporarily_unavailable" to 503,
            ),
            BatchErrorCode.entries.map { it.wireValue to it.status },
            "Renaming a published code, or moving it to another status, breaks every client that " +
                "branches on it. $BUMP",
        )
    }

    @Test
    fun `pins every published rejection code`() {
        assertEquals(
            listOf(
                "invalid_identity",
                "invalid_provenance",
                "invalid_time_range",
                "invalid_offset",
                "unsupported_record_type",
                "unsupported_mapper",
                "invalid_payload",
                "invalid_unit",
            ),
            RejectionCode.entries.map { it.wireValue },
            "Rejection codes are reported in declaration order, so reordering them is also a change. $BUMP",
        )
    }

    private fun present() = HealthRecordEnvelope(
        samsungUid = "uid-1",
        observedAt = ZonedInstant("2026-08-10T22:00:00Z", "-03:00"),
        mapperVersion = MAPPER_VERSION,
        sourceProvenance = SourceProvenance(
            sourceApp = SourceIdentity.Known("com.example.shealth"),
            sourceDevice = SourceIdentity.Unknown,
        ),
        state = RecordState.Present(
            period = period(),
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

    private fun removalWithPeriod() = HealthRecordEnvelope(
        samsungUid = "uid-2",
        observedAt = ZonedInstant("2026-08-11T09:00:00Z", "-03:00"),
        mapperVersion = MAPPER_VERSION,
        sourceProvenance = SourceProvenance(
            sourceApp = SourceIdentity.Unknown,
            sourceDevice = SourceIdentity.Known("device-1"),
        ),
        state = RecordState.Removed(period = period()),
    )

    private fun removalWithoutPeriod() = HealthRecordEnvelope(
        samsungUid = "uid-3",
        observedAt = ZonedInstant("2026-08-11T09:00:00Z", "-03:00"),
        mapperVersion = MAPPER_VERSION,
        sourceProvenance = SourceProvenance(SourceIdentity.Unknown, SourceIdentity.Unknown),
        state = RecordState.Removed(),
    )

    private fun period() = SourcePeriod(
        start = ZonedInstant("2026-08-10T21:59:00Z", "-03:00"),
        end = ZonedInstant("2026-08-10T21:59:00Z", "-03:00"),
    )

    private companion object {
        const val MAPPER_VERSION = "samsung-health-heart-rate/1"

        const val BUMP = "The version 1 wire shape changed. If the change is additive, update this " +
            "golden. If it is not, bump IngestionContract.CURRENT_VERSION and raise MINIMUM_VERSION " +
            "only once no released client still speaks the old one."
    }
}
