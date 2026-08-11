package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.IngestionContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Discovering whether this client is still spoken to, and being told so in a way it can act on.
 *
 * The Supported Contract Range is a single version today, so the range is exercised through both of
 * its edges rather than through a second accepted version. See ADR 0007.
 */
@Tag("integration")
class ContractNegotiationTest : IngestionApiTest() {

    @Test
    fun `publishes the supported contract range to an unauthenticated caller`() {
        val response = api.get("/ingestion-contract")

        assertEquals(200, response.status)
        val document = Json.parseToJsonElement(response.body).jsonObject
        assertEquals(
            IngestionContract.MINIMUM_VERSION,
            document["minimumVersion"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals(
            IngestionContract.CURRENT_VERSION,
            document["maximumVersion"]!!.jsonPrimitive.content.toInt(),
            "without the upper bound a newer client cannot tell rejection from mere advice",
        )
        assertEquals(
            IngestionContract.RECOMMENDED_VERSION,
            document["recommendedVersion"]!!.jsonPrimitive.content.toInt(),
        )
    }

    /**
     * The range is driven off the constants rather than off a literal 1, so that raising the minimum
     * without widening what the API really accepts fails here instead of in the field.
     */
    @Test
    fun `accepts every version of the supported contract range`() {
        (IngestionContract.MINIMUM_VERSION..IngestionContract.CURRENT_VERSION).forEach { version ->
            val response = api.postBatch(
                Envelopes.batch(contractVersion = version, items = listOf(item("uid-range-$version"))),
                token,
            )

            assertEquals(200, response.status, "version $version is in the range and must be accepted")
            assertEquals(listOf("accepted"), statuses(response.body))
            assertRangeDeclared(response)
        }
    }

    @Test
    fun `declares the range on a refusal too`() {
        val response = api.postBatch(Envelopes.batch(items = emptyList()), token)

        assertEquals(422, response.status)
        assertRangeDeclared(response)
    }

    @Test
    fun `declares the range even to a caller it never authenticated`() {
        val response = api.postBatch(Envelopes.batch(items = listOf(item("uid-unauthenticated"))), token = null)

        assertEquals(401, response.status)
        assertRangeDeclared(response)
    }

    @Test
    fun `tells a client below the range to update itself`() {
        val response = api.postBatch(
            Envelopes.batch(contractVersion = IngestionContract.MINIMUM_VERSION - 1, items = listOf(item("too-old"))),
            token,
        )

        assertEquals(422, response.status)
        assertEquals("contract_version_too_old", problemCode(response.body))
        assertRangeDeclared(response)
    }

    @Test
    fun `tells a client above the range that this API is the one behind`() {
        val response = api.postBatch(
            Envelopes.batch(contractVersion = IngestionContract.CURRENT_VERSION + 1, items = listOf(item("too-new"))),
            token,
        )

        assertEquals(422, response.status)
        assertEquals("contract_version_too_new", problemCode(response.body))
        assertRangeDeclared(response)
    }

    /** A JSON integer wider than an Int is still an integer, and still above the range. */
    @Test
    fun `reads a version too wide for an Int as above the range`() {
        val root = """{"contractVersion":99999999999,"recordType":"heart_rate","items":[${item("uid-wide")}]}"""

        val response = api.post(body = root.toByteArray(), token = token)

        assertEquals(422, response.status)
        assertEquals("contract_version_too_new", problemCode(response.body))
    }

    /**
     * A client told to shrink an incompatible batch would shrink and retry forever: no size of it can
     * ever be accepted.
     */
    @Test
    fun `answers incompatibility before asking for a smaller batch`() {
        val items = List(IngestionConfig.DEFAULT_MAX_ITEMS + 1) { item("over-limit-$it") }

        val response = api.postBatch(
            Envelopes.batch(contractVersion = IngestionContract.CURRENT_VERSION + 1, items = items),
            token,
        )

        assertEquals(422, response.status)
        assertEquals("contract_version_too_new", problemCode(response.body))
    }

    @Test
    fun `keeps an absent or non integer version a malformed document, not an old client`() {
        val roots = listOf(
            """{"recordType":"heart_rate","items":[${item("no-version")}]}""",
            """{"contractVersion":"1","recordType":"heart_rate","items":[${item("string-version")}]}""",
            """{"contractVersion":1.5,"recordType":"heart_rate","items":[${item("fractional-version")}]}""",
        )

        roots.forEach { root ->
            val response = api.post(body = root.toByteArray(), token = token)

            assertEquals(422, response.status, "$root should be malformed, not incompatible")
            assertEquals("invalid_batch", problemCode(response.body))
        }
    }

    /**
     * A property this version cannot interpret is kept, and kept inside the digest. It is part of what
     * the source reported, so an observation carrying it is a different observation: excluding it would
     * collapse the two onto one Observed Record Version and discard the one that carried more.
     */
    @Test
    fun `preserves a field a newer client added to an envelope, as a distinct observation`() {
        val uid = "uid-additive-envelope"
        assertEquals(200, send(item(uid)).status)

        val response = send(JsonObject(item(uid) + ("futureField" to JsonPrimitive("whatever"))))

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))

        val versions = versionsOf(uid)
        assertEquals(2, versions.size, "the richer observation was folded onto the poorer one")
        assertFalse(versions.first().envelope().containsKey("futureField"))
        assertEquals(
            "whatever",
            versions.last().envelope()["futureField"]!!.jsonPrimitive.content,
            "the stored envelope dropped a field this contract version cannot interpret",
        )
    }

