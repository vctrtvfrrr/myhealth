package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** One bad item must never hold back the batch it travelled in. */
@Tag("integration")
class IngestionRejectionTest : IngestionApiTest() {

    @Test
    fun `keeps the valid items of a mixed batch`() {
        val response = send(
            Envelopes.heartRate(samsungUid = "uid-mixed-good"),
            Envelopes.heartRate(samsungUid = "uid-mixed-bad", unit = "bpm"),
            Envelopes.removal(samsungUid = "uid-mixed-removed"),
        )

        assertEquals(200, response.status)
        assertEquals(listOf("accepted", "rejected", "accepted"), statuses(response.body))
        assertEquals(1, versionsOf("uid-mixed-good").size)
        assertEquals(1, versionsOf("uid-mixed-removed").size)
        assertTrue(versionsOf("uid-mixed-bad").isEmpty())
    }

    @Test
    fun `still answers 200 when every item is rejected`() {
        val response = send(
            Envelopes.heartRate(samsungUid = "uid-all-bad-1", unit = "bpm"),
            JsonObject(Envelopes.heartRate(samsungUid = "uid-all-bad-2") - "samsungUid"),
        )

        assertEquals(200, response.status)
        assertEquals(listOf("rejected", "rejected"), statuses(response.body))
        assertEquals(listOf(listOf("invalid_unit"), listOf("invalid_identity")), codes(response.body))
    }

    @Test
    fun `reports every reason of a rejected item, ordered and never empty`() {
        val broken = Envelopes.heartRate(
            samsungUid = "",
            observedAt = "whenever",
            observedOffset = "nope",
            unit = "bpm",
        )

        val response = send(broken)

        assertEquals(
            listOf(listOf("invalid_identity", "invalid_time_range", "invalid_offset", "invalid_unit")),
            codes(response.body),
        )
    }

    @Test
    fun `rejects an item whose fields are malformed without failing the batch`() {
        val response = send(
            buildJsonObject { put("samsungUid", 7) },
            Envelopes.heartRate(samsungUid = "uid-survivor"),
        )

        assertEquals(200, response.status)
        assertEquals(listOf("rejected", "accepted"), statuses(response.body))
        assertTrue(codes(response.body).first().isNotEmpty())
    }

    @Test
    fun `rejects every item of a batch whose record type nobody maps yet`() {
        val response = api.postBatch(
            Envelopes.batch(recordType = "blood_pressure", items = listOf(Envelopes.heartRate())),
            token,
        )

        assertEquals(200, response.status)
        assertEquals(listOf(listOf("unsupported_record_type")), codes(response.body))
    }

    @Test
    fun `rejects an item whose mapper this server does not know`() {
        val response = send(Envelopes.heartRate(mapperVersion = "samsung-health-heart-rate/99"))

        assertEquals(listOf(listOf("unsupported_mapper")), codes(response.body))
    }

    @Test
    fun `tolerates a property a newer client added to an item`() {
        val item = Envelopes.heartRate(samsungUid = "uid-additive") + ("futureField" to JsonPrimitive("whatever"))

        val response = send(JsonObject(item))

        assertEquals(listOf("accepted"), statuses(response.body))
    }

    @Test
    fun `answers a successful ingestion with a server generated id and nothing that was sent`() {
        val response = send(Envelopes.heartRate(samsungUid = "uid-not-echoed", sourceApp = "com.example.secret"))

        assertTrue(
            response.body.matches(
                Regex("""\{"ingestionId":"[0-9a-f-]{36}","results":\[\{"index":0,"status":"accepted"}]}"""),
            ),
            "the response carried more than the ingestion id and the positional results: ${response.body}",
        )
    }
}
