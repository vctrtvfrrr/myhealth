package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.RecordState
import br.etc.victor.myhealthbridge.contract.RejectionCode
import br.etc.victor.myhealthbridge.contract.SourceIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItemValidatorTest {

    private val validator = ItemValidator(Envelopes.RECORD_TYPE)

    @Test
    fun `accepts a synthetic heart rate observation`() {
        val envelope = accept(Envelopes.heartRate())

        assertEquals("uid-1", envelope.envelope.samsungUid)
        assertEquals(Envelopes.RECORD_TYPE, envelope.recordType)
        assertEquals("present", envelope.stateName)
        assertEquals("-03:00", envelope.envelope.observedAt.offset)
        assertEquals(envelope.periodStart, envelope.periodEnd)
    }

    /**
     * The digest is the identity of an Observed Record Version already stored. Changing how the
     * canonical form is built would fork every existing record into a second version on the next
     * re-read, silently, so the rendering of an item without unknown properties is pinned here.
     */
    @Test
    fun `pins the canonical rendering of an item that carries nothing unknown`() {
        val envelope = accept(Envelopes.heartRate())

        assertEquals(
            """{"mapperVersion":"samsung-health-heart-rate/1","observedAt":{"instant":"2026-08-10T22:00:00Z","offset":"-03:00"},"recordType":"heart_rate","samsungUid":"uid-1","sourceProvenance":{"sourceApp":{"id":"com.example.shealth","kind":"known"},"sourceDevice":{"id":"device-1","kind":"known"}},"state":{"kind":"present","normalizedPayload":{"heartRate":{"unit":"/min","value":72}},"period":{"end":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"},"start":{"instant":"2026-08-10T21:59:00Z","offset":"-03:00"}},"sourcePayload":{"com.samsung.health.heart_rate.unit":"/min","heart_rate":"72"}}}""",
            envelope.canonicalJson,
            "the canonical form changed, so every stored digest for this shape is now stale",
        )
    }

    @Test
    fun `accepts a removal that keeps only the identity`() {
        val envelope = accept(Envelopes.removal())

        assertEquals("removed", envelope.stateName)
        assertNull(envelope.periodStart)
        assertEquals(RecordState.Removed(period = null), envelope.envelope.state)
    }

    @Test
    fun `accepts a removal that still knows its period`() {
        val envelope = accept(Envelopes.removal(withPeriod = true))

        assertEquals("removed", envelope.stateName)
        assertEquals("2026-08-10T21:59:00Z", envelope.periodStart.toString())
    }

    @Test
    fun `keeps an unknown source identity as an observation`() {
        val envelope = accept(Envelopes.heartRate(sourceDevice = null))

        assertEquals(SourceIdentity.Unknown, envelope.envelope.sourceProvenance.sourceDevice)
    }

    @Test
    fun `tolerates additive properties it does not know`() {
        val item = Envelopes.heartRate() + ("futureField" to JsonPrimitive("whatever"))

        accept(JsonObject(item))
    }

    @Test
    fun `rejects an item that is not even an object`() {
        assertEquals(listOf(RejectionCode.INVALID_IDENTITY), reject(JsonPrimitive("nonsense")))
    }

    @Test
    fun `rejects a missing, empty or oversized samsung uid`() {
        assertEquals(listOf(RejectionCode.INVALID_IDENTITY), reject(JsonObject(Envelopes.heartRate() - "samsungUid")))
        assertEquals(listOf(RejectionCode.INVALID_IDENTITY), reject(Envelopes.heartRate(samsungUid = "")))
        assertEquals(listOf(RejectionCode.INVALID_IDENTITY), reject(Envelopes.heartRate(samsungUid = "u".repeat(257))))
    }

    @Test
    fun `rejects a samsung uid that is not a string`() {
        val item = Envelopes.heartRate() + ("samsungUid" to JsonPrimitive(42))

        assertEquals(listOf(RejectionCode.INVALID_IDENTITY), reject(JsonObject(item)))
    }

    @Test
    fun `rejects provenance that omits an identity`() {
        val item = Envelopes.heartRate() + ("sourceProvenance" to buildJsonObject { put("sourceApp", "nope") })

        assertEquals(listOf(RejectionCode.INVALID_PROVENANCE), reject(JsonObject(item)))
    }

    @Test
    fun `rejects a known identity without an id`() {
        val item = Envelopes.heartRate() + (
            "sourceProvenance" to buildJsonObject {
                put("sourceApp", buildJsonObject { put("kind", "known") })
                put("sourceDevice", buildJsonObject { put("kind", "unknown") })
            }
            )

        assertEquals(listOf(RejectionCode.INVALID_PROVENANCE), reject(JsonObject(item)))
    }

    @Test
    fun `rejects an instant that is not expressed in UTC`() {
        assertEquals(
            listOf(RejectionCode.INVALID_TIME_RANGE),
            reject(Envelopes.heartRate(observedAt = "2026-08-10T19:00:00-03:00")),
        )
    }

    @Test
    fun `rejects an unparseable instant`() {
        assertEquals(listOf(RejectionCode.INVALID_TIME_RANGE), reject(Envelopes.heartRate(observedAt = "yesterdayZ")))
    }

    @Test
    fun `rejects a period that ends before it starts`() {
        assertEquals(
            listOf(RejectionCode.INVALID_TIME_RANGE),
            reject(Envelopes.heartRate(start = "2026-08-10T22:00:00Z", end = "2026-08-10T21:00:00Z")),
        )
    }

    @Test
    fun `rejects a present observation without a period`() {
        val state = (Envelopes.heartRate()["state"] as JsonObject) - "period"
        val item = Envelopes.heartRate() + ("state" to JsonObject(state))

        assertEquals(listOf(RejectionCode.INVALID_TIME_RANGE), reject(JsonObject(item)))
    }

    @Test
    fun `rejects an offset that is not the original wall clock offset`() {
        assertEquals(listOf(RejectionCode.INVALID_OFFSET), reject(Envelopes.heartRate(observedOffset = "Z")))
        assertEquals(listOf(RejectionCode.INVALID_OFFSET), reject(Envelopes.heartRate(observedOffset = "-3")))
        assertEquals(listOf(RejectionCode.INVALID_OFFSET), reject(Envelopes.heartRate(observedOffset = "+99:99")))
    }

    @Test
    fun `reports every reason an item failed, in the documented order`() {
        val broken = Envelopes.heartRate(
            samsungUid = "",
            observedAt = "not a time",
            observedOffset = "nope",
            unit = "bpm",
        )

        assertEquals(
            listOf(
                RejectionCode.INVALID_IDENTITY,
                RejectionCode.INVALID_TIME_RANGE,
                RejectionCode.INVALID_OFFSET,
                RejectionCode.INVALID_UNIT,
            ),
            reject(broken),
        )
    }

    @Test
    fun `rejects a record type nobody maps yet`() {
        val validation = ItemValidator("blood_pressure").validate(Envelopes.heartRate())

        assertEquals(
            listOf(RejectionCode.UNSUPPORTED_RECORD_TYPE),
            (validation as ItemValidation.Invalid).codes,
        )
    }

    @Test
    fun `rejects a mapper version this server does not know`() {
        assertEquals(
            listOf(RejectionCode.UNSUPPORTED_MAPPER),
            reject(Envelopes.heartRate(mapperVersion = "samsung-health-heart-rate/2")),
        )
    }

    @Test
    fun `rejects a normalized payload of the wrong shape`() {
        val state = (Envelopes.heartRate()["state"] as JsonObject) +
            ("normalizedPayload" to buildJsonObject { put("heartRate", "72") })
        val item = Envelopes.heartRate() + ("state" to JsonObject(state))

        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(JsonObject(item)))
    }

    @Test
    fun `rejects a heart rate outside the range the mapper can mean`() {
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.heartRate(beatsPerMinute = "0")))
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.heartRate(beatsPerMinute = "501")))
        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(Envelopes.heartRate(beatsPerMinute = "1e999")))
    }

    /**
     * The number is valid JSON and sits in the faithful source payload, so nothing before the canonical
     * rendering has any reason to look at it.
     */
    @Test
    fun `rejects an item carrying a number no decimal can represent`() {
        assertEquals(
            listOf(RejectionCode.INVALID_PAYLOAD),
            reject(withSourcePayload("""{"heart_rate":1e9999999999}""")),
        )
    }

    @Test
    fun `rejects a unit that is not the UCUM beats per minute`() {
        assertEquals(listOf(RejectionCode.INVALID_UNIT), reject(Envelopes.heartRate(unit = "bpm")))
    }

    @Test
    fun `rejects a removal that still carries health content`() {
        val state = (Envelopes.removal()["state"] as JsonObject) +
            ("normalizedPayload" to buildJsonObject { put("heartRate", buildJsonObject { put("value", 72) }) })
        val item = Envelopes.removal() + ("state" to JsonObject(state))

        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(JsonObject(item)))
    }

    @Test
    fun `rejects a state outside the closed union`() {
        val item = Envelopes.heartRate() + ("state" to buildJsonObject { put("kind", "archived") })

        assertEquals(listOf(RejectionCode.INVALID_PAYLOAD), reject(JsonObject(item)))
    }

    @Test
    fun `treats a change in provenance, offset or mapper as a different version`() {
        val digest = accept(Envelopes.heartRate()).digest.toHex()

        assertNotEquals(digest, accept(Envelopes.heartRate(sourceApp = "com.example.other")).digest.toHex())
        assertNotEquals(digest, accept(Envelopes.heartRate(observedOffset = "+02:00")).digest.toHex())
        assertNotEquals(digest, accept(Envelopes.heartRate(beatsPerMinute = "73")).digest.toHex())
    }

    /**
     * A decimal beyond what a double can hold must survive into the digest and the stored envelope, or
     * two different observations become one and the preserved content stops being faithful.
     */
    @Test
    fun `preserves a source payload decimal that no double could hold`() {
        val precise = accept(withSourcePayload("""{"reading":0.1000000000000000000001}"""))
        val rounded = accept(withSourcePayload("""{"reading":0.1}"""))

        assertTrue(
            precise.canonicalJson.contains("0.1000000000000000000001"),
            "the canonical envelope lost precision: ${precise.canonicalJson}",
        )
        assertNotEquals(rounded.digest.toHex(), precise.digest.toHex())
    }

    @Test
    fun `treats a payload that only looks different as the same version`() {
        assertEquals(
            accept(withNormalizedValue(JsonPrimitive(72))).digest.toHex(),
            accept(withNormalizedValue(JsonPrimitive("72.0".toBigDecimal()))).digest.toHex(),
        )
    }

    /** Rewrites only the normalized number, so nothing else can explain a digest that moved. */
    private fun withNormalizedValue(value: JsonPrimitive): JsonObject {
        val item = Envelopes.heartRate()
        val state = item["state"] as JsonObject
        val normalized = state["normalizedPayload"] as JsonObject
        val heartRate = normalized["heartRate"] as JsonObject

        return JsonObject(
            item + (
                "state" to JsonObject(
                    state + (
                        "normalizedPayload" to JsonObject(
                            normalized + ("heartRate" to JsonObject(heartRate + ("value" to value))),
                        )
                        ),
                )
                ),
        )
    }

    private fun withSourcePayload(sourcePayload: String): JsonObject {
        val state = (Envelopes.heartRate()["state"] as JsonObject) +
            ("sourcePayload" to Json.parseToJsonElement(sourcePayload).jsonObject)

        return JsonObject(Envelopes.heartRate() + ("state" to JsonObject(state)))
    }

    private fun accept(item: JsonObject): ObservedEnvelope =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Valid -> validation.envelope
            is ItemValidation.Invalid -> throw AssertionError("unexpectedly rejected with ${validation.codes}")
        }

    private fun reject(item: JsonElement): List<RejectionCode> =
        when (val validation = validator.validate(item)) {
            is ItemValidation.Invalid -> validation.codes
            is ItemValidation.Valid -> throw AssertionError("unexpectedly accepted")
        }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
