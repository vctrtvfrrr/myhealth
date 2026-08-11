package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Only provisioned devices may ingest, and a compromised token can be taken away. */
@Tag("integration")
class IngestionDeviceAuthTest : IngestionApiTest() {

    @Test
    fun `refuses a missing, malformed or unknown token with the same answer`() {
        val body = Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-auth")))

        listOf(null, "", "not-a-token", token.dropLast(1)).forEach { candidate ->
            val response = api.postBatch(body, candidate)

            assertEquals(401, response.status, "a $candidate token must not ingest")
            assertEquals("invalid_device_token", problemCode(response.body))
        }
    }

    @Test
    fun `lets a provisioned device ingest`() {
        assertEquals(200, send(Envelopes.heartRate(samsungUid = "uid-provisioned")).status)
    }

    @Test
    fun `stops a revoked token and lets rotation recover the device`() {
        val label = "watch-bridge"
        val revoked = api.provision(label)
        assertEquals(200, api.postBatch(batchFor("uid-revocation-1"), revoked).status)

        api.device("device", "revoke", label)
        assertEquals(401, api.postBatch(batchFor("uid-revocation-2"), revoked).status)

        val rotated = api.rotate(label)
        assertNotEquals(revoked, rotated)
        assertEquals(401, api.postBatch(batchFor("uid-revocation-3"), revoked).status)
        assertEquals(200, api.postBatch(batchFor("uid-revocation-4"), rotated).status)
    }

    @Test
    fun `leaves no window where the previous token still works after a rotation`() {
        val label = "spare-phone"
        val first = api.provision(label)
        val second = api.rotate(label)

        assertEquals(401, api.postBatch(batchFor("uid-rotated-old"), first).status)
        assertEquals(200, api.postBatch(batchFor("uid-rotated-new"), second).status)
    }

    @Test
    fun `reveals a created token once and keeps only its digest`() {
        val output = api.device("device", "create", "tablet")
        val revealed = api.tokenIn(output)

        assertEquals(1, output.split(revealed).size - 1, "the token was printed more than once")
        assertEquals(43, revealed.length, "32 random bytes in unpadded Base64URL are 43 characters")
        assertTrue(revealed.none { it == '+' || it == '/' || it == '=' }, "the token is not Base64URL without padding")

        api.query { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select encode(token_digest, 'hex') as digest from ingestion_device where device_label = 'tablet'",
                ).use { row ->
                    assertTrue(row.next(), "the device was not provisioned")
                    assertEquals(hexDigestOf(revealed), row.getString("digest"))
                }
            }
        }
    }

    @Test
    fun `refuses to provision the same label twice`() {
        api.device("device", "create", "duplicated")

        val failure = runCatching { api.device("device", "create", "duplicated") }

        assertTrue(failure.isFailure, "a repeated label must not silently replace a token")
    }

    @Test
    fun `refuses to rotate or revoke a label nobody provisioned`() {
        assertTrue(runCatching { api.device("device", "rotate", "ghost") }.isFailure)
        assertTrue(runCatching { api.device("device", "revoke", "ghost") }.isFailure)
    }

    private fun batchFor(samsungUid: String) =
        Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = samsungUid)))

    private fun hexDigestOf(token: String) =
        IngestionDevices.digestOf(token).joinToString("") { "%02x".format(it) }
}
