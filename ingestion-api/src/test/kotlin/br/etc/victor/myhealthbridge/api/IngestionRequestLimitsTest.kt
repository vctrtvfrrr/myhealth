package br.etc.victor.myhealthbridge.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The documented defaults, at the boundary and one step past it.
 *
 * These limits are what keeps a personal instance from being flooded by a single request, so the
 * boundary itself has to keep working.
 */
@Tag("integration")
class IngestionRequestLimitsTest : IngestionApiTest() {

    @Test
    fun `accepts exactly the maximum number of items`() {
        val items = List(MAX_ITEMS) { Envelopes.heartRate(samsungUid = "uid-limit-$it") }

        val response = api.postBatch(Envelopes.batch(items = items), token)

        assertEquals(200, response.status)
        assertEquals(MAX_ITEMS, statuses(response.body).size)
    }

    @Test
    fun `refuses one item beyond the maximum`() {
        val items = List(MAX_ITEMS + 1) { Envelopes.heartRate(samsungUid = "uid-over-$it") }

        val response = api.postBatch(Envelopes.batch(items = items), token)

        assertEquals(422, response.status)
        assertEquals("too_many_items", problemCode(response.body))
    }

    @Test
    fun `accepts a body of exactly the maximum size`() {
        val response = api.post(body = paddedBatch(MAX_BYTES), token = token)

        assertEquals(200, response.status)
    }

    @Test
    fun `refuses a body one byte beyond the maximum`() {
        val response = api.post(body = paddedBatch(MAX_BYTES + 1), token = token)

        assertEquals(413, response.status)
        assertEquals("batch_too_large", problemCode(response.body))
    }

    @Test
    fun `refuses an oversized body that never declares its length`() {
        val response = api.post(body = paddedBatch(MAX_BYTES + 1), token = token, chunked = true)

        assertEquals(413, response.status)
        assertEquals("batch_too_large", problemCode(response.body))
    }

    @Test
    fun `refuses a media type that is not json`() {
        val response = api.post(body = smallBatch(), token = token, contentType = "text/plain")

        assertEquals(415, response.status)
        assertEquals("unsupported_media_type", problemCode(response.body))
    }

    @Test
    fun `accepts json that declares its charset`() {
        val response = api.post(
            body = Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-charset"))).toByteArray(),
            token = token,
            contentType = "application/json; charset=utf-8",
        )

        assertEquals(200, response.status)
    }

    @Test
    fun `refuses a compressed body`() {
        val response = api.post(
            body = smallBatch(),
            token = token,
            headers = mapOf("Content-Encoding" to "gzip"),
        )

        assertEquals(415, response.status)
        assertEquals("unsupported_media_type", problemCode(response.body))
    }

    @Test
    fun `refuses an empty body`() {
        val response = api.post(body = ByteArray(0), token = token)

        assertEquals(400, response.status)
        assertEquals("invalid_request", problemCode(response.body))
    }

    @Test
    fun `refuses a root that is not a batch document`() {
        listOf("[]", "\"nope\"", "42", "{", "{}", """{"items":"nope"}""").forEach { root ->
            val response = api.post(body = root.toByteArray(), token = token)

            assertEquals(400, response.status, "$root should not look like a batch")
            assertEquals("invalid_request", problemCode(response.body))
        }
    }

    @Test
    fun `refuses a batch whose version or record type is structurally invalid`() {
        val item = Envelopes.heartRate(samsungUid = "uid-invalid-batch")
        val roots = listOf(
            Envelopes.batch(contractVersion = 2, items = listOf(item)),
            Envelopes.batch(contractVersion = 0, items = listOf(item)),
            """{"contractVersion":"1","recordType":"heart_rate","items":[$item]}""",
            """{"recordType":"heart_rate","items":[$item]}""",
            """{"contractVersion":1,"items":[$item]}""",
            """{"contractVersion":1,"recordType":"","items":[$item]}""",
            """{"contractVersion":1,"recordType":7,"items":[$item]}""",
            Envelopes.batch(items = emptyList()),
        )

        roots.forEach { root ->
            val response = api.post(body = root.toByteArray(), token = token)

            assertEquals(422, response.status, "$root should not be a valid batch")
            assertEquals("invalid_batch", problemCode(response.body))
        }
    }

    /**
     * Replacing a malformed sequence would persist a repaired Samsung UID that nobody sent, and would
     * map two distinct byte sequences onto the same Health Record Identity.
     */
    @Test
    fun `refuses a body that is not valid UTF-8`() {
        val batch = Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = PLACEHOLDER)))
        // 0xC3 opens a two byte sequence, so on its own inside the string the text cannot be recovered.
        val body = batch.substringBefore(PLACEHOLDER).toByteArray() +
            byteArrayOf(0xC3.toByte()) +
            batch.substringAfter(PLACEHOLDER).toByteArray()

        val response = api.post(body = body, token = token)

        assertEquals(400, response.status)
        assertEquals("invalid_request", problemCode(response.body))
        assertEquals(
            0,
            countOf(
                "select count(*) from health_record_identity where samsung_uid like '%' || chr(65533) || '%'",
            ),
            "an identifier repaired with the replacement character was persisted",
        )
    }

    @Test
    fun `answers a refused batch as a problem document`() {
        val response = api.post(body = ByteArray(0), token = token)

        assertEquals("application/problem+json", response.contentType?.substringBefore(';')?.trim())
        assertEquals(
            """{"type":"urn:myhealthbridge:ingestion:invalid_request","title":"The request body is not a batch document","status":400,"code":"invalid_request"}""",
            response.body,
        )
    }

    private fun smallBatch() =
        Envelopes.batch(items = listOf(Envelopes.heartRate(samsungUid = "uid-small"))).toByteArray()

    /**
     * A batch padded to an exact byte size with a root property the decoder is required to ignore, so
     * the size is the only thing under test.
     */
    private fun paddedBatch(size: Int): ByteArray {
        val item = Envelopes.heartRate(samsungUid = "uid-padded-$size")
        val skeleton = """{"contractVersion":1,"recordType":"heart_rate","pad":"@","items":[$item]}"""
        val padding = size - skeleton.toByteArray().size
        require(padding >= 0) { "the skeleton alone already exceeds $size bytes" }
        return skeleton.replace("@", "p".repeat(padding + 1)).toByteArray()
    }

    private companion object {
        const val PLACEHOLDER = "uid-utf8-placeholder"
        const val MAX_ITEMS = IngestionConfig.DEFAULT_MAX_ITEMS
        const val MAX_BYTES = IngestionConfig.DEFAULT_MAX_BYTES
    }
}
