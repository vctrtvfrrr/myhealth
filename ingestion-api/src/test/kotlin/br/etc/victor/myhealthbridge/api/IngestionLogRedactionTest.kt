package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Everything sensitive is sent as a sentinel string, and then the log is searched for all of them.
 *
 * A leak here is not a formatting problem: the log of a personal health service is the one place where
 * health content must never accumulate.
 */
@Tag("integration")
class IngestionLogRedactionTest : IngestionApiTest() {

    @Test
    fun `writes an ingestion line from the allowlist and nothing else`() {
        val response = send(
            Envelopes.heartRate(samsungUid = "$SENTINEL_UID-line-1", sourceApp = SENTINEL_APP),
            Envelopes.heartRate(samsungUid = "$SENTINEL_UID-line-2", unit = SENTINEL_UNIT),
            withCoordinates("$SENTINEL_UID-line-3"),
        )
        val ingestion = ingestionId(response.body)

        val line = api.logs().lineSequence().last { it.contains("ingestion=$ingestion") }

        assertTrue(line.contains("contractVersion=1"), line)
        assertTrue(line.contains("items=3"), line)
        assertTrue(line.contains("accepted=2"), line)
        assertTrue(line.contains("alreadyPresent=0"), line)
        assertTrue(line.contains("rejected=1"), line)
        assertTrue(line.contains("codes=[invalid_unit]"), line)
        assertTrue(line.contains("durationMs="), line)
    }

    @Test
    fun `logs a refused batch as a code, never as the content that was refused`() {
        api.post(body = """{"nonsense":"$SENTINEL_UID-refused"}""".toByteArray(), token = token)

        assertTrue(api.logs().contains("ingestion refused code=invalid_request status=400"))
    }

    @Test
    fun `leaks no sentinel through success, rejection, invalid json, invalid auth or a refused batch`() {
        send(Envelopes.heartRate(samsungUid = "$SENTINEL_UID-ok", sourceApp = SENTINEL_APP))
        send(Envelopes.heartRate(samsungUid = "$SENTINEL_UID-bad", unit = SENTINEL_UNIT))
        send(withCoordinates("$SENTINEL_UID-located"))
        api.post(body = """{"contractVersion":1,"recordType":"$SENTINEL_UID","items":[""".toByteArray(), token = token)
        api.post(body = """{"items":[{"samsungUid":"$SENTINEL_UID"}]}""".toByteArray(), token = token)
        api.postBatch(Envelopes.batch(items = listOf(Envelopes.heartRate())), SENTINEL_TOKEN)
        api.post(body = sentinelBatch(), token = token, contentType = "text/plain")

        val logs = api.logs()

        listOf(
            SENTINEL_UID,
            SENTINEL_APP,
            SENTINEL_DEVICE,
            SENTINEL_UNIT,
            SENTINEL_TOKEN,
            SENTINEL_LATITUDE,
            SENTINEL_BEATS,
            token,
        ).forEach { sentinel ->
            assertFalse(sentinel in logs, "the log leaked $sentinel")
        }
    }

    /** Coordinates arrive as a property this contract does not know, which must be tolerated, not logged. */
    private fun withCoordinates(samsungUid: String): JsonObject = buildJsonObject {
        Envelopes.heartRate(
            samsungUid = samsungUid,
            beatsPerMinute = SENTINEL_BEATS,
            sourceDevice = SENTINEL_DEVICE,
        ).forEach { (key, value) -> put(key, value) }
        putJsonObject("location") {
            put("latitude", SENTINEL_LATITUDE)
            put("longitude", "-46.000000")
        }
    }

    private fun sentinelBatch() =
        Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "$SENTINEL_UID-media-type"))).toByteArray()

    private companion object {
        const val SENTINEL_UID = "SENTINEL-samsung-uid"
        const val SENTINEL_APP = "SENTINEL-source-app"
        const val SENTINEL_DEVICE = "SENTINEL-source-device"
        const val SENTINEL_UNIT = "SENTINEL-unit"
        const val SENTINEL_TOKEN = "SENTINEL-device-token"
        const val SENTINEL_LATITUDE = "-23.SENTINEL"
        const val SENTINEL_BEATS = "77.7654321"
    }
}