    /** Preservation has to reach every level, or the loss simply moves one node deeper. */
    @Test
    fun `preserves a field a newer client nested inside a known object`() {
        val uid = "uid-additive-nested"
        val observedAt = buildJsonObject {
            put("instant", "2026-08-10T22:00:00Z")
            put("offset", "-03:00")
            put("era", "whatever")
        }

        val response = send(JsonObject(item(uid) + ("observedAt" to observedAt)))

        assertEquals(200, response.status)
        assertEquals(listOf("accepted"), statuses(response.body))
        assertEquals(
            "whatever",
            versionsOf(uid).single().envelope()["observedAt"]!!.jsonObject["era"]!!.jsonPrimitive.content,
        )
    }

    /**
     * The batch root is the exception, and deliberately: it is transport framing rather than something
     * the source observed, so it belongs to no Observed Record Version to be preserved in.
     */
    @Test
    fun `does not carry a batch root field into any observation`() {
        val uid = "uid-additive-root-ignored"
        val root = """{"contractVersion":1,"recordType":"heart_rate","futureField":{"a":1},""" +
            """"items":[${item(uid)}]}"""

        assertEquals(200, api.post(body = root.toByteArray(), token = token).status)

        assertFalse(versionsOf(uid).single().envelope().containsKey("futureField"))
    }

    /**
     * The other side of additive tolerance: ignoring what it does not know must not become ignoring
     * what it requires.
     */
    @Test
    fun `still rejects an envelope that dropped a required field`() {
        val response = send(JsonObject(item("uid-missing-required") - "observedAt"))

        assertEquals(200, response.status)
        assertEquals(listOf("rejected"), statuses(response.body))
        assertEquals(listOf(listOf("invalid_time_range")), codes(response.body))
    }

    private fun item(samsungUid: String) = Envelopes.heartRate(samsungUid = samsungUid)

    private fun assertRangeDeclared(response: IngestionApi.Response) {
        assertEquals(
            IngestionContract.MINIMUM_VERSION.toString(),
            response.header(IngestionContract.MINIMUM_HEADER),
        )
        assertEquals(
            IngestionContract.CURRENT_VERSION.toString(),
            response.header(IngestionContract.MAXIMUM_HEADER),
        )
        assertEquals(
            IngestionContract.RECOMMENDED_VERSION.toString(),
            response.header(IngestionContract.RECOMMENDED_HEADER),
        )
    }
}
